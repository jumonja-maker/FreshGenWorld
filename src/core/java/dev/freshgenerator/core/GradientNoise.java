package dev.freshgenerator.core;

/** Seeded 2-D gradient (Perlin) noise for the landform models of the core: deterministic, thread-safe, no game
 * classes. Values are about -0.7..0.7 (rarely beyond), zero at lattice points, smooth (C2 fade). */
public final class GradientNoise {
    private final int[] perm = new int[512];
    private final double offsetX, offsetZ;

    public GradientNoise(long seed) {
        long s = seed;
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            s = mix(s + i);
            int j = (int) Long.remainderUnsigned(s, i + 1);
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        s = mix(s ^ 0x5DEECE66DL);
        offsetX = (s >>> 11) * 0x1.0p-53 * 256;
        s = mix(s);
        offsetZ = (s >>> 11) * 0x1.0p-53 * 256;
    }

    /** Noise at (x, z) in lattice units (one cell per unit). */
    public double value(double x, double z) {
        x += offsetX; z += offsetZ;
        double fx = Math.floor(x), fz = Math.floor(z);
        int ix = (int) fx & 255, iz = (int) fz & 255;
        double dx = x - fx, dz = z - fz;
        double u = fade(dx), v = fade(dz);
        int a = perm[ix] + iz, b = perm[ix + 1] + iz;
        double n00 = grad(perm[a], dx, dz), n10 = grad(perm[b], dx - 1, dz);
        double n01 = grad(perm[a + 1], dx, dz - 1), n11 = grad(perm[b + 1], dx - 1, dz - 1);
        double x0 = n00 + u * (n10 - n00), x1 = n01 + u * (n11 - n01);
        return x0 + v * (x1 - x0);
    }

    /** Sum of octaves: first period {@code period} blocks, each next octave half the period and half the amplitude. */
    public double fractal(double x, double z, double period, int octaves) {
        double sum = 0, amp = 1, f = 1 / period, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * value(x * f + o * 31.7, z * f - o * 17.3);
            norm += amp; amp *= 0.5; f *= 2;
        }
        return sum / norm;
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double grad(int h, double x, double z) {
        switch (h & 7) {
            case 0: return x + z;
            case 1: return x - z;
            case 2: return -x + z;
            case 3: return -x - z;
            case 4: return x * 1.41421356;
            case 5: return -x * 1.41421356;
            case 6: return z * 1.41421356;
            default: return -z * 1.41421356;
        }
    }
    /** splitmix64 finaliser. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    /** 0..1 from a seed and salts. */
    public static double unit(long seed, long a, long b) {
        return (mix(seed ^ mix(a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL)) >>> 11) * 0x1.0p-53;
    }
}
