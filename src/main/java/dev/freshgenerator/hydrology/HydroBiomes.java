package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;

/** River biomes from real channels: wide rivers (7+ blocks) get river, or frozen_river where the surrounding
 * biome is cold, in the quart cells around their water surface. Lakes keep the surrounding biome (they freeze
 * or not with it); small streams belong to the land they cross. */
public final class HydroBiomes {
    public static final double RIVER_WIDTH = 7;
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

    public static BiomeResolver wrap(BiomeResolver original, HydroField field, BiomeSource source) {
        Holder<Biome>[] rivers = rivers(source);
        if (rivers == null) return original;
        HydroField.Column c = new HydroField.Column();
        return (qx, qy, qz) -> stored(original, field, c, qx, qy, qz, rivers);
    }

    /** {river, frozen_river (or river)} of a source, or null when it has no river. */
    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] rivers(BiomeSource source) {
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
    private static Holder<Biome> stored(BiomeResolver original, HydroField field, HydroField.Column c, int qx, int qy, int qz, Holder<Biome>[] rivers) {
        field.column((qx << 2) + 2, (qz << 2) + 2, c);
        // Biome depth and the deep climate count from the ground before the rivers: the floor and walls of a deep
        // valley would read as underground (another climate, cave biomes). They take the biome of the height
        // the original ground had there, shifted by the incision (audit 22.11 B-3).
        double cut = c.base - c.terrain;
        int y = (qy << 2) + 2;
        Holder<Biome> base = cut >= INCISION && y >= c.terrain - 24 && y <= c.base
                ? original.getNoiseBiome(qx, qy + (int) Math.round(cut / 4), qz) : original.getNoiseBiome(qx, qy, qz);
        if ((c.kind != HydroField.RIVER && c.kind != HydroField.POOL) || !c.wet() || c.width < RIVER_WIDTH) return base;
        // The river ends at the coast: where the ground before the river lies below the sea, the last reach runs on
        // to its lattice node in the sea - a straight strip of river biome over the sea floor (seed 42 X2900
        // Z2482, audit 23.11).
        if (c.base < HydroModel.SEA_LEVEL) return base;
        if (y < c.water - 16 || y > c.water + 8) return base;
        return riverFor(base, rivers);
    }
    private static Holder<Biome> riverFor(Holder<Biome> base, Holder<Biome>[] rivers) {
        return base.value().getBaseTemperature() < 0.15f ? rivers[1] : rivers[0];
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
                return near && wanted.test(base) ? stored(original, field, c, qx, qy, qz, rivers) : base;
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
                Holder<Biome> r = riverFor(original.getNoiseBiome(QuartPos.fromBlock(river[0]), QuartPos.fromBlock(river[1]), QuartPos.fromBlock(river[2])), rivers);
                if (wanted.test(r)) { hits.put(key(qx, qz), river); return r; }
            }
            return onlyRivers ? unwanted : stored(original, field, c, qx, qy, qz, rivers);
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
        Holder<Biome>[] rivers = rivers(source);
        if (rivers == null) return original;
        return new Search(original, field, source, wanted, rivers, origin.getX(), origin.getZ());
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
