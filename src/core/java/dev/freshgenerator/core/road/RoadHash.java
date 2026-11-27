package dev.freshgenerator.core.road;

/** Seeded hashes of the road layer: every choice is a pure function of the world seed and the place, never of the
 * order in which chunks are generated. */
public final class RoadHash {
    private RoadHash() { }

    public static long mix(long h) {
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
    public static long of(long seed, long a) { return mix(seed * 0x9E3779B97F4A7C15L + mix(a)); }
    public static long of(long seed, long a, long b) { return mix(of(seed, a) ^ mix(b * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L)); }
    public static long of(long seed, long a, long b, long c) { return mix(of(seed, a, b) + c * 0xD6E8FEB86659FD93L); }
    /** Uniform value in [0, 1). */
    public static double unit(long h) { return (mix(h) >>> 11) * 0x1.0p-53; }
    public static double unit(long seed, long a, long b) { return unit(of(seed, a, b)); }
    public static double unit(long seed, long a, long b, long c) { return unit(of(seed, a, b, c)); }
    /** Smooth seeded 1-D value noise in [-1, 1]. */
    public static double noise(long seed, double t) {
        long i = (long) Math.floor(t);
        double f = t - i, s = f * f * (3 - 2 * f);
        double a = unit(seed, i, 0) * 2 - 1, b = unit(seed, i + 1, 0) * 2 - 1;
        return a + (b - a) * s;
    }
}
