package dev.freshgenerator.forest;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Per-world memo of two pure functions of the position read by the forest adapter: the noise biome of a quart cell
 * and the ground height of a column. It only memoizes: a value is what the world's own source returns for that key,
 * the same whichever placer or chunk asks first, so generation order cannot change a tree (the adapter itself keeps
 * no global state). Every candidate's site reads biomes and heights 16-32 blocks around it; neighbouring candidates
 * and chunks asked for the same cells again - the forest was ~22% of the serial decoration queue (Chunk Atlas
 * profile, 23.11). One memo per RandomState (world and data load), bounded: cleared past LIMIT entries. */
final class ForestWorldMemo {
    private static final Map<RandomState, ForestWorldMemo> WORLDS = new WeakHashMap<>();
    /** Entries per table before it is cleared: the working set is the few chunks being decorated, a million per table
     * held 100+ MB (code audit 24.11 K-9). */
    static final int LIMIT = 1 << 18;
    private final ConcurrentHashMap<Long, Holder<Biome>> biomes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Float> heights = new ConcurrentHashMap<>();

    static ForestWorldMemo of(RandomState state) {
        synchronized (WORLDS) { return WORLDS.computeIfAbsent(state, s -> new ForestWorldMemo()); }
    }

    /** Noise biome of a quart cell from the world's source, memoized. */
    Holder<Biome> biome(int qx, int qy, int qz, BiomeResolver source) {
        long key = quartKey(qx, qy, qz);
        Holder<Biome> b = biomes.get(key);
        if (b != null) return b;
        b = source.getNoiseBiome(qx, qy, qz);
        if (biomes.size() > LIMIT) biomes.clear();
        biomes.put(key, b);
        return b;
    }
    /** Memoized ground height of a packed column (x << 32 | z), NaN when unknown. */
    float height(long packed) { Float h = heights.get(mix(packed)); return h == null ? Float.NaN : h; }
    void height(long packed, float value) {
        if (heights.size() > LIMIT) heights.clear();
        heights.put(mix(packed), value);
    }

    /** Distinct for every quart of the world (24-bit x and z: +-33 million blocks; 10-bit y), mixed for the map. */
    static long quartKey(int qx, int qy, int qz) {
        return mix(((long) (qx & 0xFFFFFF) << 34) | ((long) (qz & 0xFFFFFF) << 10) | (qy & 0x3FF));
    }
    /** Bijective mix: Long.hashCode of a packed pair collides along diagonals. */
    private static long mix(long k) { k *= 0x9E3779B97F4A7C15L; return k ^ (k >>> 29); }
}
