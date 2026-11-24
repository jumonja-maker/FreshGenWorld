package dev.freshgenerator.core.hydro;


import java.util.Arrays;

/** Block-level lake water: a 4-connected fill of the columns below the lake level from the basin interior.
 * If the fill reaches the edge of the search box (an unseen low spill on the block scale) or the open sea,
 * the level drops by one block and the fill repeats - the lake is watertight by construction. River channel
 * columns belong to their rivers and are not filled. */
final class HydroLakes {
    static final int MARGIN = 40, MAX_COLUMNS = 2_000_000, DIST_CAP = 31;
    private HydroLakes() { }

    static final class Mask {
        final int x0, z0, w, h, level;
        final long[] bits;
        final int count;
        /** Water depth over the mask (blocks of water above the ground surface): maximum and mean. */
        final double depthMax, depthMean;
        /** Columns holding at least two blocks of water. */
        final int deep;
        Mask(int x0, int z0, int w, int h, int level, long[] bits, int count) { this(x0, z0, w, h, level, bits, count, 0, 0, 0); }
        Mask(int x0, int z0, int w, int h, int level, long[] bits, int count, double depthMax, double depthMean, int deep) {
            this.x0 = x0; this.z0 = z0; this.w = w; this.h = h; this.level = level; this.bits = bits; this.count = count;
            this.depthMax = depthMax; this.depthMean = depthMean; this.deep = deep;
        }
        /** Share of the lake area with at least two blocks of water (depth = level + 0.5 - ground >= 1.5). */
        double deepShare() { return count == 0 ? 0 : deep / (double) count; }
        boolean contains(int x, int z) {
            int a = x - x0, b = z - z0;
            if (a < 0 || b < 0 || a >= w || b >= h) return false;
            int i = a + w * b;
            return (bits[i >>> 6] & (1L << (i & 63))) != 0;
        }
        int level() { return level; }
        /** Up to max columns of the mask, evenly spread in mask order (packed x << 32 | z). */
        long[] samples(int max) {
            if (count == 0) return new long[0];
            int stride = Math.max(1, (count + max - 1) / max), n = 0, seen = 0;
            long[] out = new long[(count + stride - 1) / stride];
            for (int i = 0; i < w * h && n < out.length; i++) {
                if ((bits[i >>> 6] & (1L << (i & 63))) == 0) continue;
                if (seen++ % stride == 0) out[n++] = ((long) (x0 + i % w) << 32) | ((z0 + i / w) & 0xffffffffL);
            }
            return n == out.length ? out : Arrays.copyOf(out, n);
        }
        /** Exact Euclidean distance (blocks, quarter-block resolution, capped at DIST_CAP) from a column to the
         * nearest lake column. The field covers the mask box widened by the cap on every side, so a column just
         * outside the box still sees water near its edge (audit 22.11 F-02); no chamfer over-estimate (FG3-05). */
        double distance(int x, int z) {
            int c = DIST_CAP + 1, a = x - x0 + c, b = z - z0 + c, W = w + 2 * c, H = h + 2 * c;
            if (a < 0 || b < 0 || a >= W || b >= H) return DIST_CAP;
            byte[] d = dist;
            // Built once per mask: concurrent first readers built the same map each (code audit 24.11 HY-02: eight
            // threads allocated 48 MB for one map).
            if (d == null) synchronized (this) { d = dist; if (d == null) dist = d = euclidean(); }
            return d[a + W * b] / 4.0;
        }
        private volatile byte[] dist;
        /** Felzenszwalb-Huttenlocher squared distance transform (columns, then rows), quantized to quarter blocks. */
        private byte[] euclidean() {
            int c = DIST_CAP + 1, W = w + 2 * c, H = h + 2 * c;
            int far = 4 * c;                       // farther than the cap: treated as infinity
            int[] g = new int[W * H];
            for (int a = 0; a < W; a++) {
                int run = far;
                for (int b = 0; b < H; b++) { run = inside(a - c, b - c) ? 0 : Math.min(far, run + 1); g[a + W * b] = run; }
                run = far;
                for (int b = H - 1; b >= 0; b--) { run = inside(a - c, b - c) ? 0 : Math.min(far, run + 1); g[a + W * b] = Math.min(g[a + W * b], run); }
            }
            byte[] out = new byte[W * H];
            double[] f = new double[W], dt = new double[W], zz = new double[W + 1];
            int[] v = new int[W];
            double inf = 1e18, cap = DIST_CAP * 4.0;
            for (int b = 0; b < H; b++) {
                for (int a = 0; a < W; a++) { int gv = g[a + W * b]; f[a] = gv >= far ? inf : (double) gv * gv; }
                int k = 0; v[0] = 0; zz[0] = -inf; zz[1] = inf;
                for (int q = 1; q < W; q++) {
                    double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
                    while (s <= zz[k]) { k--; s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]); }
                    k++; v[k] = q; zz[k] = s; zz[k + 1] = inf;
                }
                k = 0;
                for (int q = 0; q < W; q++) { while (zz[k + 1] < q) k++; dt[q] = (double) (q - v[k]) * (q - v[k]) + f[v[k]]; }
                for (int a = 0; a < W; a++) out[a + W * b] = (byte) Math.min(cap, Math.floor(Math.sqrt(dt[a]) * 4));
            }
            return out;
        }
        private boolean inside(int a, int b) {
            if (a < 0 || b < 0 || a >= w || b >= h) return false;
            int i = a + w * b;
            return (bits[i >>> 6] & (1L << (i & 63))) != 0;
        }
        boolean intersects(int bx0, int bz0, int bx1, int bz1) { return bx1 >= x0 && bx0 < x0 + w && bz1 >= z0 && bz0 < z0 + h; }
    }

    static Mask contain(HydroModel m, HydroModel.Group g, int target) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int ni0 = Integer.MAX_VALUE, nj0 = Integer.MAX_VALUE, ni1 = Integer.MIN_VALUE, nj1 = Integer.MIN_VALUE;
        for (long k : g.flood.region) {
            minX = Math.min(minX, m.x(k)); maxX = Math.max(maxX, m.x(k)); minZ = Math.min(minZ, m.z(k)); maxZ = Math.max(maxZ, m.z(k));
            ni0 = Math.min(ni0, HydroModel.ni(k)); ni1 = Math.max(ni1, HydroModel.ni(k));
            nj0 = Math.min(nj0, HydroModel.nj(k)); nj1 = Math.max(nj1, HydroModel.nj(k));
        }
        int x0 = minX - MARGIN, z0 = minZ - MARGIN, w = maxX - minX + 2 * MARGIN + 1, h = maxZ - minZ + 2 * MARGIN + 1;
        if ((long) w * h > 4L * MAX_COLUMNS) return null;
        boolean[] excluded = new boolean[w * h];
        // Channels of every reach whose start lies near the basin (inflows, outlet, passing rivers).
        for (int j = nj0 - 3; j <= nj1 + 3; j++) for (int i = ni0 - 3; i <= ni1 + 3; i++) {
            long n = HydroModel.node(i, j);
            if (m.channel(n) == 0) continue;
            HydroReach r = m.reach(n);
            if (r == null) continue;
            stamp(r, excluded, x0, z0, w, h);
        }
        int bottom = (int) Math.floor(m.h(g.deepest));
        Mask first = highest(m, g.interior, target, bottom, excluded, x0, z0, w, h);
        if (first != FAILED) return first;
        // The channels can cover every low interior seed (inflows end on the interior nodes). The basin was proven
        // watertight without channels (preCheck), so its water stands: fill without the channel exclusion, from the
        // target down, and finally at the proven level itself. A decided lake never ends up without water.
        boolean[] none = new boolean[w * h];
        Mask open = highest(m, g.interior, target, bottom, none, x0, z0, w, h);
        if (open != FAILED) return open;
        if (g.preLevel != HydroModel.NO_WATER && g.preLevel > target) {
            Mask mask = fill(m, g.interior, g.preLevel, none, x0, z0, w, h);
            if (mask != FAILED) return mask;
        }
        return null;
    }
    static final Mask FAILED = new Mask(0, 0, 0, 0, 0, new long[0], 0);

    /** Block-scale containment of a hollow before any channel exists: the fill at the highest level (from the
     * target down) at which the water stays enclosed (with its depth), or null. */
    static Mask preCheck(HydroModel m, HydroModel.Flood f, long[] interior, long deepest, int target) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : f.region) { minX = Math.min(minX, m.x(k)); maxX = Math.max(maxX, m.x(k)); minZ = Math.min(minZ, m.z(k)); maxZ = Math.max(maxZ, m.z(k)); }
        int x0 = minX - MARGIN, z0 = minZ - MARGIN, w = maxX - minX + 2 * MARGIN + 1, h = maxZ - minZ + 2 * MARGIN + 1;
        if ((long) w * h > 4L * MAX_COLUMNS || interior.length == 0) return null;
        boolean[] none = new boolean[w * h];
        int bottom = (int) Math.floor(m.h(deepest));
        Mask mask = highest(m, interior, target, bottom, none, x0, z0, w, h);
        return mask != FAILED ? mask : null;
    }

    /** The fill at the highest level in (bottom, top] at which the water stays enclosed, or FAILED: the result of
     * scanning down from the top one block at a time, found with a few fills. Above the lowest seed the outcome is
     * monotone in the level - the water region at a lower level is a subset of the region at a higher one (fewer
     * seeds, fewer columns at or below the level), so it reaches neither the box edge nor the open sea when the
     * higher one does not - and below the lowest seed every fill fails. Galloping down from the top keeps the
     * common case (enclosed at the top or a block or two lower) as cheap as the scan, while a long descent no
     * longer floods the whole box once per level. Same level, same fill, same mask. */
    static Mask highest(HydroModel m, long[] interior, int top, int bottom, boolean[] excluded, int x0, int z0, int w, int h) {
        double lowest = Double.POSITIVE_INFINITY;
        for (long k : interior) {
            int i = (m.x(k) - x0) + w * (m.z(k) - z0);
            if (!excluded[i]) lowest = Math.min(lowest, m.terrain.at(m.x(k), m.z(k)));
        }
        if (lowest == Double.POSITIVE_INFINITY) return FAILED;
        int lo = (int) Math.max(bottom + 1, Math.ceil(lowest));     // the lowest level with a seed
        if (lo > top) return FAILED;
        // Invariant: every level >= bad fails; good is a level that holds (or lo - 1 before one is found).
        int bad = top + 1, good = lo - 1;
        Mask kept = FAILED;
        for (int n = 0; ; n++) {
            int level = Math.max(lo, bad - (n == 0 ? 1 : 1 << (n - 1)));   // top, top-1, top-3, top-7, ...
            Mask mask = fill(m, interior, level, excluded, x0, z0, w, h);
            if (mask != FAILED) { good = level; kept = mask; break; }
            bad = level;
            if (level == lo) return FAILED;
        }
        while (bad - good > 1) {
            int level = good + (bad - good) / 2;
            Mask mask = fill(m, interior, level, excluded, x0, z0, w, h);
            if (mask != FAILED) { good = level; kept = mask; } else bad = level;
        }
        return kept;
    }

    /** Exclude exactly the columns the field treats as this reach's channel. */
    private static void stamp(HydroReach r, boolean[] excluded, int x0, int z0, int w, int h) {
        double half = r.maxWidth() / 2 + 1;
        double[] scratch = new double[2];
        int ax = (int) Math.floor(Arrays.stream(r.px).min().orElse(0) - half), bx = (int) Math.ceil(Arrays.stream(r.px).max().orElse(0) + half);
        int az = (int) Math.floor(Arrays.stream(r.pz).min().orElse(0) - half), bz = (int) Math.ceil(Arrays.stream(r.pz).max().orElse(0) + half);
        for (int z = Math.max(az, z0); z <= Math.min(bz, z0 + h - 1); z++)
            for (int x = Math.max(ax, x0); x <= Math.min(bx, x0 + w - 1); x++) {
                int i = (x - x0) + w * (z - z0);
                if (!excluded[i] && r.inChannel(x, z, scratch)) excluded[i] = true;
            }
    }

    /** Watertight fill at a level, or FAILED when the water escapes (box edge or the open sea) or has no seed. The
     * fill is bounded by its box (at most 4 x MAX_COLUMNS cells, checked by the callers): there is no separate
     * column budget, so a large watertight lake is never lowered just to fit a budget (audit 22.11 N-01). */
    static Mask fill(HydroModel m, long[] interior, int level, boolean[] excluded, int x0, int z0, int w, int h) {
        // Channel columns belong to their rivers (not to the mask) but the water still connects through them: with
        // the channels cutting the fill, low ground between the stamped channels of a basin stayed dry below the
        // lake level and the river at that level spilled into it (audit 23.11: seed 120327 X147 Z-1597, a stream at
        // the lake level 91 beside ground at 88.6). Connectivity is that of the basin without channels, which the
        // preCheck proved watertight.
        long[] bits = new long[(w * h + 63) >>> 6], seen = new long[bits.length];
        int[] queue = new int[256];
        int head = 0, tail = 0, count = 0;
        double depthSum = 0, depthMax = 0;
        int deep = 0;
        for (long k : interior) {
            int a = m.x(k) - x0, b = m.z(k) - z0, i = a + w * b;
            double t = m.terrain.at(m.x(k), m.z(k));
            if (excluded[i] || t > level) continue;
            if ((seen[i >>> 6] & (1L << (i & 63))) != 0) continue;
            seen[i >>> 6] |= 1L << (i & 63);
            bits[i >>> 6] |= 1L << (i & 63);
            count++;
            double dep = level + 0.5 - t; depthSum += dep; depthMax = Math.max(depthMax, dep); if (dep >= 1.5) deep++;
            if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length * 2);
            queue[tail++] = i;
        }
        if (tail == 0) return FAILED;
        while (head < tail) {
            int i = queue[head++], a = i % w, b = i / w;
            int x = a + x0, z = b + z0;
            if (m.ocean(HydroModel.nodeAt(x, z)) && m.terrain.at(x, z) < HydroModel.SEA_LEVEL) return FAILED;
            for (int d = 0; d < 4; d++) {
                int na = a + (d == 0 ? 1 : d == 1 ? -1 : 0), nb = b + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (na < 0 || nb < 0 || na >= w || nb >= h) return FAILED;
                int ni = na + w * nb;
                if ((seen[ni >>> 6] & (1L << (ni & 63))) != 0) continue;
                double t = m.terrain.at(na + x0, nb + z0);
                if (t > level) continue;
                seen[ni >>> 6] |= 1L << (ni & 63);
                if (!excluded[ni]) {
                    bits[ni >>> 6] |= 1L << (ni & 63);
                    count++;
                    double dep = level + 0.5 - t; depthSum += dep; depthMax = Math.max(depthMax, dep); if (dep >= 1.5) deep++;
                }
                if (tail == queue.length) queue = Arrays.copyOf(queue, Math.min(w * h, queue.length * 2));
                queue[tail++] = ni;
            }
        }
        return new Mask(x0, z0, w, h, level, bits, count, depthMax, depthSum / count, deep);
    }
}
