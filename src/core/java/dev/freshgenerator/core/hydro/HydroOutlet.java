package dev.freshgenerator.core.hydro;

/** Outlet channel of a through-flow lake (docs/hydrology-1.0.md, section 6).
 * <p>The lake level is set on the block scale (the highest watertight level), so its shore lies below and before the
 * lattice rim where the outlet river begins. A lake that feeds a river must overflow into it: a short channel runs
 * from the nearest shore to the start of the outlet river along a gentle curve that joins the river in its own
 * direction, at the lake level at the shore and never below the river's first water, cut through the saddle with low
 * banks and steep sides (a natural overflow notch). */
public final class HydroOutlet {
    static final HydroOutlet NONE = new HydroOutlet();
    static final double[] BANK_OFFSETS = {0.7, 1.5, 2.5};
    /** Carving reach beyond the channel edge (the sides rise 0.6 per block). */
    static final double INFLUENCE = 40, SIDE = 1.0;
    /** Last REF_BACK water levels of the channel (continuity of the outlet river's banks at its start). */
    double[][] tail() {
        int back = (int) HydroReach.REF_BACK;
        double[][] t = new double[2][back];
        for (int i = 0; i < back; i++) { int at = Math.max(0, water.length - back + i); t[0][i] = water[at]; t[1][i] = water[at]; }
        return t;
    }
    final double[] px, pz, s;
    final double length, half;
    final double ax, az, bx, bz;
    final int[] water;
    final int depth;
    final double minX, minZ, maxX, maxZ;

    private HydroOutlet() {
        px = pz = s = new double[0]; water = new int[0];
        length = half = ax = az = bx = bz = minX = minZ = maxX = maxZ = 0; depth = 0;
    }

    private HydroOutlet(double[] px, double[] pz, double half, int[] water, int depth) {
        this.px = px; this.pz = pz; this.half = half; this.water = water; this.depth = depth;
        int n = px.length;
        s = new double[n];
        for (int i = 1; i < n; i++) s[i] = s[i - 1] + Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
        length = Math.max(1e-6, s[n - 1]);
        ax = px[0]; az = pz[0]; bx = px[n - 1]; bz = pz[n - 1];
        double reach = half + INFLUENCE, x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) { x0 = Math.min(x0, px[i]); x1 = Math.max(x1, px[i]); z0 = Math.min(z0, pz[i]); z1 = Math.max(z1, pz[i]); }
        minX = x0 - reach; maxX = x1 + reach; minZ = z0 - reach; maxZ = z1 + reach;
    }

    static HydroOutlet build(HydroModel m, HydroModel.Group g) {
        HydroLakes.Mask mask = g.mask;
        long rim = g.flood.rim;
        if (mask == null || m.channel(rim) != 2) return null;
        HydroReach r = m.reach(rim);
        if (r == null || !r.wet) return null;
        double ex = r.px[0], ez = r.pz[0];
        int level = mask.level(), start = r.core(m).water[0];
        // The river head stands higher than the lake (backwater from the water body it runs into): the lake cannot
        // overflow into it, and a channel would make the water rise downstream (audit 22.11 H02).
        if (start > level) return null;
        // Nearest shore anywhere on the accepted mask (no search window: a lowered lake can lie far from the rim).
        double best = Double.POSITIVE_INFINITY; int qx = 0, qz = 0;
        for (int word = 0; word < mask.bits.length; word++) {
            long bits = mask.bits[word];
            while (bits != 0) {
                int i = (word << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int x = mask.x0 + i % mask.w, z = mask.z0 + i / mask.w;
                double dx = x + 0.5 - ex, dz = z + 0.5 - ez, d = dx * dx + dz * dz;
                if (d < best) { best = d; qx = x; qz = z; }
            }
        }
        best = Math.sqrt(best);
        double half = r.w0 / 2;
        if (Double.isInfinite(best) || best <= half + 2) return null;
        double sx = qx + 0.5, sz = qz + 0.5, len = Math.hypot(ex - sx, ez - sz);
        // Quadratic curve shore -> river start whose end tangent is the river's own direction (no kink at the
        // junction): the control point lies on the river's backward tangent. The curve bends naturally unless the
        // river already points away from the shore.
        double tx = r.px[1] - r.px[0], tz = r.pz[1] - r.pz[0], tl = Math.max(1e-9, Math.hypot(tx, tz));
        tx /= tl; tz /= tl;
        double cxp = ex - tx * len * 0.5, czp = ez - tz * len * 0.5;
        int n = Math.max(4, (int) Math.ceil(len / 2)) + 1;
        double[] px = new double[n], pz = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1), u = 1 - t;
            px[i] = u * u * sx + 2 * u * t * cxp + t * t * ex;
            pz[i] = u * u * sz + 2 * u * t * czp + t * t * ez;
        }
        // Water profile along the curve: from the lake level down to the river's first water, never above the banks.
        int end = start;
        double[] arc = new double[n];
        for (int i = 1; i < n; i++) arc[i] = arc[i - 1] + Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
        double total = Math.max(1e-6, arc[n - 1]);
        int blocks = (int) Math.ceil(total) + 1;
        int[] water = new int[blocks];
        int seg = 0;
        for (int b = 0; b < blocks; b++) {
            double a = Math.min(total, b);
            while (seg < n - 2 && arc[seg + 1] < a) seg++;
            double f = arc[seg + 1] - arc[seg] < 1e-9 ? 0 : (a - arc[seg]) / (arc[seg + 1] - arc[seg]);
            double x = px[seg] + (px[seg + 1] - px[seg]) * f, z = pz[seg] + (pz[seg + 1] - pz[seg]) * f;
            double dx = px[seg + 1] - px[seg], dz = pz[seg + 1] - pz[seg], dl = Math.max(1e-9, Math.hypot(dx, dz));
            double nx = -dz / dl, nz = dx / dl, ceil = Double.POSITIVE_INFINITY;
            for (double off : BANK_OFFSETS) {
                double o = half + off;
                ceil = Math.min(ceil, Math.min(m.terrain.at(x + nx * o, z + nz * o), m.terrain.at(x - nx * o, z - nz * o)) - 1 - 1e-6);
            }
            double ramp = level + (end - level) * (a / total);
            int w = (int) Math.min(Math.floor(ramp + 1e-9), Math.floor(ceil));
            if (b > 0) w = Math.min(w, water[b - 1]);
            water[b] = Math.max(w, end);
        }
        return new HydroOutlet(px, pz, half, water, Math.max(1, r.d0));
    }

    boolean inBox(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    boolean intersects(int x0, int z0, int x1, int z1) { return x1 >= minX && x0 <= maxX && z1 >= minZ && z0 <= maxZ; }

    /** Writes {carve, water, inChannel, distance}; carve = +inf beyond the influence. */
    void evaluate(double x, double z, double[] out) {
        double best = Double.MAX_VALUE, arcAt = 0;
        for (int i = 0; i + 1 < px.length; i++) {
            double ax0 = px[i], az0 = pz[i], dx = px[i + 1] - ax0, dz = pz[i + 1] - az0;
            double ll = dx * dx + dz * dz, t = ll < 1e-12 ? 0 : ((x - ax0) * dx + (z - az0) * dz) / ll;
            t = t < 0 ? 0 : t > 1 ? 1 : t;
            double qx = ax0 + dx * t - x, qz = az0 + dz * t - z, d2 = qx * qx + qz * qz;
            if (d2 < best) { best = d2; arcAt = s[i] + (s[i + 1] - s[i]) * t; }
        }
        double d = Math.sqrt(best);
        int at = Math.min(water.length - 1, (int) Math.round(arcAt)), w = water[at];
        out[3] = d; out[4] = w;
        if (d <= half) {
            double bed = w - depth, u = d / Math.max(half, HydroReach.MIN_HALF);
            out[0] = bed + (w + 0.4 - bed) * u * u; out[1] = w; out[2] = 1;
            return;
        }
        // Banks follow the water REF_BACK blocks upstream (never below the water above a step: flowing water from a
        // riffle stays in the channel instead of spreading over a shelf at the lower level), and the notch sides rise
        // a block per block (no wide flat shelf cut into a saddle) - audit 23.11 water B-1.
        double xb = d - half;
        int ref = water[Math.max(0, at - (int) HydroReach.REF_BACK)];
        out[0] = xb > INFLUENCE ? Double.POSITIVE_INFINITY : ref + 0.4 + Math.min(1.6 * xb, 1.4) + SIDE * Math.max(0, xb - 1);
        out[1] = HydroModel.NO_WATER; out[2] = 0;
    }
}
