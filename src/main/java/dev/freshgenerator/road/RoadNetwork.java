package dev.freshgenerator.road;

import dev.freshgenerator.core.Diagnostics;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.road.Bridge;
import dev.freshgenerator.core.road.RoadGraph;
import dev.freshgenerator.core.road.RoadLine;
import dev.freshgenerator.core.road.RoadRouter;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** The roads of one world (user request 24.11): which villages exist, which pairs a road joins, and each road's plan.
 * Everything is a pure function of the world seed and the terrain, memoized once per world and computed by whichever
 * generation thread asks first - a road crossing a chunk is the same whatever the order of chunks. Holds no world, random
 * state or drainage field (the callers pass them): a closed world is not kept alive by its roads. */
public final class RoadNetwork {
    public static final Identifier VILLAGES = Identifier.parse("freshgenerator:villages");
    private static final Map<RandomState, RoadNetwork> NETWORKS = new WeakHashMap<>();
    private static final RoadNetwork ABSENT = new RoadNetwork();

    /** A road and the two villages it joins, with the materials of its bridges. */
    public record Road(RoadLine line, VillagePlan a, VillagePlan b, RoadPainter.Materials[] materials) {
        boolean inVillage(int x, int z) { return a.inside(x, z) || b.inside(x, z); }
    }
    private record Edge(long a, long b) { }

    private final RegistryAccess registries;
    private final NoiseBasedChunkGenerator generator;
    private final StructureTemplateManager templates;
    private final ResourceKey<Level> dimension;
    private final LevelHeightAccessor height;
    private final Holder<StructureSet> villages;
    private final RandomSpreadStructurePlacement placement;
    private final long seed;
    private final int spacing, reachCells;
    private final double reach;
    /** Memos of pure values, bounded (code audit 25.11 K3: they grew without limit on long explorations; a dropped
     * value is computed again, the same). */
    private final Memo<Long, RoadGraph.Site> sites = new Memo<>(1 << 17, "site");
    private final Memo<Long, VillagePlan> plans = new Memo<>(1 << 13, "village plan");
    private final Memo<Edge, Boolean> edges = new Memo<>(1 << 16, "road pair");
    private final Memo<Edge, Road> roads = new Memo<>(1 << 12, "road");
    /** Village starts the planner built, waiting for the world's structure stage: {structure, start} by start chunk. A
     * village is generated once - by the planner or by the world, whichever comes first (Structure.generate costs
     * ~0.7 s per village in this world: a height query per jigsaw connection). */
    private final ConcurrentHashMap<Long, Object[]> pending = new ConcurrentHashMap<>();
    /** Order of the waiting starts: past PENDING_LIMIT the oldest go (a start the world never asks for - the area is
     * never visited - must not stop the hand-over for the rest). */
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static final int PENDING_LIMIT = 512;

    private RoadNetwork() {
        registries = null; generator = null; templates = null; dimension = null; height = null; villages = null; placement = null;
        seed = 0; spacing = 1; reachCells = 0; reach = 0; structureState = new java.lang.ref.WeakReference<>(null);
    }
    private RoadNetwork(ServerLevel level, NoiseBasedChunkGenerator generator, Holder<StructureSet> villages, RandomSpreadStructurePlacement placement) {
        this.registries = level.registryAccess();
        this.generator = generator;
        this.templates = level.getStructureTemplateManager();
        this.dimension = level.dimension();
        this.height = LevelHeightAccessor.create(level.getMinY(), level.getHeight());
        this.villages = villages;
        this.placement = placement;
        this.seed = level.getSeed();
        this.spacing = placement.spacing();
        this.reach = RoadGraph.FACTOR * RoadGraph.MAX_SPAN + 2 * RoadGraph.REACH + 16;
        this.reachCells = (int) Math.ceil(reach / (spacing * 16.0)) + 1;
        this.structureState = new java.lang.ref.WeakReference<>(level.getChunkSource().getGeneratorState());
    }

    /** The world's structure placement state, held weakly (it holds the random state the network is keyed by). */
    private final java.lang.ref.WeakReference<net.minecraft.world.level.chunk.ChunkGeneratorStructureState> structureState;
    /** Surface structures of other sets a road walks round, by set and start chunk: their box or null. */
    private final Memo<Spot, int[]> foreignStarts = new Memo<>(1 << 14, "structure beside a road");
    private record Spot(int set, long chunk) { }
    /** Structures a road keeps off (code audit 25.11 Fable M-04: a path ran through ruined portals with lava and magma -
     * seed 314159265 X-379 Z1389). Mansions are left out: rare in a corridor, costly to lay out. */
    private static boolean surfaceStructure(String id) {
        return id.equals("minecraft:pillager_outpost") || id.equals("minecraft:igloo") || id.equals("minecraft:desert_pyramid")
                || id.equals("minecraft:jungle_pyramid") || id.equals("minecraft:swamp_hut")
                || (id.startsWith("minecraft:ruined_portal") && !id.endsWith("_ocean") && !id.endsWith("_nether"));
    }
    /** Boxes {minX, minZ, maxX, maxZ} of the surface structures of other sets that reach into a road's corridor. */
    private List<int[]> foreign(RandomState state, RoadGraph.Site a, RoadGraph.Site b) {
        var structures = structureState.get();
        if (structures == null) return List.of();
        double r = RoadGraph.corridor(a, b) / 2 + 96, mx = (a.x() + b.x()) / 2, mz = (a.z() + b.z()) / 2;
        int minCX = (int) Math.floor((mx - r) / 16), maxCX = (int) Math.floor((mx + r) / 16);
        int minCZ = (int) Math.floor((mz - r) / 16), maxCZ = (int) Math.floor((mz + r) / 16);
        List<int[]> out = new ArrayList<>();
        var sets = structures.possibleStructureSets();
        for (int si = 0; si < sets.size(); si++) {
            Holder<StructureSet> set = sets.get(si);
            if (set.value() == villages.value() || !(set.value().placement() instanceof RandomSpreadStructurePlacement spread)) continue;
            boolean surface = false;
            for (var e : set.value().structures()) surface |= surfaceStructure(e.structure().unwrapKey().map(k -> k.identifier().toString()).orElse(""));
            if (!surface) continue;
            int sp = spread.spacing();
            for (int j = Math.floorDiv(minCZ, sp); j <= Math.floorDiv(maxCZ, sp); j++)
                for (int i = Math.floorDiv(minCX, sp); i <= Math.floorDiv(maxCX, sp); i++) {
                    ChunkPos pos = spread.getPotentialStructureChunk(seed, i * sp, j * sp);
                    if (!RoadGraph.covers(a, b, pos.getMiddleBlockX(), pos.getMiddleBlockZ(), 96)) continue;
                    int[] box = foreignStarts.get(new Spot(si, pos.pack()), () -> {
                        if (!spread.isStructureChunk(structures, pos.x(), pos.z())) return null;
                        StructureStart start = startAt(state, set, pos);
                        if (start == null) return null;
                        var bb = start.getBoundingBox();
                        // A ruined portal spreads netherrack and magma far beyond its template (seed 314159265 X-318
                        // Z5139: box 6x6, netherrack over 23x18): the road keeps 12 blocks off it, 3 off the others.
                        String id = registries.lookupOrThrow(Registries.STRUCTURE).getResourceKey(start.getStructure())
                                .map(k -> k.identifier().toString()).orElse("");
                        int margin = id.startsWith("minecraft:ruined_portal") ? 12 : 3;
                        return new int[]{bb.minX() - margin, bb.minZ() - margin, bb.maxX() + margin, bb.maxZ() + margin};
                    });
                    if (box != null) out.add(box);
                }
        }
        return out;
    }
    /** The start of a structure set at a chunk with the native selection of ChunkGenerator.createStructures (weighted
     * choice, the next one when a structure does not fit), or null. */
    private StructureStart startAt(RandomState state, Holder<StructureSet> set, ChunkPos pos) {
        var list = set.value().structures();
        var climate = state.createClimateSampler(SamplerContext.builder().enableCaches().build());
        List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(list);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, pos.x(), pos.z());
        int total = 0;
        for (var e : entries) total += e.weight();
        while (!entries.isEmpty()) {
            int index = 0;
            if (list.size() > 1) {
                int pick = random.nextInt(total);
                for (var e : entries) { pick -= e.weight(); if (pick < 0) break; index++; }
            }
            var chosen = entries.get(index);
            var structure = chosen.structure();
            HolderSet<Biome> biomes = structure.value().biomes();
            StructureStart start = structure.value().generate(structure, dimension, registries, generator, generator.getBiomeSource(), climate,
                    state, templates, seed, pos, 0, height, biomes::contains);
            if (start.isValid()) return start;
            if (list.size() == 1) break;
            entries.remove(index);
            total -= chosen.weight();
        }
        return null;
    }

    /** The network of a world with the adapted village set, or null. */
    public static RoadNetwork of(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState state) {
        synchronized (NETWORKS) {
            RoadNetwork n = NETWORKS.get(state);
            if (n == null) {
                n = create(level, generator);
                NETWORKS.put(state, n);
            }
            return n == ABSENT ? null : n;
        }
    }
    private static RoadNetwork create(ServerLevel level, NoiseBasedChunkGenerator generator) {
        var set = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).get(ResourceKey.create(Registries.STRUCTURE_SET, VILLAGES));
        if (set.isEmpty() || !(set.get().value().placement() instanceof RandomSpreadStructurePlacement spread)) {
            Diagnostics.warnOnce("road-set", "FreshGenerator: no adapted village set " + VILLAGES + "; village roads are off");
            return ABSENT;
        }
        return new RoadNetwork(level, generator, set.get(), spread);
    }

    /** The network of a world, if one was created (no creation: structure generation calls this). */
    public static RoadNetwork existing(RandomState state) {
        synchronized (NETWORKS) {
            RoadNetwork n = NETWORKS.get(state);
            return n == ABSENT ? null : n;
        }
    }
    /** The start the planner already built for this structure at this chunk, handed over once; null if none. */
    public StructureStart takePending(Holder<net.minecraft.world.level.levelgen.structure.Structure> structure, ChunkPos pos) {
        long key = pos.pack();
        Object[] p = pending.get(key);
        if (p == null || p[0] != structure.value() || !pending.remove(key, p)) return null;
        // The order queue lets go of it too (code audits 25.11 OpenAI 3, Opus KO-05: handed-over keys stayed in it).
        pendingOrder.remove(key);
        return (StructureStart) p[1];
    }
    /** A village start the world built: the planner reads it instead of building it again. */
    public void record(Holder<net.minecraft.world.level.levelgen.structure.Structure> structure, ChunkPos pos, StructureStart start) {
        if (!start.isValid() || !placement.getPotentialStructureChunk(seed, pos.x(), pos.z()).equals(pos)) return;
        long key = cell(Math.floorDiv(pos.x(), spacing), Math.floorDiv(pos.z(), spacing));
        if (plans.holds(key)) return;
        String id = structure.unwrapKey().map(k -> k.identifier().getPath()).orElse("village_plains");
        plans.offer(key, VillagePlan.of(start, id));
    }

    private static long cell(int i, int j) { return ((long) i << 32) ^ (j & 0xffffffffL); }

    /** The roads that may cover a chunk. */
    public List<Road> roadsAt(RandomState state, HydroField field, int chunkX, int chunkZ) {
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        double px = x0 + 8, pz = z0 + 8;
        int ci = Math.floorDiv(chunkX, spacing), cj = Math.floorDiv(chunkZ, spacing);
        List<RoadGraph.Site> near = new ArrayList<>();
        for (int dj = -reachCells; dj <= reachCells; dj++) for (int di = -reachCells; di <= reachCells; di++) {
            RoadGraph.Site s = site(state, field, ci + di, cj + dj);
            if (s != null && Math.hypot(s.x() - px, s.z() - pz) <= reach) near.add(s);
        }
        List<RoadGraph.Site[]> pairs = new ArrayList<>();
        java.util.LinkedHashSet<RoadGraph.Site> needed = new java.util.LinkedHashSet<>();
        for (int a = 0; a < near.size(); a++) for (int b = a + 1; b < near.size(); b++) {
            RoadGraph.Site sa = near.get(a), sb = near.get(b);
            if (sa.cell() > sb.cell()) { RoadGraph.Site t = sa; sa = sb; sb = t; }
            // The road is routed inside the same ellipse (foci at the sites): a chunk it touches always asks for it
            // (code audit 25.11 N-7: the corridor used the village centres, the chunk filter the sites).
            if (sa.distance(sb) > RoadGraph.MAX_SPAN || !RoadGraph.covers(sa, sb, px, pz, 24) || !RoadGraph.rolled(seed, sa, sb)) continue;
            pairs.add(new RoadGraph.Site[]{sa, sb});
            if (edges.map.containsKey(new Edge(sa.cell(), sb.cell()))) continue;
            needed.add(sa); needed.add(sb);
            needed.addAll(blockers(state, field, sa, sb));
        }
        prefetch(state, needed);
        List<Road> out = new ArrayList<>();
        for (RoadGraph.Site[] p : pairs) {
            if (!edge(state, field, p[0], p[1])) continue;
            Road r = road(state, field, p[0], p[1]);
            if (r != null && r.line().touches(x0, z0, x0 + 15, z0 + 15)) out.add(r);
        }
        return out;
    }

    /** Builds the plans of several villages side by side and returns when all are known (code audit 25.11 Opus KO-10:
     * built one after another by the first thread that asked, they held the other generation threads waiting on the
     * road; the generation around a new village took up to twice as long). Pure values: the same in any order. */
    private void prefetch(RandomState state, java.util.Collection<RoadGraph.Site> sites) {
        List<RoadGraph.Site> todo = new ArrayList<>();
        for (RoadGraph.Site s : sites) if (!plans.map.containsKey(s.cell())) todo.add(s);
        if (todo.size() <= 1) return;
        List<CompletableFuture<?>> running = new ArrayList<>(todo.size());
        for (RoadGraph.Site s : todo) running.add(CompletableFuture.runAsync(() -> plan(state, s), PLANNER));
        for (CompletableFuture<?> f : running) f.join();
    }
    private static final java.util.concurrent.ExecutorService PLANNER = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 2), runnable -> {
                Thread t = new Thread(runnable, "FreshGenerator village planner");
                t.setDaemon(true);
                return t;
            });
    /** The sites closer to both ends of a pair than the ends to each other: the villages that may take the road. */
    private List<RoadGraph.Site> blockers(RandomState state, HydroField field, RoadGraph.Site a, RoadGraph.Site b) {
        List<RoadGraph.Site> out = new ArrayList<>();
        double d = a.distance(b), mx = (a.x() + b.x()) / 2, mz = (a.z() + b.z()) / 2;
        int cs = spacing * 16;
        int i0 = Math.floorDiv((int) Math.floor(mx - d), cs) - 1, i1 = Math.floorDiv((int) Math.ceil(mx + d), cs) + 1;
        int j0 = Math.floorDiv((int) Math.floor(mz - d), cs) - 1, j1 = Math.floorDiv((int) Math.ceil(mz + d), cs) + 1;
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
            RoadGraph.Site s = site(state, field, i, j);
            if (s != null && !RoadGraph.neighbours(a, b, List.of(s))) out.add(s);
        }
        return out;
    }

    /** The approximate village of a placement cell, or null: a village biome at the corner of its start chunk (where
     * the start piece is placed) or at four points around it - the meeting point of a rotated town centre lies within
     * about ten blocks (code audit 25.11: the chunk's middle alone missed a real snowy village). */
    RoadGraph.Site site(RandomState state, HydroField field, int i, int j) {
        long key = cell(i, j);
        return sites.get(key, () -> {
            ChunkPos pos = placement.getPotentialStructureChunk(seed, i * spacing, j * spacing);
            // The cell's chance of a village (the set's frequency, user request 25.11): an empty cell is no site.
            var structures = structureState.get();
            if (structures == null || !placement.isStructureChunk(structures, pos.x(), pos.z())) return null;
            int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ();
            BiomeResolver biomes = generator.getBiomeSource().createCachingResolver(state);
            for (int[] p : SITE_PROBES) {
                int x = x0 + p[0], z = z0 + p[1];
                double y = field.model().groundAt(x, z);
                Holder<Biome> b = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock((int) Math.floor(y)), QuartPos.fromBlock(z));
                for (var entry : villages.value().structures())
                    if (entry.structure().value().biomes().contains(b)) return new RoadGraph.Site(key, x0, z0, field.model().groundAt(x0, z0));
            }
            return null;
        });
    }
    private static final int[][] SITE_PROBES = {{0, 0}, {-10, -10}, {10, -10}, {-10, 10}, {10, 10}};

    /** Relative-neighbourhood test of a pair over the villages around it. A site that would take the road is checked
     * against the real village of its cell: about one site in eight is no village (a biome edge, a meeting point on the
     * water), and it must not keep two real neighbours apart (seed 31337: 8 of 18 drawn pairs lost). */
    private boolean edge(RandomState state, HydroField field, RoadGraph.Site a, RoadGraph.Site b) {
        return Boolean.TRUE.equals(edges.get(new Edge(a.cell(), b.cell()), () -> {
            // Both ends must be real villages (code audit 26.11 KO-04: 5 of 9 drawn pairs had an empty site at one end -
            // two villages built for nothing each time). Their plans are prefetched with the blockers.
            if (plan(state, a) == null || plan(state, b) == null) return false;
            for (RoadGraph.Site s : blockers(state, field, a, b)) if (plan(state, s) != null) return false;
            return true;
        }));
    }

    /** The real village of a cell (the native structure selection with Structure.generate), or null. */
    VillagePlan plan(RandomState state, RoadGraph.Site s) {
        return plans.get(s.cell(), () -> {
            int i = (int) (s.cell() >> 32), j = (int) s.cell();
            ChunkPos pos = placement.getPotentialStructureChunk(seed, i * spacing, j * spacing);
            var list = villages.value().structures();
            var climate = state.createClimateSampler(SamplerContext.builder().enableCaches().build());
            List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(list);
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            random.setLargeFeatureSeed(seed, pos.x(), pos.z());
            int total = 0;
            for (var e : entries) total += e.weight();
            while (!entries.isEmpty()) {
                StructureSet.StructureSelectionEntry chosen;
                int index = 0;
                if (list.size() == 1) chosen = entries.get(0);
                else {
                    int pick = random.nextInt(total);
                    for (var e : entries) { pick -= e.weight(); if (pick < 0) break; index++; }
                    chosen = entries.get(index);
                }
                var structure = chosen.structure();
                HolderSet<Biome> biomes = structure.value().biomes();
                StructureStart start = structure.value().generate(structure, dimension, registries, generator, generator.getBiomeSource(), climate,
                        state, templates, seed, pos, 0, height, biomes::contains);
                if (start.isValid()) {
                    if (pending.put(pos.pack(), new Object[]{structure.value(), start}) == null) pendingOrder.add(pos.pack());
                    while (pending.size() > PENDING_LIMIT) {
                        Long old = pendingOrder.poll();
                        if (old == null) break;
                        pending.remove(old);
                    }
                    return VillagePlan.of(start, structure.unwrapKey().map(k -> k.identifier().getPath()).orElse("village_plains"));
                }
                if (list.size() == 1) break;
                entries.remove(index);
                total -= chosen.weight();
            }
            return null;
        });
    }

    private Road road(RandomState state, HydroField field, RoadGraph.Site a, RoadGraph.Site b) {
        return roads.get(new Edge(a.cell(), b.cell()), () -> {
            VillagePlan pa = plan(state, a), pb = plan(state, b);
            if (pa == null || pb == null || pa.exits().isEmpty() || pb.exits().isEmpty()) return null;
            // Other villages near the corridor: the road keeps off them (it joins its two villages only). Only real ones:
            // an approximate site without a village made the road bend round empty land (code audit 25.11 OpenAI 5).
            List<double[]> avoid = new ArrayList<>();
            List<RoadGraph.Site> corridor = new ArrayList<>();
            int cs = spacing * 16;
            double minX = Math.min(pa.x(), pb.x()) - reach / 2, maxX = Math.max(pa.x(), pb.x()) + reach / 2;
            double minZ = Math.min(pa.z(), pb.z()) - reach / 2, maxZ = Math.max(pa.z(), pb.z()) + reach / 2;
            for (int j = Math.floorDiv((int) minZ, cs) - 1; j <= Math.floorDiv((int) maxZ, cs) + 1; j++)
                for (int i = Math.floorDiv((int) minX, cs) - 1; i <= Math.floorDiv((int) maxX, cs) + 1; i++) {
                    RoadGraph.Site s = site(state, field, i, j);
                    if (s != null && s.cell() != a.cell() && s.cell() != b.cell() && RoadGraph.covers(a, b, s.x(), s.z(), 72)) corridor.add(s);
                }
            prefetch(state, corridor);
            for (RoadGraph.Site s : corridor) if (plan(state, s) != null) avoid.add(new double[]{s.x(), s.z()});
            List<int[]> structures = foreign(state, a, b);
            RoadRouter.Blocked blocked = (x, z) -> {
                if (pa.building(x, z, 1) || pb.building(x, z, 1)) return true;
                for (double[] o : avoid) if (Math.abs(x - o[0]) < 72 && Math.abs(z - o[1]) < 72) return true;
                for (int[] o : structures) if (x >= o[0] && x <= o[2] && z >= o[1] && z <= o[3]) return true;
                return false;
            };
            RoadRouter router = new RoadRouter(new FieldTerrain(field), blocked, (x, z) -> pa.inside(x, z) || pb.inside(x, z),
                    (x, z) -> pa.near(x, z, 16) || pb.near(x, z, 16));
            long id = dev.freshgenerator.core.road.RoadHash.of(seed, a.cell(), b.cell());
            RoadLine line = router.plan(id, seed, pa.exits(), pb.exits(), a.x(), a.z(), b.x(), b.z(), RoadGraph.corridor(a, b));
            if (line == null) return null;
            BiomeResolver biomes = generator.getBiomeSource().createCachingResolver(state);
            RoadPainter.Materials[] materials = new RoadPainter.Materials[line.bridges.size()];
            for (int k = 0; k < materials.length; k++) {
                Bridge br = line.bridges.get(k);
                int bx = br.alongX ? (br.lo + br.hi) / 2 : br.centre, bz = br.alongX ? br.centre : (br.lo + br.hi) / 2;
                materials[k] = RoadPainter.Materials.of(biomes.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(br.water), QuartPos.fromBlock(bz)), br.seed);
            }
            return new Road(line, pa, pb, materials);
        });
    }

    /** Planned roads so far (audits). */
    public List<Road> known() {
        List<Road> out = new ArrayList<>();
        for (var f : roads.map.values()) { Road r = f.getNow(null); if (r != null) out.add(r); }
        return out;
    }
    public int sitesKnown() { return sites.map.size(); }

    /** Once-per-key computation shared by the generation threads (the others wait for the first). A failure is kept as
     * "none" (code audit 25.11 N-9: a failure dropped the entry, so one chunk drew the road and the next skipped it, and
     * every chunk paid the computation again); past the limit the finished entries are dropped and computed again. */
    static final class Memo<K, V> {
        final ConcurrentHashMap<K, CompletableFuture<V>> map = new ConcurrentHashMap<>();
        /** Keys in the order they came: past the limit the oldest finished quarter goes (code audits 25.11 Fable K-09,
         * Opus KO-05: dropping every finished entry at once made each village near the player plan again in a row). */
        final java.util.concurrent.ConcurrentLinkedQueue<K> order = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        /** Failed computations per key: a failure is no fact about the world - the key is computed again, up to
         * RETRIES times (code audits 26.11 OpenAI FG94-02, FG094-02: one transient exception kept the plan absent until
         * the entry was evicted, and the village the world then built could not replace it). */
        private final ConcurrentHashMap<K, Integer> failed = new ConcurrentHashMap<>();
        static final int RETRIES = 3;
        private final int limit;
        private final String what;
        Memo(int limit, String what) { this.limit = limit; this.what = what; }
        V get(K key, Supplier<V> compute) {
            CompletableFuture<V> f = map.get(key);
            if (f != null) return f.join();
            if (map.size() > limit) trim();
            CompletableFuture<V> mine = new CompletableFuture<>();
            f = map.putIfAbsent(key, mine);
            if (f != null) return f.join();
            V v;
            try {
                v = compute.get();
            } catch (RuntimeException e) {
                // Reported at the 1st, 2nd, 4th, 8th... failure of this world (code audits 25.11 Fable K-10, Opus KO-12:
                // one message per program run hid the next world's failures).
                int n = failures.incrementAndGet();
                if (Integer.bitCount(n) == 1) Diagnostics.sink.accept("FreshGenerator: a " + what + " for village roads failed and is skipped ("
                        + n + (n == 1 ? " failure" : " failures") + " in this world so far): " + e);
                v = null;
                if (failed.size() > limit) failed.clear();
                // The waiting threads get no value this time; the next request computes it again.
                if (failed.merge(key, 1, Integer::sum) < RETRIES) map.remove(key, mine);
            } catch (Error e) {
                map.remove(key, mine);
                mine.completeExceptionally(e);
                throw e;
            }
            // A key enters the eviction order once it stays (a removed failure left its key in the queue for ever: 10 000
            // keys for an empty map, code audit 27.11 OpenAI K-11). A value offered meanwhile has completed the future
            // already and wins over this computation (K-10: an offered plan was lost to a null finishing later).
            if (map.get(key) == mine) order.add(key);
            mine.complete(v);
            return mine.join();
        }
        /** A value known from elsewhere (a village the world built): it replaces an absent or failed entry, never a
         * value. The limit holds here as well (code audit 26.11 OpenAI FG094-03: 10 000 offers past a limit of 4). */
        void offer(K key, V value) {
            if (value == null) return;
            if (map.size() > limit) trim();
            boolean[] added = {false};
            map.compute(key, (k, f) -> {
                if (f == null) { added[0] = true; return CompletableFuture.completedFuture(value); }
                if (!f.isDone() && f.complete(value)) return f; // a computation in flight: the known value wins
                return f.isDone() && !f.isCompletedExceptionally() && f.getNow(null) == null ? CompletableFuture.completedFuture(value) : f;
            });
            if (added[0]) order.add(key);
            failed.remove(key);
        }
        /** Whether a key holds a value (not an absent, failed or unfinished entry). */
        boolean holds(K key) {
            CompletableFuture<V> f = map.get(key);
            return f != null && f.isDone() && !f.isCompletedExceptionally() && f.getNow(null) != null;
        }
        private synchronized void trim() {
            int target = limit / 4 * 3;
            for (int guard = map.size() + 1; map.size() > target && guard > 0; guard--) {
                K old = order.poll();
                if (old == null) break;
                CompletableFuture<V> f = map.get(old);
                if (f != null && !f.isDone()) { order.add(old); continue; } // still computed by another thread
                map.remove(old);
            }
        }
    }
}
