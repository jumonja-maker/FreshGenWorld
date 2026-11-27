package dev.freshgenerator.core.road;

import java.util.List;

/** A planned road: its centre line every block, the half width and paint density along it (gaps are density 0), and
 * its bridges. Immutable; drawn per chunk from the same plan, so a road is continuous over chunk borders. */
public final class RoadLine {
    public final long id;
    public final double[] x, z;
    public final float[] half, density;
    public final List<Bridge> bridges;
    public final int minX, minZ, maxX, maxZ;
    /** Straight distance between the ends and the largest grade over 16 blocks (statistics, audits). */
    public final double span, maxGrade;

    RoadLine(long id, double[] x, double[] z, float[] half, float[] density, List<Bridge> bridges, double span, double maxGrade) {
        this.id = id; this.x = x; this.z = z; this.half = half; this.density = density; this.bridges = List.copyOf(bridges);
        this.span = span; this.maxGrade = maxGrade;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i < x.length; i++) {
            x0 = Math.min(x0, (int) Math.floor(x[i]) - 3); x1 = Math.max(x1, (int) Math.floor(x[i]) + 3);
            z0 = Math.min(z0, (int) Math.floor(z[i]) - 3); z1 = Math.max(z1, (int) Math.floor(z[i]) + 3);
        }
        for (Bridge b : bridges) {
            int[] box = b.box();
            x0 = Math.min(x0, box[0]); z0 = Math.min(z0, box[1]); x1 = Math.max(x1, box[2]); z1 = Math.max(z1, box[3]);
        }
        minX = x0; minZ = z0; maxX = x1; maxZ = z1;
    }

    public int length() { return x.length; }
    public boolean touches(int x0, int z0, int x1, int z1) { return maxX >= x0 && minX <= x1 && maxZ >= z0 && minZ <= z1; }
    /** Whether the road (its centre line with half width and a block, gaps included: a break in the path still reads as
     * the way on) or one of its bridges reaches into a block box. */
    public boolean crosses(int x0, int z0, int x1, int z1) {
        if (!touches(x0, z0, x1, z1)) return false;
        for (Bridge b : bridges) {
            int[] box = b.box();
            if (box[2] >= x0 && box[0] <= x1 && box[3] >= z0 && box[1] <= z1) return true;
        }
        for (int i = 0; i < x.length; i++) {
            double r = half[i] + 1;
            if (x[i] + r >= x0 && x[i] - r <= x1 + 1 && z[i] + r >= z0 && z[i] - r <= z1 + 1) return true;
        }
        return false;
    }
    /** Painted length (blocks of centre line with paint) - statistics. */
    public int painted() { int n = 0; for (float d : density) if (d > 0) n++; return n; }

    public interface ColumnSink {
        /** A column the road covers: the chance of a path block there (0..1) and the nearest centre point. */
        void column(int x, int z, double chance, int point);
    }

    /** The columns of a 16x16 chunk (min corner x0, z0) the road covers, bridge footprints excluded. */
    public void cover(int x0, int z0, ColumnSink out) {
        if (!touches(x0, z0, x0 + 15, z0 + 15)) return;
        float[] best = new float[256];
        int[] near = new int[256];
        java.util.Arrays.fill(best, Float.POSITIVE_INFINITY);
        for (int i = 0; i < x.length; i++) {
            if (density[i] <= 0) continue;
            double px = x[i], pz = z[i];
            int r = (int) Math.ceil(half[i] + 0.5);
            if (px < x0 - r - 1 || px > x0 + 16 + r || pz < z0 - r - 1 || pz > z0 + 16 + r) continue;
            int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int cx = bx + dx, cz = bz + dz;
                if (cx < x0 || cx > x0 + 15 || cz < z0 || cz > z0 + 15) continue;
                float d = (float) Math.hypot(cx + 0.5 - px, cz + 0.5 - pz);
                int k = (cz - z0) * 16 + (cx - x0);
                if (d < best[k]) { best[k] = d; near[k] = i; }
            }
        }
        for (int k = 0; k < 256; k++) {
            if (best[k] == Float.POSITIVE_INFINITY) continue;
            int i = near[k], cx = x0 + (k & 15), cz = z0 + (k >> 4);
            double h = half[i], d = best[k];
            double chance = d <= h ? 0.93 : d <= h + 0.5 ? 0.5 : 0;
            chance *= density[i];
            if (chance <= 0) continue;
            boolean onBridge = false;
            for (Bridge b : bridges) if (b.contains(cx, cz)) { onBridge = true; break; }
            if (!onBridge) out.column(cx, cz, chance, i);
        }
    }
}
