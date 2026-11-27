package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;

/** River biomes from real channels: rivers of 5+ blocks get river, or frozen_river where the surrounding biome is
 * cold at the water's height, in the quart cells around their water surface. Lakes keep the surrounding biome (they
 * freeze or not with it); small streams belong to the land they cross. Code audit 25.11 Fable K-08: at 7+ blocks the
 * river biome covered 3% of the wet channels (4.85 km of 173.6 km on 144 km2, 0.26 km in the cold), and no search found
 * a frozen river - a biome of the Adventuring Time advancement. */
public final class HydroBiomes {
    public static final double RIVER_WIDTH = 5;
    /** Incision (blocks) from which a valley floor reads the biome of the original ground height. */
    public static final double INCISION = 8;
    private HydroBiomes() { }

    /** Whether a biome predicate accepts river or frozen_river (a search for them needs the drainage overlay). */
    public static boolean wantsRiver(BiomeSource source, java.util.function.Predicate<Holder<Biome>> wanted) {
        for (Holder<Biome> h : source.possibleBiomes()) {
            String id = h.unwrapKey().map(k -> k.identifier().toString()).orElse("");
            if ((id.equals("minecraft:river") || id.equals("minecraft:frozen_river")) && wanted.test(h)) return true;
        }
        return false;
    }

    /** The resolver a chunk fills its biomes from. maxQy is the chunk's top quart cell: the native resolver of a chunk
     * reads a climate buffer of the chunk's own height without a bounds check (26.3 DensityVolume.indexUnchecked), so
     * a valley floor's biome read from above the world top took the bottom of the neighbouring buffer column (deep_dark
     * at Y251-270 in the gorges of icy peaks) or, in the chunk's last column, ran past the buffer: the biome step failed
     * and the area never generated (audits 25.11 Opus N-1/KO-01, OpenAI: seed 161803398 X-3605 Z-4377). */
    public static BiomeResolver wrap(BiomeResolver original, HydroField field, BiomeSource source, int maxQy) {
        return wrap(original, field, source, maxQy, null);
    }
    /** The same with the land biomes of volcanic islands (null: none). */
    public static BiomeResolver wrap(BiomeResolver original, HydroField field, BiomeSource source, int maxQy,
                                     dev.freshgenerator.landform.IslandBiomes islands) {
        Holder<Biome>[] rivers = rivers(source);
        if (rivers == null) return original;
        HydroField.Column c = new HydroField.Column();
        return (qx, qy, qz) -> stored(original, field, c, qx, qy, qz, rivers, maxQy, islands);
    }

    /** The level's fallback resolver for unloaded chunks: the river and valley biomes the chunk will store where the
     * drainage model of the column is already built, the source biome elsewhere - a lookup never builds the model (an
     * explorer map makes 16 384 lookups over unexplored land). Audits 25.11 K4, OpenAI code 1, Opus KO-06: a river
     * column read as beach before its chunk loaded (seed 42 X2970 Z2486). */
    public static BiomeResolver wrapReady(BiomeResolver original, HydroField field, BiomeSource source, int maxQy) {
        return wrapReady(original, field, rivers(source), maxQy);
    }
    static BiomeResolver wrapReady(BiomeResolver original, HydroField field, Holder<Biome>[] rivers, int maxQy) {
        return wrapReady(original, field, rivers, maxQy, null);
    }
    static BiomeResolver wrapReady(BiomeResolver original, HydroField field, Holder<Biome>[] rivers, int maxQy,
                                   dev.freshgenerator.landform.IslandBiomes islands) {
        if (rivers == null) return original;
        HydroField.Column c = new HydroField.Column();
        return (qx, qy, qz) -> field.ready((qx << 2) + 2, (qz << 2) + 2)
                ? stored(original, field, c, qx, qy, qz, rivers, maxQy, islands) : original.getNoiseBiome(qx, qy, qz);
    }

    /** {river, frozen_river (or river)} of a source, or null when it has no river. */
    @SuppressWarnings("unchecked")
    static Holder<Biome>[] rivers(BiomeSource source) {
        Holder<Biome> river = null, frozen = null;
        for (Holder<Biome> h : source.possibleBiomes()) {
            String id = h.unwrapKey().map(k -> k.identifier().toString()).orElse("");
            if (id.equals("minecraft:river")) river = h;
            else if (id.equals("minecraft:frozen_river")) frozen = h;
        }
        return river == null ? null : new Holder[]{river, frozen != null ? frozen : river};
    }

    /** The biome a chunk stores at a quart cell: the source biome (shifted by the incision of deep valleys) or the
     * river biome over the water of a wide river. */
    private static Holder<Biome> stored(BiomeResolver original, HydroField field, HydroField.Column c, int qx, int qy, int qz, Holder<Biome>[] rivers, int maxQy,
                                        dev.freshgenerator.landform.IslandBiomes islands) {
        field.column((qx << 2) + 2, (qz << 2) + 2, c);
        return fromColumn(original, c, qx, qy, qz, rivers, maxQy, islands);
    }

    /** The stored biome of a quart cell for a known drainage column (package-private for the tests). */
    static Holder<Biome> fromColumn(BiomeResolver original, HydroField.Column c, int qx, int qy, int qz, Holder<Biome>[] rivers, int maxQy) {
        return fromColumn(original, c, qx, qy, qz, rivers, maxQy, null);
    }
    static Holder<Biome> fromColumn(BiomeResolver original, HydroField.Column c, int qx, int qy, int qz, Holder<Biome>[] rivers, int maxQy,
                                    dev.freshgenerator.landform.IslandBiomes islands) {
        // Biome depth and the deep climate count from the ground before the rivers: the floor and walls of a deep
        // valley would read as underground (another climate, cave biomes). They take the biome of the height
        // the original ground had there, shifted by the incision (audit 22.11 B-3), never above the top cell of the
        // world (everything shifted past it is open air over the original ground: the top cell reads the same).
        double cut = c.base - c.terrain;
        int y = (qy << 2) + 2;
        Holder<Biome> base = cut >= INCISION && y >= c.terrain - 24 && y <= c.base
                ? original.getNoiseBiome(qx, Math.min(qy + (int) Math.round(cut / 4), Math.max(qy, maxQy)), qz) : original.getNoiseBiome(qx, qy, qz);
        // Island ground keeps no ocean biome (IslandBiomes), in the whole column: the cells under the island kept
        // cold_ocean in its rock and /locate cold_ocean pointed into the island at Y48 (seed 7391827364 X-2781 Z-5304).
        // So does the floor of a bowl closed above the sea (seaFree: a dry caldera at Y58 kept the ocean biome, audit 27.11
        // Opus N-2).
        if (islands != null && (c.base >= HydroModel.SEA_LEVEL || c.seaFree)) base = islands.on(base, qx, qy, qz, c.base, c.seaFree);
        if ((c.kind != HydroField.RIVER && c.kind != HydroField.POOL) || !c.wet() || c.width < RIVER_WIDTH) return base;
        // The river ends at the coast: where the ground before the river lies below the sea, the last reach runs on
        // to its lattice node in the sea - a straight strip of river biome over the sea floor (seed 42 X2900
        // Z2482, audit 23.11).
        if (c.base < HydroModel.SEA_LEVEL) return base;
        if (y < c.water - 16 || y > c.water + 8) return base;
        return riverFor(base, rivers, (qx << 2) + 2, c.water, (qz << 2) + 2);
    }
    /** River or frozen river by the surrounding biome's own temperature at the water surface - the height-adjusted
     * rule of snow and ice, as for lakes (HydroIce): a river at Y160 in windswept hills or above Y208 in taiga stayed a
     * river among frozen water and snow (code audit 25.11 K6). */
    private static Holder<Biome> riverFor(Holder<Biome> base, Holder<Biome>[] rivers, int x, int water, int z) {
        return base.value().warmEnoughToRain(new BlockPos(x, water, z), (int) HydroModel.SEA_LEVEL) ? rivers[0] : rivers[1];
    }

    /** One biome search (/locate biome) in the current world: a resolver for BiomeSource.findClosestBiome3d and the
     * correction of its result (BiomeSourceRiverMixin). Audit 24.11 T-3 and code audit 24.11 K-2, K-3, G1, G3:
     * <ul><li>a river search counts a wide river (7+ blocks) at the probe column or within NEAR blocks of it at any
     * height - the stored river biome is a band around the water (-16..+8) and the probes stand 64 blocks apart in
     * height, so from Y72-110 the search saw no river at all (a frozen river 1901 blocks away was found from Y70 and
     * Y133, not from Y100);</li>
     * <li>a found river is reported at the river column itself, at its water level, not at the dry probe up to 24 blocks
     * away;</li>
     * <li>the drainage model answers within RADIUS blocks of the search origin, beyond it the source alone: a fixed area
     * instead of a 15 s clock, so the answer no longer depends on the machine (a failed river search once held the
     * server thread for 55 s);</li>
     * <li>any other search checks the stored biome only where the source matches (snowy_taiga at distance 0 on a frozen
     * river), within the same radius.</li></ul>
     * The model of the rings ahead is computed on a shared pool of low-priority threads while the search runs. */
    public static final class Search implements BiomeResolver {
        private final BiomeResolver original;
        private final HydroField field;
        private final Holder<Biome>[] rivers;
        private final java.util.function.Predicate<Holder<Biome>> wanted;
        private final boolean riverSearch, onlyRivers;
        private final Holder<Biome> unwanted;
        private final int ox, oz;
        private final HydroField.Column c = new HydroField.Column();
        private final java.util.HashMap<Long, int[]> hits = new java.util.HashMap<>();
        /** Land biomes of volcanic islands, as the chunks store them (null: none). */
        private dev.freshgenerator.landform.IslandBiomes islands;
        private volatile boolean done;
        private boolean prefetching;

        Search(BiomeResolver original, HydroField field, BiomeSource source, java.util.function.Predicate<Holder<Biome>> wanted, Holder<Biome>[] rivers, int ox, int oz) {
            this.original = original; this.field = field; this.rivers = rivers; this.wanted = wanted; this.ox = ox; this.oz = oz;
            riverSearch = wantsRiver(source, wanted);
            unwanted = source.possibleBiomes().stream().filter(h -> !wanted.test(h)).findFirst().orElse(null);
            onlyRivers = riverSearch && unwanted != null && source.possibleBiomes().stream().filter(wanted)
                    .allMatch(h -> h.equals(rivers[0]) || h.equals(rivers[1]));
        }

        @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
            int bx = (qx << 2) + 2, bz = (qz << 2) + 2;
            boolean near = (long) (bx - ox) * (bx - ox) + (long) (bz - oz) * (bz - oz) <= (long) RADIUS * RADIUS;
            if (!riverSearch) {
                Holder<Biome> base = original.getNoiseBiome(qx, qy, qz);
                // An ocean biome of the source over a volcano may be island land in the chunks: the stored biome answers
                // (code audits 27.11 Opus KO-06, OpenAI K-02: /locate found frozen_ocean where the chunks hold snowy_taiga).
                boolean island = islands != null && near && base.is(net.minecraft.tags.BiomeTags.IS_OCEAN) && islands.mayBeIsland(bx, bz);
                return near && (wanted.test(base) || island) ? stored(original, field, c, qx, qy, qz, rivers, Integer.MAX_VALUE, islands) : base;
            }
            if (!near) return onlyRivers ? unwanted : original.getNoiseBiome(qx, qy, qz);
            if (!prefetching) { prefetching = true; prefetch(field, ox, oz, this); }
            field.column(bx, bz, c);
            int[] river = null;
            if ((c.kind == HydroField.RIVER || c.kind == HydroField.POOL) && c.wet() && c.width >= RIVER_WIDTH && c.base >= HydroModel.SEA_LEVEL)
                river = new int[]{bx, c.water, bz};
            else {
                double[] n = field.wideRiverNear(bx, bz, NEAR, RIVER_WIDTH);
                if (n != null) river = new int[]{(int) n[0], (int) n[2], (int) n[1]};
            }
            if (river != null) {
                Holder<Biome> r = riverFor(original.getNoiseBiome(QuartPos.fromBlock(river[0]), QuartPos.fromBlock(river[1]), QuartPos.fromBlock(river[2])),
                        rivers, river[0], river[1], river[2]);
                if (wanted.test(r)) { hits.put(key(qx, qz), river); return r; }
            }
            return onlyRivers ? unwanted : stored(original, field, c, qx, qy, qz, rivers, Integer.MAX_VALUE, null);
        }

        /** The search result with a river hit moved onto the river itself; ends the prefetch of this search. */
        public com.mojang.datafixers.util.Pair<BlockPos, Holder<Biome>> finish(com.mojang.datafixers.util.Pair<BlockPos, Holder<Biome>> result) {
            done = true;
            if (result == null) return null;
            BlockPos at = result.getFirst();
            int[] hit = hits.get(key(QuartPos.fromBlock(at.getX()), QuartPos.fromBlock(at.getZ())));
            return hit == null ? result : com.mojang.datafixers.util.Pair.of(new BlockPos(hit[0], hit[1], hit[2]), result.getSecond());
        }
        private static long key(int qx, int qz) { return ((long) qx << 32) | (qz & 0xffffffffL); }
    }

    /** The search resolver of a findClosestBiome3d call from origin (the source resolver when the source has no river). */
    public static BiomeResolver search(BiomeResolver original, HydroField field, BiomeSource source, java.util.function.Predicate<Holder<Biome>> wanted, BlockPos origin) {
        return search(original, field, source, wanted, origin, null);
    }
    /** The same with the land biomes of volcanic islands (null: none). */
    public static BiomeResolver search(BiomeResolver original, HydroField field, BiomeSource source, java.util.function.Predicate<Holder<Biome>> wanted, BlockPos origin,
                                       dev.freshgenerator.landform.IslandBiomes islands) {
        Holder<Biome>[] rivers = rivers(source);
        if (rivers == null) return original;
        Search s = new Search(original, field, source, wanted, rivers, origin.getX(), origin.getZ());
        s.islands = islands;
        return s;
    }

    /** Builds the query tiles of the drainage model ring by ring around the search origin, up to RADIUS, on a shared
     * pool of low-priority daemon threads (all cores but two), until the search ends. Failures are logged. */
    private static void prefetch(HydroField field, int x, int z, Search search) {
        int q = HydroField.QUERY, r = RADIUS / q + 1, cx = Math.floorDiv(x, q), cz = Math.floorDiv(z, q);
        java.util.ArrayList<int[]> tiles = new java.util.ArrayList<>();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) if (dx * dx + dz * dz <= r * r) tiles.add(new int[]{cx + dx, cz + dz});
        tiles.sort(java.util.Comparator.comparingInt(t -> (t[0] - cx) * (t[0] - cx) + (t[1] - cz) * (t[1] - cz)));
        for (int[] tile : tiles) PREFETCH.execute(() -> {
            if (search.done) return;
            try { field.column(tile[0] * q + q / 2, tile[1] * q + q / 2, new HydroField.Column()); }
            catch (RuntimeException e) { LOG.warn("FreshGenerator river search: model prefetch failed at tile {},{}", tile[0], tile[1], e); }
        });
    }
    private static final java.util.concurrent.ExecutorService PREFETCH = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 2), runnable -> {
                Thread t = new Thread(runnable, "FreshGenerator river search");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("FreshGenerator");
    /** Radius (blocks) around the search origin in which the drainage model answers; probe radius of a river search
     * (half the probe diagonal of 32 blocks). */
    static final int RADIUS = 2048;
    static final double NEAR = 24;
}
