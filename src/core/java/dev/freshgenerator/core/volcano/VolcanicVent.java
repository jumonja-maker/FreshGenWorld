package dev.freshgenerator.core.volcano;

/** A small volcanic vent instead of the native surface lava lakes (user request 26.11), a pure function of a seed.
 * <ul>
 * <li>CONE (deserts): a slightly elongated cinder or spatter cone 3-6 blocks high, its crater holding lava a block below
 *     the rim - like the cones of the desert lava fields (Amboy Crater in the Mojave, Harrat Khaybar in Arabia, Pinacate
 *     in Sonora), where no forest or erosion hides them.</li>
 * <li>PIT (badlands): the same vent the other way round - a funnel sunk 5-7 blocks below the ground with a lava pool at
 *     its bottom and a low raised ring around it, like a small maar.</li>
 * </ul>
 * The shape is a grid of columns around the centre: the top of the solid ground relative to the ground at the centre,
 * and where lava stands, its top. Every lava column is closed on its four sides by lava or by ground reaching the lava top
 * (no lava runs out). */
public final class VolcanicVent {
    public enum Kind { CONE, PIT }
    /** Column codes of the material layer. */
    public static final int NONE = 0, SHELL = 1, CRATER = 2, SCATTER = 3, RING = 4;
    public static final int NO_LAVA = Integer.MIN_VALUE;

    public final Kind kind;
    /** Grid half size (blocks): the grid spans -r..r on both axes. */
    public final int r;
    /** Height above the ground (cone) or depth below it (pit). */
    public final int size;
    private final int[] top, lava, code;
    private final double[] q;

    public VolcanicVent(Kind kind, long seed) {
        this.kind = kind;
        double angle = unit(seed, 1) * Math.PI;
        double minor = kind == Kind.CONE ? 6 + 2 * unit(seed, 2) : 5 + 2 * unit(seed, 2);
        double major = minor * (1.15 + 0.3 * unit(seed, 3));
        size = kind == Kind.CONE ? 3 + (int) Math.floor(unit(seed, 4) * 4) : 5 + (int) Math.floor(unit(seed, 4) * 3);
        // Scoria strewn round a cone to 1.7 of its radius (the grid then stays within a chunk around the centre chunk).
        double scatter = kind == Kind.CONE ? 1.7 : 1.25;
        r = (int) Math.ceil(major * scatter) + 1;
        int n = 2 * r + 1;
        top = new int[n * n]; lava = new int[n * n]; code = new int[n * n]; q = new double[n * n];
        double ca = Math.cos(angle), sa = Math.sin(angle);
        double crater = 0.24 + 0.06 * unit(seed, 5), pool = 0.34 + 0.06 * unit(seed, 5);
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            int i = index(dx, dz);
            double u = dx * ca + dz * sa, v = -dx * sa + dz * ca;
            double d = Math.sqrt((u / major) * (u / major) + (v / minor) * (v / minor));
            q[i] = d;
            lava[i] = NO_LAVA;
            if (kind == Kind.CONE) {
                if (d < crater) { top[i] = size - 3; lava[i] = size - 1; code[i] = CRATER; }
                else if (d < 1) { top[i] = (int) Math.round(size * Math.pow((1 - d) / (1 - crater), 1.3)); code[i] = top[i] > 0 ? SHELL : SCATTER; }
                else if (d < scatter && unit(seed, 100 + i) < 0.6 * (scatter - d) / (scatter - 1)) code[i] = SCATTER;
            } else {
                if (d < pool) { top[i] = -size - 1; lava[i] = -size + 1; code[i] = CRATER; }
                else if (d < 1) { top[i] = -(int) Math.round(size * Math.pow((1 - d) / (1 - pool), 0.9)); code[i] = SHELL; }
                else if (d < scatter) { top[i] = 1; code[i] = RING; }
            }
        }
        // Containment: a lava column's side neighbours are lava or ground up to its lava top.
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int i = index(dx, dz);
                if (lava[i] == NO_LAVA) continue;
                int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] s : side) {
                    int x = dx + s[0], z = dz + s[1];
                    if (Math.abs(x) > r || Math.abs(z) > r) continue;
                    int j = index(x, z);
                    if (lava[j] != NO_LAVA) continue;
                    if (top[j] < lava[i]) { top[j] = lava[i]; if (code[j] == NONE || code[j] == SCATTER) code[j] = SHELL; changed = true; }
                }
            }
        }
    }

    public int index(int dx, int dz) { return (dz + r) * (2 * r + 1) + (dx + r); }
    /** Top of the solid ground relative to the centre ground (0 outside the vent). */
    public int top(int dx, int dz) { return top[index(dx, dz)]; }
    /** Top of the lava relative to the centre ground, or NO_LAVA. */
    public int lava(int dx, int dz) { return lava[index(dx, dz)]; }
    public int code(int dx, int dz) { return code[index(dx, dz)]; }
    /** Normalized elliptic distance from the centre (1 at the foot of the cone or the edge of the pit). */
    public double distance(int dx, int dz) { return q[index(dx, dz)]; }

    /** 0..1 from a seed and a salt. */
    public static double unit(long seed, long salt) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
