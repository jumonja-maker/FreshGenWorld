package dev.freshgenerator.core;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Island climate (user request 23.11): a small island takes the dominant climate of its land as a whole instead of
 * being cut in two by a climate border running across it - a mostly wintry island is wintry from shore to shore.
 * <p>Land is found on a 32-block cell grid from a climate-independent ground height (the native height, so there
 * is no feedback through climate-driven relief). Connectivity runs over the <i>core</i> of the land (land cells whose
 * four neighbours are land too): a beach or an isthmus narrower than about two cells does not join an island to the
 * mainland. A core component of at most MAX_CELLS cells (about 4 km²) is an island; the coastal fringe (land that is
 * not core) belongs to the island of an adjacent core cell. Every island cell gets the offset that brings the local
 * zone temperature and moisture to the island's median over its core (fully up to FULL_CELLS, fading out toward
 * MAX_CELLS, so island size has no threshold). Offsets are 0 at sea, on islets without a core and on large land; the
 * field interpolates them bilinearly between cell centres, so the coast has no step and local detail is kept.
 * <p>Pure function of the inputs: the island median is taken over sorted values, so every cell of an island sees the
 * same value whatever cell or thread found it first; caches only memoize. */
public final class IslandClimate {
    public interface Inputs {
        double height(int x, int z);
        double temperature(int x, int z);
        double moisture(int x, int z);
    }
    public static final int CELL = 32, MAX_CELLS = 4096, FULL_CELLS = 1536;
    public static final double SEA = 62.5;
    /** Entries of all three memo tables together before they are cleared (about 70 MB). The limit counted only the
     * cell offsets, while the land flags and island records grew several hundred times faster under scattered queries
     * (code audit 24.11: 64 cells against 102 000 other entries on three seeds). Clearing all three is consistent: every
     * value is recomputed identically (an island marked mainland is larger than MAX_CELLS from any of its cells). */
    static final int CACHE_LIMIT = 1 << 20;
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    private record Offset(float temperature, float moisture) { }
    private static final Offset ZERO = new Offset(0, 0);
    /** Island of a core component: median climate over its core and the weight of the island climate. */
    private record Island(double temperature, double moisture, double weight) { }
    private static final Island MAINLAND = new Island(0, 0, 0);

    private final Inputs inputs;
    private final ConcurrentHashMap<Long, Offset> cells = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Island> islands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> landCells = new ConcurrentHashMap<>();

    public IslandClimate(Inputs inputs) { this.inputs = inputs; }

    /** Island temperature and moisture of a column (the density function adds offset() to its own sample of the
     * local field instead: same value, through the caches of the caller). */
    public double temperature(int x, int z) { return apply(inputs.temperature(x, z), offset(x, z, true), -1, 1); }
    public double moisture(int x, int z) { return apply(inputs.moisture(x, z), offset(x, z, false), -1, 1); }
    /** Local value with the island offset, kept inside the bounds of the local field (unchanged without offset). */
    public static double apply(double local, double offset, double min, double max) {
        if (offset == 0) return local;
        double v = local + offset;
        return v < min ? min : v > max ? max : v;
    }

    /** Bilinear offset between the four surrounding cell centres (0 on large land and at sea). */
    public double offset(int x, int z, boolean temperature) {
        double fx = (x - CELL / 2.0) / CELL, fz = (z - CELL / 2.0) / CELL;
        int cx = (int) Math.floor(fx), cz = (int) Math.floor(fz);
        NodeTable table = temperature ? fastT : fastM;
        float a = fast(table, cx, cz, temperature), b = fast(table, cx + 1, cz, temperature);
        float c = fast(table, cx, cz + 1, temperature), d = fast(table, cx + 1, cz + 1, temperature);
        if (a == 0 && b == 0 && c == 0 && d == 0) return 0;
        double tx = fx - cx, tz = fz - cz;
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }
    /** Cell offsets through lock-free direct-mapped tables in front of the cell memo: the climate is read by every
     * height sample of a lake fill, a ThreadLocal or a map lookup per sample cost generation several percent. */
    private final NodeTable fastT = new NodeTable(), fastM = new NodeTable();
    private float fast(NodeTable table, int cx, int cz, boolean temperature) {
        float v = table.get(cx, cz);
        if (v == v) return v;
        Offset o = cell(cx, cz);
        fastT.put(cx, cz, o.temperature); fastM.put(cx, cz, o.moisture);
        return temperature ? o.temperature : o.moisture;
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }
    private static int cx(long key) { return (int) (key >> 32); }
    private static int cz(long key) { return (int) key; }
    /** Map key: the cell key mixed bijectively. Long.hashCode of a packed (cx, cz) is cx ^ cz - every diagonal of
     * cells collides into one bin (tree bins, slow lookups on every climate sample). */
    private static long hashed(long key) { key *= 0x9E3779B97F4A7C15L; return key ^ (key >>> 29); }

    private static int centre(int c) { return c * CELL + CELL / 2; }

    Offset cell(int cx, int cz) {
        long h = hashed(key(cx, cz));
        Offset o = cells.get(h);
        if (o == null) {
            if ((long) cells.size() + islands.size() + landCells.size() > CACHE_LIMIT) { cells.clear(); islands.clear(); landCells.clear(); }
            o = compute(cx, cz);
            Offset prior = cells.putIfAbsent(h, o);
            if (prior != null) o = prior;
        }
        return o;
    }

    private boolean land(int cx, int cz) {
        long k = hashed(key(cx, cz));
        Boolean b = landCells.get(k);
        if (b != null) return b;
        b = inputs.height(centre(cx), centre(cz)) >= SEA;
        landCells.putIfAbsent(k, b);
        return b;
    }
    private boolean core(int cx, int cz) {
        if (!land(cx, cz)) return false;
        for (int d = 0; d < 4; d++) if (!land(cx + DX[d], cz + DZ[d])) return false;
        return true;
    }

    private Offset compute(int cx, int cz) {
        if (!land(cx, cz)) return ZERO;
        Island island = null;
        if (core(cx, cz)) island = island(cx, cz);
        else {
            // Coastal fringe: the island of an adjacent core cell (the lowest key, deterministic).
            long best = Long.MAX_VALUE;
            for (int d = 0; d < 4; d++) { int nx = cx + DX[d], nz = cz + DZ[d]; if (core(nx, nz)) best = Math.min(best, key(nx, nz)); }
            if (best != Long.MAX_VALUE) island = island(cx(best), cz(best));
        }
        if (island == null || island.weight == 0) return ZERO;
        int x = centre(cx), z = centre(cz);
        return new Offset((float) (island.weight * (island.temperature - inputs.temperature(x, z))),
                (float) (island.weight * (island.moisture - inputs.moisture(x, z))));
    }

    /** Island of a core cell (memoized for every core cell of the component). */
    private Island island(int cx, int cz) {
        long start = key(cx, cz);
        Island known = islands.get(hashed(start));
        if (known != null) return known;
        HashSet<Long> seen = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        seen.add(start); queue.add(start);
        while (!queue.isEmpty()) {
            long c = queue.poll();
            for (int d = 0; d < 4; d++) {
                int nx = cx(c) + DX[d], nz = cz(c) + DZ[d];
                long n = key(nx, nz);
                if (seen.contains(n) || !core(nx, nz)) continue;
                if (islands.get(hashed(n)) == MAINLAND) { for (long s : seen) islands.putIfAbsent(hashed(s), MAINLAND); return MAINLAND; }
                seen.add(n); queue.add(n);
                if (seen.size() > MAX_CELLS) { for (long s : seen) islands.putIfAbsent(hashed(s), MAINLAND); return MAINLAND; }
            }
        }
        // Dominant climate over the core: the median of every axis, so the climate of more than half of the island
        // wins at any biome threshold (the mean of a mostly wintry island could land on the warm side of the frost
        // line: 77 of 121 cells below -0.45, mean -0.437 - audit 23.11). Order-independent: values are sorted.
        TreeSet<Long> sorted = new TreeSet<>(seen);
        double[] ts = new double[sorted.size()], ms = new double[sorted.size()];
        int n = 0;
        for (long c : sorted) { int x = centre(cx(c)), z = centre(cz(c)); ts[n] = inputs.temperature(x, z); ms[n] = inputs.moisture(x, z); n++; }
        Island island = new Island(median(ts), median(ms), 1 - smooth(FULL_CELLS, MAX_CELLS, sorted.size()));
        for (long c : sorted) islands.putIfAbsent(hashed(c), island);
        // The local result, never a re-read: a cache clear by another thread between the stores and a re-read gave
        // null, cached as "no island" for good (audit 23.11).
        Island known2 = islands.get(hashed(start));
        return known2 != null ? known2 : island;
    }

    /** Debug: core cells of the island a block position belongs to, 0 at sea or on an islet without core, -1 on large
     * land. */
    public int islandCells(int x, int z) {
        int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
        if (!land(cx, cz)) return 0;
        long k = key(cx, cz);
        if (!core(cx, cz)) {
            long best = Long.MAX_VALUE;
            for (int d = 0; d < 4; d++) { int nx = cx + DX[d], nz = cz + DZ[d]; if (core(nx, nz)) best = Math.min(best, key(nx, nz)); }
            if (best == Long.MAX_VALUE) return 0;
            k = best;
        }
        if (island(cx(k), cz(k)) == MAINLAND) return -1;
        HashSet<Long> seen = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        seen.add(k); queue.add(k);
        while (!queue.isEmpty()) {
            long c = queue.poll();
            for (int d = 0; d < 4; d++) {
                int nx = cx(c) + DX[d], nz = cz(c) + DZ[d];
                long n = key(nx, nz);
                if (!seen.contains(n) && core(nx, nz)) { seen.add(n); queue.add(n); }
            }
        }
        return seen.size();
    }

    static double median(double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    private static double smooth(double a, double b, double v) {
        double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
