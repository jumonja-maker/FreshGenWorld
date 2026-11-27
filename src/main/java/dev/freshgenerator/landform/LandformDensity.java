package dev.freshgenerator.landform;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.Landforms;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Terrain height with the rare large landforms of {@link Landforms} (volcanoes, impact craters, rifts) applied to the
 * input height. The site ground is the input height at the site centre. One model per compile context (world). */
public record LandformDensity(DensityFunction height) implements DensityFunction {
    public static final Identifier ID = Identifier.parse("freshgenerator:landforms");
    public static final String LABEL = "FG landforms";
    public static final MapCodec<LandformDensity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.CODEC.fieldOf("height").forGetter(LandformDensity::height)
    ).apply(i, LandformDensity::new));

    private static final Map<CompileContext, Map<DensityFunction, World>> WORLDS = new WeakHashMap<>();

    @Override public DensitySampler compileSampler(CompileContext context) {
        World world;
        synchronized (WORLDS) {
            Map<DensityFunction, World> byInput = WORLDS.computeIfAbsent(context, c -> new java.util.HashMap<>());
            world = byInput.get(height);
            if (world == null) {
                world = new World(new Landforms(context.createRandom(ID).nextLong()), height.compileSampler(context));
                byInput.put(height, world);
            }
        }
        return new Sampler(world, height.compileSampler(context));
    }
    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return new LandformDensity(rule.rewrite(height)); }
    @Override public Interval range() {
        Interval r = height.range();
        // A rift floor stands at an absolute height (RIFT_FLOOR_MIN up), not relative to the input: on high ground the
        // bound input - 120 lay above it (code audit 27.11 OpenAI K-05: 59.6 under a declared floor of 80).
        return Interval.encapsulating(Math.min(r.min() - 120, (float) (Landforms.RIFT_FLOOR_MIN - 1)),
                Math.max(r.max(), (float) (Landforms.SOFT_TOP + Landforms.SOFT_ROOM)));
    }
    @Override public int domainAxes() { return AXIS_X | AXIS_Z; }
    @Override public MapCodec<LandformDensity> codec() { return CODEC; }

    /** The landform model of a world with its site memo and site ground sampler. */
    public static final class World implements Landforms.SiteCache, Landforms.Ground {
        public final Landforms model;
        private final DensitySampler ground;
        private final Map<Long, Object> sites = new ConcurrentHashMap<>();
        private final Map<Long, Double> bases = new ConcurrentHashMap<>();
        private final Map<Landforms.Volcano, Double> lava = new ConcurrentHashMap<>();
        private static final Object NONE = new Object();
        World(Landforms model, DensitySampler ground) { this.model = model; this.ground = ground; }

        /** The base ground at a point. A caching context per call: no thread-local (code audit 26.11 KO-07: a context held
         * in the worker threads kept the samplers of a closed world). The ground is read rarely - site centres, rift lines,
         * crater rims - and memoized. */
        @Override public double at(int x, int z) { return ground.sampleValue(SamplerContext.builder().enableCaches().build(), x, 0, z); }
        @Override public Landforms.Volcano volcano(int i, int j, Landforms m, Landforms.Ground g) {
            long key = key(i, j, 1);
            Object o = sites.get(key);
            if (o == null) { var v = m.volcano(i, j, g); o = v == null ? NONE : v; trim(); sites.put(key, o); }
            return o == NONE ? null : (Landforms.Volcano) o;
        }
        @Override public Landforms.Crater crater(int i, int j, Landforms m, Landforms.Ground g) {
            long key = key(i, j, 2);
            Object o = sites.get(key);
            if (o == null) { var c = m.crater(i, j, g); o = c == null ? NONE : c; trim(); sites.put(key, o); }
            return o == NONE ? null : (Landforms.Crater) o;
        }
        @Override public Landforms.Rift rift(int i, int j, Landforms m, Landforms.Ground g) {
            long key = key(i, j, 5);
            Object o = sites.get(key);
            if (o == null) { var r = m.rift(i, j, g, this); o = r == null ? NONE : r; trim(); sites.put(key, o); }
            return o == NONE ? null : (Landforms.Rift) o;
        }
        @Override public double base(int x, int z, Landforms.Ground g) {
            long key = key(x, z, 3);
            Double v = bases.get(key);
            if (v == null) { v = g.at(x, z); trim(); bases.put(key, v); }
            return v;
        }
        /** Sites that can touch each influence cell: one lookup per sample instead of eighteen site lookups (the
         * drainage model reads the ground at thousands of points per tile). Direct-mapped on the low INFLUENCE_BITS of
         * both cell indices (a 32 x 32 km window without conflicts): one array read per sample - a ConcurrentHashMap
         * with a boxed key cost 3.4 ms per desert chunk. A pure memo: a replaced entry is the same list again. */
        private static final int INFLUENCE_BITS = 6, INFLUENCE_MASK = (1 << INFLUENCE_BITS) - 1;
        private record Influence(int i, int j, java.util.List<Object> list) { }
        private final java.util.concurrent.atomic.AtomicReferenceArray<Influence> influence = new java.util.concurrent.atomic.AtomicReferenceArray<>(1 << (2 * INFLUENCE_BITS));
        java.util.List<Object> candidates(int x, int z) {
            int ci = Math.floorDiv(x, Landforms.INFLUENCE_CELL), cj = Math.floorDiv(z, Landforms.INFLUENCE_CELL);
            int slot = (ci & INFLUENCE_MASK) | (cj & INFLUENCE_MASK) << INFLUENCE_BITS;
            Influence e = influence.getOpaque(slot);
            if (e != null && e.i == ci && e.j == cj) return e.list;
            java.util.List<Object> list = model.candidates(ci, cj, this, this);
            influence.setOpaque(slot, new Influence(ci, cj, list));
            return list;
        }
        /** Landform height at (x, z) from the base height h there. */
        public double apply(int x, int z, double h) {
            java.util.List<Object> c = candidates(x, z);
            return c.isEmpty() ? h : model.apply(x, z, h, Landforms.touching(c, x, z), this, this);
        }
        /** Volcanoes, craters and rifts touching (x, z). */
        public java.util.List<Object> near(int x, int z) { return Landforms.touching(candidates(x, z), x, z); }
        public double baseAt(int x, int z) { return base(x, z, this); }
        /** Whether the drainage network ends at (x, z): a rift or the lava crater of an active volcano that holds a lake. */
        public boolean sink(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            return !c.isEmpty() && (Landforms.sink(Landforms.touching(c, x, z), x, z) || !Double.isNaN(lavaAt(x, z)));
        }
        /** Whether (x, z) lies inside a rift (its floor and walls). */
        public boolean inRift(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            return !c.isEmpty() && Landforms.sink(Landforms.touching(c, x, z), x, z);
        }
        /** Whether the ground below the sea level at (x, z) is no sea: the open air of a rift and of the band beside its
         * walls, and the bowl of a crater closed above the sea level (Landforms.closedBowl). The drainage model alone
         * decides the water there (HydroField.Column.seaFree). */
        public boolean seaFree(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            if (c.isEmpty()) return false;
            java.util.List<Object> t = Landforms.touching(c, x, z);
            return Landforms.nearRift(t, x, z, Landforms.RIFT_DRY_MARGIN) || model.closedBowl(t, x, z, this, this);
        }
        /** Whether the cone of a volcano reaches (x, z): an ocean volcano (island or seamount) or the flank of a coastal
         * one running out over the sea (a shield on the shore raised the sea floor at Y52 to land at Y84 under a
         * cold_ocean biome and an ocean ruin, seed 7391827364 X-2555 Z-3920). */
        public boolean oceanIsland(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            if (c.isEmpty()) return false;
            for (Object o : Landforms.touching(c, x, z))
                if (o instanceof Landforms.Volcano v && Math.hypot(x - v.x, z - v.z) < v.radius) return true;
            return false;
        }
        /** The ground the landforms make at (x, z) where the cone of a volcano reaches it (before rivers), NaN
         * elsewhere: marine structures keep to the water round it. */
        public double islandGround(int x, int z) {
            if (!oceanIsland(x, z)) return Double.NaN;
            java.util.List<Object> t = Landforms.touching(candidates(x, z), x, z);
            return model.apply(x, z, at(x, z), t, this, this);
        }
        /** Whether (x, z) lies inside a rift or within margin blocks of its walls. */
        public boolean nearRift(int x, int z, double margin) {
            java.util.List<Object> c = candidates(x, z);
            return !c.isEmpty() && Landforms.nearRift(Landforms.touching(c, x, z), x, z, margin);
        }
        /** Whether no native badlands pillar may rise at (x, z) (LandformMaterials.noPillar). */
        public boolean noPillar(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            return !c.isEmpty() && dev.freshgenerator.core.LandformMaterials.noPillar(Landforms.touching(c, x, z), x, z);
        }
        /** Whether no surface structure may stand at (x, z) (LandformMaterials.noBuild). */
        public boolean noBuild(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            return !c.isEmpty() && dev.freshgenerator.core.LandformMaterials.noBuild(Landforms.touching(c, x, z), x, z);
        }
        /** Whether no snow lies at (x, z): the crater and fresh flows of an active volcano (LandformMaterials.hot). */
        public boolean hot(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            return !c.isEmpty() && dev.freshgenerator.core.LandformMaterials.hot(Landforms.touching(c, x, z), x, z);
        }
        /** The point on the axis of a rift that crosses the lattice cell centred on (cx, cz) (within half blocks of the
         * centre on both axes, where the rift is wide and deep enough), or null. The drainage model moves the cell's node
         * there, so that its lattice sees rifts narrower than the lattice spacing. */
        public int[] snapToRift(int cx, int cz, int half) {
            java.util.List<Object> c = candidates(cx, cz);
            if (c.isEmpty()) return null;
            for (Object o : c) {
                if (!(o instanceof Landforms.Rift rf)) continue;
                int[] p = Landforms.riftSnap(rf, cx, cz, half);
                if (p != null) return p;
            }
            return null;
        }
        /** Lava level of the active crater containing (x, z) (on land or a volcanic island), or NaN. */
        public double lavaAt(int x, int z) {
            java.util.List<Object> c = candidates(x, z);
            if (c.isEmpty()) return Double.NaN;
            for (Object o : c) {
                if (!(o instanceof Landforms.Volcano v) || !v.lavaCrater()) continue;
                if (Math.hypot(x - v.x, z - v.z) >= v.craterR) continue;
                Double level = lava.get(v);
                if (level == null) { level = model.lavaLevel(v, this, this); if (lava.size() > 4096) lava.clear(); lava.put(v, level); }
                if (!Double.isNaN(level)) return level;
            }
            return Double.NaN;
        }
        /** Whether the surface of the lava lake at (x, z) is a crust plate (only meaningful where lavaAt is a level). */
        public boolean crustAt(int x, int z) {
            for (Object o : candidates(x, z))
                if (o instanceof Landforms.Volcano v && v.lavaCrater() && Math.hypot(x - v.x, z - v.z) < v.craterR) return v.crust(x, z);
            return false;
        }
        private void trim() {
            // Pure memo: a cleared entry is recomputed to the same value.
            if (sites.size() > 16384) sites.clear();
            if (bases.size() > 16384) bases.clear();
        }
        private static long key(int i, int j, int kind) {
            return dev.freshgenerator.core.GradientNoise.mix(((long) i << 32) ^ (j & 0xFFFFFFFFL) ^ ((long) kind << 61));
        }
    }

    public static final class Sampler implements DensitySampler {
        private final World world;
        private final DensitySampler height;
        Sampler(World world, DensitySampler height) { this.world = world; this.height = height; }
        public World world() { return world; }
        @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
            return (float) world.apply(x, z, height.sampleValue(context, x, y, z));
        }
        @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            for (int a = 0; a < volume.sizeX(); a++) for (int b = 0; b < volume.sizeZ(); b++) {
                float v = sampleValue(context, volume.blockX(a), volume.blockY(0), volume.blockZ(b));
                for (int y = 0; y < volume.sizeY(); y++) output.set(volume.indexUnchecked(a, y, b), v);
            }
        }
    }

    private static final Map<RandomState, World> BOUND = new WeakHashMap<>();

    /** The landform model of a world (null for worlds without it). */
    public static World world(RandomState state, NoiseGeneratorSettings settings) {
        synchronized (BOUND) { if (BOUND.containsKey(state)) return BOUND.get(state); }
        World found = null;
        for (var debug : settings.debugFunctions().functions()) {
            if (!debug.label().equals(LABEL)) continue;
            found = worldOf(state.getSampler(debug.function()));
            if (found != null) break;
        }
        synchronized (BOUND) { BOUND.put(state, found); }
        return found;
    }

    /** The landform model already bound to a world state (null when none was looked up yet or there is none). */
    public static World boundWorld(RandomState state) {
        synchronized (BOUND) { return BOUND.get(state); }
    }

    /** The landform model behind a compiled sampler, through the cache and slice wrappers the compiler puts round it
     * (the surface rule saw YSampler -> CachingDensitySampler -> YSampler -> Sampler and gave up at the first YSampler:
     * no volcanic rock, no crater breccia anywhere - OpenAI 26.11 code finding 1). Null when there is none. */
    public static World worldOf(DensitySampler sampler) {
        for (int guard = 0; guard < 16 && sampler != null; guard++) {
            switch (sampler) {
                case Sampler s -> { return s.world(); }
                case CachingDensitySampler cache -> sampler = cache.input();
                case SliceFunction.YSampler horizontal -> sampler = horizontal.input();
                default -> { return null; }
            }
        }
        return null;
    }
}
