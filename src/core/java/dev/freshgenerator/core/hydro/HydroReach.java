package dev.freshgenerator.core.hydro;

/** One channel reach between two lattice nodes: a smooth centreline (centripetal Catmull-Rom through the
 * jittered nodes), channel width and depth, valley cross-section, bank ceilings measured on the real ground,
 * and - once the node water levels are known - the stepped water profile with riffles, cascades or a
 * waterfall with its plunge pool. */
public final class HydroReach {
    public static final int INTO_CHANNEL = 0, INTO_LAKE = 1, INTO_SEA = 2, TERMINAL = 3;
    static final HydroReach ABSENT = new HydroReach();
    /** Water stays 1 block below the bank top; banks are sampled this far beyond the channel edge. */
    static final double BANK_OFFSET = 1.5, SAMPLE = 2.0, REF_BACK = 8;
    /** Inner gorge of a steep cascade: its walls start SIDE_ABOVE blocks over the local water and rise NOTCH blocks per
     * block (63 degrees) until they meet the valley side. */
    static final double SIDE_ABOVE = 2, NOTCH = 2;
    static final double[] BANK_OFFSETS = {0.7, 1.5, 2.5};

    public final long from, to;
    public final boolean wet;
    /** This reach is the main donor of its downstream node (the river continues through it). */
    final boolean mainstem;
    public final int end;
    final double[] px, pz, s;
    public final double length;
    final double w0, w1;
    final int d0, d1;
    public final HydroClimate.Valley valley;
    final double fp, influence;
    /** Cross-section parameters at both ends (interpolated along the reach: no seams at nodes). */
    final double fpA, fpB, bankA, bankB, s1A, s1B, s2A, s2B;
    final double minX, minZ, maxX, maxZ;
    final double[] bankCeil, center;
    /** Signed curvature of the centreline per sample (radians per block, smoothed over about 12 blocks). */
    final double[] bend;
    final double bankCeilMin;
    /** Full profile (with the valley-floor reference) and the core profile (water, ramp and falls only: it never
     * reads another reach's profile, so building profiles never recurses along a river). */
    private volatile Profile profile, core;
    private volatile Pockets pockets;

    private HydroReach() {
        from = to = HydroModel.NONE; wet = false; mainstem = false; end = TERMINAL; px = pz = s = bankCeil = center = bend = new double[0];
        length = w0 = w1 = fp = influence = minX = minZ = maxX = maxZ = bankCeilMin = 0; d0 = d1 = 0; valley = null;
        fpA = fpB = bankA = bankB = s1A = s1B = s2A = s2B = 0;
        widthSeed = 0; terraced = false;
    }

    private HydroReach(long from, long to, boolean wet, boolean mainstem, int end, double[] px, double[] pz, double w0, double w1, int d0, int d1,
                       HydroClimate.Valley valley, HydroClimate.Valley valleyB, BaseTerrain terrain, long seed) {
        this.from = from; this.to = to; this.wet = wet; this.mainstem = mainstem; this.end = end; this.px = px; this.pz = pz;
        this.w0 = w0; this.w1 = w1; this.d0 = d0; this.d1 = d1; this.valley = valley;
        fpA = valley.floodplain(w0); fpB = valleyB.floodplain(w1); bankA = valley.bank; bankB = valleyB.bank;
        terraced = valley == HydroClimate.Valley.CANYON && valleyB == HydroClimate.Valley.CANYON;
        s1A = valley.s1; s1B = valleyB.s1; s2A = valley.s2; s2B = valleyB.s2;
        int n = px.length;
        s = new double[n];
        for (int i = 1; i < n; i++) s[i] = s[i - 1] + Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
        length = Math.max(1e-6, s[n - 1]);
        widthSeed = HydroModel.hash(seed ^ 0x3D1D7L, HydroModel.ni(from), HydroModel.nj(from));
        double wmax = maxWidth();
        fp = Math.max(fpA, fpB);
        double reach = valley == HydroClimate.Valley.CANYON && valleyB == HydroClimate.Valley.CANYON ? 40 : HydroClimate.Valley.REACH;
        influence = wmax / 2 + fp + reach;
        double ax = Double.MAX_VALUE, az = Double.MAX_VALUE, bx = -Double.MAX_VALUE, bz = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) { ax = Math.min(ax, px[i]); bx = Math.max(bx, px[i]); az = Math.min(az, pz[i]); bz = Math.max(bz, pz[i]); }
        minX = ax - influence; minZ = az - influence; maxX = bx + influence; maxZ = bz + influence;
        bankCeil = new double[n];
        center = new double[n];
        double cmin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double tx = px[Math.min(n - 1, i + 1)] - px[Math.max(0, i - 1)], tz = pz[Math.min(n - 1, i + 1)] - pz[Math.max(0, i - 1)];
            double tl = Math.max(1e-9, Math.hypot(tx, tz)), nx = -tz / tl, nz = tx / tl;
            // Banks sampled just beyond the edge, at the bank and a little farther: a low spot anywhere next to the
            // channel caps the level (the containment rule cannot raise natural ground).
            double left = Double.POSITIVE_INFINITY, right = Double.POSITIVE_INFINITY;
            for (double off : BANK_OFFSETS) {
                double half = width(s[i]) / 2 + off;
                left = Math.min(left, terrain.at(px[i] + nx * half, pz[i] + nz * half));
                right = Math.min(right, terrain.at(px[i] - nx * half, pz[i] - nz * half));
            }
            center[i] = terrain.at(px[i], pz[i]);
            // floor(W) <= ceil(H) - 2 (water one block below the bank top) <=> W < H - 1; continuous so that node
            // levels and the valley floor stay continuous.
            bankCeil[i] = Math.min(left, right) - 1 - 1e-6;
            cmin = Math.min(cmin, bankCeil[i]);
        }
        bankCeilMin = wet ? cmin : Double.POSITIVE_INFINITY;
        bend = curvature(px, pz, s);
    }

    /** Signed curvature at every sample: heading change per block, averaged over BEND_SPAN samples each way. */
    static double[] curvature(double[] px, double[] pz, double[] s) {
        int n = px.length;
        double[] raw = new double[n], out = new double[n];
        for (int i = 1; i + 1 < n; i++) {
            double a = Math.atan2(pz[i] - pz[i - 1], px[i] - px[i - 1]), b = Math.atan2(pz[i + 1] - pz[i], px[i + 1] - px[i]);
            double turn = b - a;
            if (turn > Math.PI) turn -= 2 * Math.PI; else if (turn < -Math.PI) turn += 2 * Math.PI;
            raw[i] = turn / Math.max(1e-6, (s[i + 1] - s[i - 1]) / 2);
        }
        for (int i = 0; i < n; i++) {
            double sum = 0; int count = 0;
            for (int j = Math.max(1, i - BEND_SPAN); j <= Math.min(n - 2, i + BEND_SPAN); j++) { sum += raw[j]; count++; }
            out[i] = count == 0 ? 0 : sum / count;
        }
        return out;
    }
    static final int BEND_SPAN = 3;

    static HydroReach build(HydroModel m, long key) {
        int ch = m.channel(key);
        if (ch == 0) return null;
        long dn = m.down(key), to;
        int end;
        if (dn == HydroModel.NONE) return null;
        if (dn == HydroModel.OCEAN_ID || dn == HydroModel.BASIN_FLAG) {
            byte r = m.receiver(key);
            if (r < 0 || r >= 8) return null;
            to = HydroModel.nb(key, r);
            if (dn == HydroModel.OCEAN_ID) end = INTO_SEA;
            else {
                HydroModel.Group g = m.decided(m.basinOf(key));
                end = g.state == HydroModel.Group.LAKE || g.state == HydroModel.Group.POND ? INTO_LAKE : TERMINAL;
            }
        } else {
            to = dn;
            end = m.ocean(to) ? INTO_SEA : INTO_CHANNEL;
        }
        boolean wet = ch == 2;
        HydroClimate.Setting s0 = m.setting(key);
        double w0, w1; int d0, d1;
        if (wet) {
            w0 = HydroClimate.width(m.widthK(key), m.flow(key)); d0 = HydroClimate.depth(m.depthK(key), m.flow(key));
            if (end == INTO_CHANNEL && m.channel(to) == 2) { w1 = HydroClimate.width(m.widthK(to), m.flow(to)); d1 = HydroClimate.depth(m.depthK(to), m.flow(to)); }
            else { w1 = w0; d1 = d0; }
        } else {
            w0 = HydroClimate.dryWidth(m.widthK(key), m.area(key)); d0 = HydroClimate.dryDepth(m.area(key));
            if (end == INTO_CHANNEL && m.channel(to) > 0) { w1 = HydroClimate.dryWidth(m.widthK(to), m.area(to)); d1 = HydroClimate.dryDepth(m.area(to)); }
            else { w1 = w0; d1 = d0; }
        }
        double[] p1 = m.pathPoint(key), p2 = end == INTO_CHANNEL ? m.pathPoint(to) : new double[]{m.x(to), m.z(to)};
        double x1 = p1[0], z1 = p1[1], x2 = p2[0], z2 = p2[1];
        long md = m.mainDonor(key);
        double[] p0 = md != HydroModel.NONE ? m.pathPoint(md) : null;
        double x0 = p0 != null ? p0[0] : 2 * x1 - x2, z0 = p0 != null ? p0[1] : 2 * z1 - z2;
        double x3 = 2 * x2 - x1, z3 = 2 * z2 - z1;
        if (end == INTO_CHANNEL) {
            long dd = m.down(to);
            if (dd != HydroModel.NONE && dd != HydroModel.OCEAN_ID && dd != HydroModel.BASIN_FLAG) { double[] p3 = m.pathPoint(dd); x3 = p3[0]; z3 = p3[1]; }
        }
        int n = Math.max(4, (int) Math.ceil(Math.hypot(x2 - x1, z2 - z1) / SAMPLE)) + 1;
        double[] px = new double[n], pz = new double[n];
        catmullRom(x0, z0, x1, z1, x2, z2, x3, z3, px, pz);
        // Meanders of rivers and the bends of creeks and washes (shared field: reaches meeting at a node move it
        // identically). Every reach moves with the parameters of its own nodes, a dry wash ending at a wet node with
        // the wet node's, so that its end stays on the channel it joins.
        double mw0 = meanderWidth(m, key), mw1 = end == INTO_CHANNEL && m.channel(to) > 0 ? meanderWidth(m, to) : mw0;
        meander(px, pz, mw0, mw1, flatness(m.slope(key)), end == INTO_CHANNEL || end == INTO_SEA ? flatness(m.slope(to)) : 0, m.seed);
        HydroClimate.Valley valley = HydroClimate.valley(s0, wet, m.slope(key), m.h(key));
        HydroClimate.Valley valleyB = end == INTO_CHANNEL && m.channel(to) > 0
                ? HydroClimate.valley(m.setting(to), m.channel(to) == 2, m.slope(to), m.h(to)) : valley;
        // River mouths open into a broad low valley (the last reach, and the end of the reach before it).
        if (end == INTO_SEA) valley = valleyB = HydroClimate.Valley.ESTUARY;
        else if (end == INTO_CHANNEL && m.down(to) == HydroModel.OCEAN_ID) valleyB = HydroClimate.Valley.ESTUARY;
        boolean mainstem = end == INTO_CHANNEL && m.mainDonor(to) == key;
        return new HydroReach(key, to, wet, mainstem, end, px, pz, w0, w1, d0, d1, valley, valleyB, m.terrain, m.seed);
    }

    /** Meanders (audit 23.11 B-8: reaches were straight between lattice nodes). A smooth seeded displacement field
     * with a wavelength of ~11 channel widths (Leopold & Wolman 1960) moves the centreline of wide rivers on flat
     * ground. Creeks get a second field with a wavelength of CREEK_WAVE widths (30-90 blocks): bends on flat ground,
     * a gentler wiggle on slopes (confined channels are less sinuous but never ruler-straight), fading out for
     * rivers 5-11 wide - creeks ran as straight lines for hundreds of blocks and showed a staircase on every
     * diagonal (player report 23.11). The fields depend only on the position, the width and the flatness, which are
     * equal for every reach at a shared node: confluences and reach joints stay put relative to each other. The
     * summed displacement gradient stays well below 1, so the line never folds. */
    static void meander(double[] px, double[] pz, double w0, double w1, double f0, double f1, long seed) {
        int n = px.length;
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : i / (double) (n - 1);
            double w = w0 + (w1 - w0) * t, flat = f0 + (f1 - f0) * t, x = px[i], z = pz[i], dx = 0, dz = 0;
            double amp = MEANDER_AMP * flat * HydroClimate.smooth(MEANDER_W0, MEANDER_W1, w);
            if (amp > 0) {
                double l = HydroClimate.clamp(11 * w, 80, 320);
                dx += amp * l * gradientNoise(seed ^ 0x6D65616EL, x / l, z / l);
                dz += amp * l * gradientNoise(seed ^ 0x64657273L, x / l, z / l);
            }
            double creek = CREEK_AMP * (CREEK_STEEP + (1 - CREEK_STEEP) * flat) * (1 - HydroClimate.smooth(CREEK_W0, CREEK_W1, w));
            if (creek > 0) {
                double l = HydroClimate.clamp(CREEK_WAVE * w, CREEK_L0, CREEK_L1);
                dx += creek * l * gradientNoise(seed ^ 0x637265656BL, x / l, z / l);
                dz += creek * l * gradientNoise(seed ^ 0x7769676CL, x / l, z / l);
            }
            px[i] = x + dx; pz[i] = z + dz;
        }
    }
    static final double MEANDER_AMP = 0.22, MEANDER_W0 = 3, MEANDER_W1 = 9;
    /** On steep ground the bends keep a quarter of their amplitude: a torrent follows the fall line, and a creek moved
     * onto the side slope of its gully had a low downhill bank (audit 24.11 V-2). */
    static final double CREEK_AMP = 0.28, CREEK_STEEP = 0.25, CREEK_W0 = 5, CREEK_W1 = 11, CREEK_WAVE = 12, CREEK_L0 = 30, CREEK_L1 = 90;
    /** Width that sets the bends at a node: the wet width of a wet node, the wash width of a dry one. */
    static double meanderWidth(HydroModel m, long key) {
        return m.channel(key) == 2 ? HydroClimate.width(m.widthK(key), m.flow(key)) : HydroClimate.dryWidth(m.widthK(key), m.area(key));
    }
    /** Flat-ground weight of a node (lowland reaches meander; hill and mountain valleys do not). */
    static double flatness(double slope) { return 1 - HydroClimate.smooth(0.010, 0.025, slope); }
    /** Smooth 2-D gradient noise in about [-0.71, 0.71] (quintic fade, seeded gradients). */
    static double gradientNoise(long seed, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz, u = fade(fx), v = fade(fz);
        double a = grad(seed, ix, iz, fx, fz), b = grad(seed, ix + 1, iz, fx - 1, fz);
        double c = grad(seed, ix, iz + 1, fx, fz - 1), d = grad(seed, ix + 1, iz + 1, fx - 1, fz - 1);
        double ab = a + (b - a) * u, cd = c + (d - c) * u;
        return ab + (cd - ab) * v;
    }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double grad(long seed, int ix, int iz, double dx, double dz) {
        double angle = (HydroModel.hash(seed, ix, iz) >>> 11) * 0x1.0p-53 * 2 * Math.PI;
        return Math.cos(angle) * dx + Math.sin(angle) * dz;
    }

    /** Centripetal Catmull-Rom from P1 to P2 (no cusps or self-intersections). */
    static void catmullRom(double x0, double z0, double x1, double z1, double x2, double z2, double x3, double z3, double[] ox, double[] oz) {
        double t0 = 0, t1 = t0 + Math.sqrt(Math.max(1e-9, Math.hypot(x1 - x0, z1 - z0)));
        double t2 = t1 + Math.sqrt(Math.max(1e-9, Math.hypot(x2 - x1, z2 - z1)));
        double t3 = t2 + Math.sqrt(Math.max(1e-9, Math.hypot(x3 - x2, z3 - z2)));
        int n = ox.length;
        for (int i = 0; i < n; i++) {
            double t = t1 + (t2 - t1) * i / (n - 1);
            double a1x = (t1 - t) / (t1 - t0) * x0 + (t - t0) / (t1 - t0) * x1, a1z = (t1 - t) / (t1 - t0) * z0 + (t - t0) / (t1 - t0) * z1;
            double a2x = (t2 - t) / (t2 - t1) * x1 + (t - t1) / (t2 - t1) * x2, a2z = (t2 - t) / (t2 - t1) * z1 + (t - t1) / (t2 - t1) * z2;
            double a3x = (t3 - t) / (t3 - t2) * x2 + (t - t2) / (t3 - t2) * x3, a3z = (t3 - t) / (t3 - t2) * z2 + (t - t2) / (t3 - t2) * z3;
            double b1x = (t2 - t) / (t2 - t0) * a1x + (t - t0) / (t2 - t0) * a2x, b1z = (t2 - t) / (t2 - t0) * a1z + (t - t0) / (t2 - t0) * a2z;
            double b2x = (t3 - t) / (t3 - t1) * a2x + (t - t1) / (t3 - t1) * a3x, b2z = (t3 - t) / (t3 - t1) * a2z + (t - t1) / (t3 - t1) * a3z;
            ox[i] = (t2 - t) / (t2 - t1) * b1x + (t - t1) / (t2 - t1) * b2x;
            oz[i] = (t2 - t) / (t2 - t1) * b1z + (t - t1) / (t2 - t1) * b2z;
        }
        ox[0] = x1; oz[0] = z1; ox[n - 1] = x2; oz[n - 1] = z2;
    }

    /** Channel width along the reach: the downstream-geometry width, varied along small channels (pools wider,
     * riffles narrower) by a seeded noise that fades out at both nodes - no constant-width canals, no seams at the
     * nodes (audit 22.11 G-2). */
    double width(double arc) {
        double t = Math.min(1, Math.max(0, arc / length)), base = w0 + (w1 - w0) * t;
        double amp = variation(base) * HydroClimate.clamp((14 - base) / 8, 0.3, 1) * Math.sin(Math.PI * t);
        return Math.max(MIN_ARC_WIDTH, base * (1 + amp * noise1(arc / 18.0)));
    }
    /** Relative width variation along a reach: WIDTH_VARIATION for rivers, more for creeks (pools and riffles). The
     * widest point, base * (1 + variation), grows with the base width, so maxWidth() bounds every reach. */
    static double variation(double base) { return WIDTH_VARIATION + CREEK_VARIATION * (1 - HydroClimate.smooth(4, 8, base)); }
    static final double WIDTH_VARIATION = 0.22, CREEK_VARIATION = 0.10, MIN_ARC_WIDTH = 2;
    private final long widthSeed;
    /** Smooth seeded 1-D value noise in [-1, 1]. */
    private double noise1(double u) {
        int i = (int) Math.floor(u); double f = u - i, sm = f * f * (3 - 2 * f);
        double a = (HydroModel.hash(widthSeed, i, 0) >>> 11) * 0x1.0p-53, b = (HydroModel.hash(widthSeed, i + 1, 0) >>> 11) * 0x1.0p-53;
        return (a + (b - a) * sm) * 2 - 1;
    }
    private double lerp(double a, double b, double arc) { return a + (b - a) * Math.min(1, Math.max(0, arc / length)); }
    double floodplainAt(double arc) { return lerp(fpA, fpB, arc); }
    double bankAt(double arc) { return lerp(bankA, bankB, arc); }
    /** Valley-side rise x blocks beyond the floodplain at an arc position. */
    double riseAt(double arc, double x) {
        if (x <= 0) return 0;
        double r = lerp(s1A, s1B, arc) * x + lerp(s2A, s2B, arc) * x * x;
        // Badlands canyons: stepped walls (ledges every TERRACE blocks) instead of one smooth slope (audit 23.11 B-9).
        if (terraced) { double u = r / TERRACE, f = u - Math.floor(u); r = TERRACE * (Math.floor(u) + HydroClimate.smooth(0.55, 1, f)); }
        return r;
    }
    static final double TERRACE = 6;
    final boolean terraced;
    int depth(double arc) { return (int) Math.round(d0 + (d1 - d0) * Math.min(1, Math.max(0, arc / length))); }
    public double bankCeiling() { return bankCeilMin; }
    /** Top water block of a wet reach at an arc position, NO_WATER for a dry one. */
    int waterLevel(HydroModel m, double arc) { return wet ? profile(m).waterAt(arc) : HydroModel.NO_WATER; }
    /** Bank ceiling at the start of the reach (the node level may not stand above it: no step at the node). */
    double startCeiling() { return bankCeil[0]; }
    public double maxWidth() { double w = Math.max(w0, w1); return w * (1 + variation(w)); }
    public double[] xs() { return px.clone(); }
    public double[] zs() { return pz.clone(); }
    public int falls(HydroModel m) { return wet ? profile(m).fallArcs.length : 0; }
    public double[] fallPoint(HydroModel m, int i) { return pointAt(profile(m).fallArcs[i]); }
    /** Drop of a waterfall in the final water profile (lip level minus plunge-pool level). */
    public int fallHeight(HydroModel m, int i) {
        Profile p = profile(m);
        return p.fallTop[i] - p.poolLevel[i];
    }

    // ---------------------------------------------------------------- projection

    /** Nearest point of the centreline: {distance, arc length} and, when out has a third slot, the side arc: the arcs of
     * the segments within SIDE_BAND blocks of the nearest distance, blended. On the inner side of a bend the nearest
     * point jumps along the river, and the valley-side profile (floor, floodplain and slopes interpolated along the
     * reach) jumped with it - 29 blocks between two columns 119 blocks out (unit test valleyCarveEndsWithoutWalls). */
    void project(double x, double z, double[] out) {
        int ns = px.length - 1;
        double[] seg = out.length > 2 ? SEGMENTS.get() : null;
        if (seg != null && seg.length < 2 * ns) { seg = new double[2 * ns + 32]; SEGMENTS.set(seg); }
        double best = Double.MAX_VALUE, arc = 0;
        for (int i = 0; i < ns; i++) {
            double ax = px[i], az = pz[i], dx = px[i + 1] - ax, dz = pz[i + 1] - az;
            double ll = dx * dx + dz * dz, t = ll < 1e-12 ? 0 : ((x - ax) * dx + (z - az) * dz) / ll;
            t = t < 0 ? 0 : t > 1 ? 1 : t;
            double qx = ax + dx * t - x, qz = az + dz * t - z, d2 = qx * qx + qz * qz, a = s[i] + (s[i + 1] - s[i]) * t;
            if (seg != null) { seg[2 * i] = d2; seg[2 * i + 1] = a; }
            if (d2 < best) { best = d2; arc = a; }
        }
        double d = Math.sqrt(best);
        out[0] = d;
        out[1] = arc;
        // Beyond the influence radius the reach is no candidate (HydroField.evaluate): no side arc needed.
        if (seg == null || d > influence) { if (out.length > 2) out[2] = arc; return; }
        double lim = (d + SIDE_BAND) * (d + SIDE_BAND), sw = 0, sa = 0;
        for (int i = 0; i < ns; i++) {
            if (seg[2 * i] >= lim) continue;
            double u = (Math.sqrt(seg[2 * i]) - d) / SIDE_BAND, w = (1 - u) * (1 - u);
            sw += w; sa += w * seg[2 * i + 1];
        }
        out[2] = sw > 0 ? sa / sw : arc;
    }
    static final double SIDE_BAND = 8;
    private static final ThreadLocal<double[]> SEGMENTS = ThreadLocal.withInitial(() -> new double[128]);
    boolean inBox(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

    // ---------------------------------------------------------------- profile

    /** Water and bed along the reach at 1-block arc resolution, plus an optional plunge pool. */
    static final class Profile {
        final int[] water;      // top water block per arc block (wet reaches)
        final double[] bed;     // bed height per arc block
        double[] floor;         // smooth valley-floor reference (>= the water 8 blocks upstream)
        double[] ramp;          // continuous water progress (top - drop * terrain-weighted progress)
        /** Waterfalls: arc of each lip, plunge pool centre/radius/depth and the pool level below it. */
        final double[] fallArcs, poolX, poolZ, poolR, poolDepth;
        final int[] poolLevel, fallTop;
        final int drop;
        /** Highest water block within REF_BACK blocks upstream (the level flowing water from a riffle can reach). */
        double[] hold;
        /** Per kept fall: a knickpoint (capped drop) rather than a cliff in the ground. */
        boolean[] knick = new boolean[0];
        Profile(int[] water, double[] bed, double[] fallArcs, double[] poolX, double[] poolZ, double[] poolR, double[] poolDepth, int[] poolLevel,
                int[] fallTop, int drop) {
            this.water = water; this.bed = bed; this.fallArcs = fallArcs;
            this.poolX = poolX; this.poolZ = poolZ; this.poolR = poolR; this.poolDepth = poolDepth; this.poolLevel = poolLevel;
            this.fallTop = fallTop; this.drop = drop;
        }
        Profile withFloor(double[] floor, double[] hold) {
            Profile p = new Profile(water, bed, fallArcs, poolX, poolZ, poolR, poolDepth, poolLevel, fallTop, drop);
            p.ramp = ramp; p.floor = floor; p.hold = hold; p.knick = knick;
            return p;
        }
        double holdAt(double arc) { return hold[Math.min(hold.length - 1, Math.max(0, (int) Math.floor(arc)))]; }
        boolean fall() { return fallArcs.length > 0; }
        /** Index of the plunge pool containing a column (below its lip, above the next lip), or -1. */
        int pool(double x, double z, double arc) {
            for (int i = 0; i < fallArcs.length; i++) {
                if (arc <= fallArcs[i] + 0.25 || (i + 1 < fallArcs.length && arc >= fallArcs[i + 1] - 0.5)) continue;
                if (poolDistance(i, x, z) < 1) return i;
            }
            return -1;
        }
        /** Distance from the pool centre relative to its irregular rim (scoured bowls are not circles). */
        double poolDistance(int i, double x, double z) {
            double dx = x - poolX[i], dz = z - poolZ[i], th = Math.atan2(dz, dx), ph = poolX[i] * 0.37 + poolZ[i] * 0.61;
            double rim = poolR[i] * (1 + 0.16 * Math.sin(3 * th + ph) + 0.09 * Math.sin(5 * th + 2.3 * ph));
            return Math.hypot(dx, dz) / rim;
        }
        int waterAt(double arc) { return water[Math.min(water.length - 1, Math.max(0, (int) Math.floor(arc)))]; }
        double floorAt(double arc) {
            double a = Math.max(0, Math.min(floor.length - 1, arc));
            int i = (int) Math.floor(a);
            if (i >= floor.length - 1) return floor[floor.length - 1];
            return floor[i] + (floor[i + 1] - floor[i]) * (a - i);
        }
        double bedAt(double arc) { return bed[Math.min(bed.length - 1, Math.max(0, (int) Math.floor(arc)))]; }
    }

    Profile profile(HydroModel m) {
        Profile p = profile;
        if (p != null) return p;
        if (wet) { Profile c = core(m); double[][] tail = upstreamTail(m); p = c.withFloor(envelope(c.water, c.ramp, tail), hold(c.water, tail)); }
        else p = dryProfile(m);
        profile = p;
        return p;
    }
    /** Water, ramp and falls (no valley-floor reference): depends on node levels and geometry only. */
    Profile core(HydroModel m) {
        Profile c = core;
        if (c != null) return c;
        c = wetCore(m);
        core = c;
        return c;
    }

    /** Wet reach downstream of this one (a channel continuation), or null. */
    HydroReach next(HydroModel m) {
        if (end != INTO_CHANNEL || m.channel(to) != 2) return null;
        HydroReach n = m.reach(to);
        return n != null && n.wet ? n : null;
    }
    /** Target level at the end of the reach: the receiving node, lake or sea - and never above the first water of
     * the next reach, whose first block can already sit on the floor of a receiving valley (confluence ceilings):
     * the drop then belongs to this reach (riffles or a fall), not to an unplanned step at the node. */
    private double endLevel(HydroModel m, boolean useNext) {
        double wf = m.water(from), wt;
        // Into a sink (a rift, a lava crater) from outside: the water keeps its level to the sink's edge and falls
        // there; a profile down to the rift floor carved a trench 40 blocks deep before the brink (seed 31337 X-7470
        // Z1400: water 51 beside ground 91).
        if (intoSink(m)) return wf;
        switch (end) {
            case INTO_CHANNEL -> {
                wt = m.channel(to) == 2 ? m.water(to) : wf;
                HydroReach next = useNext ? next(m) : null;
                if (next != null) wt = Math.min(wt, next.startLevel(m));
            }
            case INTO_LAKE -> { double r = m.receivingLevel(from); wt = Double.isInfinite(r) ? wf : r; }
            case INTO_SEA -> wt = HydroModel.SEA_WATER;
            default -> wt = wf;
        }
        return Math.min(wt, wf);
    }
    /** First water block given the end target (the first step of the core profile loop). */
    private int firstWater(HydroModel m, double wf, int bottom, double extra0) {
        double ceiling = Math.min(bankCeil[0], extra0);
        boolean held = end == INTO_LAKE || end == INTO_SEA;
        int w = (int) Math.floor(wf + 1e-9);
        w = (int) Math.min(w, held ? Math.max(bottom, Math.floor(ceiling)) : Math.floor(ceiling));
        return Math.max(w, end == TERMINAL ? Integer.MIN_VALUE / 2 : bottom);
    }
    /** Arc where the reach enters a sink of the landforms (a rift, a lava crater), NaN until measured. */
    private volatile double sinkArc = Double.NaN;
    /** Arc where the reach enters a sink of the landforms: +infinity when it never does, 0 when it starts inside one.
     * Columns outside the sink see the reach only up to there (HydroField.evaluate): its drop into a rift is the rift's
     * own wall, not a valley carved beside the rift down to its floor - those valleys lay below the sea level, filled
     * with sea water and stood as half-moon walls of water against the dry rift (seed 31337 X-6980 Z1340). Measured
     * every SINK_STEP blocks along the centreline. */
    double sinkArc(HydroModel m) {
        double a = sinkArc;
        if (!Double.isNaN(a)) return a;
        a = Double.POSITIVE_INFINITY;
        for (double t = 0; t <= length; t += SINK_STEP) {
            double[] q = pointAt(t);
            if (m.inputs.sink((int) Math.floor(q[0]), (int) Math.floor(q[1]))) { a = t; break; }
        }
        if (a == Double.POSITIVE_INFINITY && length > 0) {
            double[] q = pointAt(length);
            if (m.inputs.sink((int) Math.floor(q[0]), (int) Math.floor(q[1]))) a = length;
        }
        sinkArc = a;
        return a;
    }
    static final double SINK_STEP = 2;
    /** Plunge pool at the foot of the wall where the reach falls into a sink: {x, z, radius, water level}, an empty
     * array when there is none, null until measured. */
    private volatile double[] riftPool;
    private static final double[] NO_POOL = new double[0];
    /** The pool of a wet reach falling into a sink (a rift): POOL_IN blocks inside the wall on its centreline, POOL_R
     * plus half the channel width across, water one block under the lowest ground round it (the floor holds it), or
     * null. The water of the waterfall lands in it instead of spreading over the dry floor. */
    double[] riftPool(HydroModel m) {
        double[] p = riftPool;
        if (p == null) {
            p = NO_POOL;
            double sa = sinkArc(m);
            if (wet && intoSink(m) && sa > 0 && sa < length) {
                double[] c = pointAt(Math.min(length, sa + POOL_IN));
                double r = POOL_R + width(sa) / 2, low = Double.POSITIVE_INFINITY;
                for (int k = 0; k < 16; k++) {
                    double a = k * Math.PI / 8;
                    low = Math.min(low, m.terrain.at((int) Math.floor(c[0] + Math.cos(a) * (r + 1.5)), (int) Math.floor(c[1] + Math.sin(a) * (r + 1.5))));
                }
                double level = Math.floor(low) - 1;
                // A river of the sink's own drainage passing the pool: the pool takes its water level and is one water
                // body with it (a pool above it drained into its channel: seed 424242 X-7796 Z-1166, 309 blocks).
                double[] pr = new double[3];
                for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
                    HydroReach q = m.reach(HydroModel.nodeAt((int) Math.floor(c[0]) + di * HydroModel.SPACING, (int) Math.floor(c[1]) + dj * HydroModel.SPACING));
                    if (q == null || q == this || !q.wet || !q.fromSink(m)) continue;
                    q.project(c[0], c[1], pr);
                    if (pr[0] < r + 4) level = Math.min(level, q.waterLevel(m, pr[1]));
                }
                if (level > HydroModel.SEA_LEVEL - 60) p = new double[]{c[0], c[1], r, level};
            }
            riftPool = p;
        }
        return p.length == 0 ? null : p;
    }
    static final double POOL_IN = 3, POOL_R = 5, POOL_DEPTH = 2;
    /** Whether the reach's nodes lie in a sink: bit 1 the upstream node, bit 2 the downstream one; -1 until measured. */
    private volatile int sinkNodes = -1;
    private int sinkNodes(HydroModel m) {
        int v = sinkNodes;
        if (v >= 0) return v;
        v = 0;
        if (from != HydroModel.NONE && m.inputs.sink(m.x(from), m.z(from))) v |= 1;
        if (to != HydroModel.NONE && m.inputs.sink(m.x(to), m.z(to))) v |= 2;
        sinkNodes = v;
        return v;
    }
    /** A reach of a sink's own drainage (its upstream node lies in the sink): seen only from inside the sink. */
    boolean fromSink(HydroModel m) { return (sinkNodes(m) & 1) != 0; }
    /** A reach from outside into a sink. */
    boolean intoSink(HydroModel m) { return sinkNodes(m) == 2; }
    private volatile boolean startKnown;
    private volatile int start;
    /** First water block of this wet reach without building its profile. Computed iteratively from the far end of
     * the chain of unknown starts downstream (no recursion along a river, whatever its length). */
    int startLevel(HydroModel m) {
        if (startKnown) return start;
        java.util.ArrayList<HydroReach> chain = new java.util.ArrayList<>();
        java.util.HashSet<HydroReach> seen = new java.util.HashSet<>();
        boolean cycle = false;
        for (HydroReach cur = this; cur != null && !cur.startKnown; cur = cur.next(m)) {
            if (!seen.add(cur)) { cycle = true; break; } // defensive: the flow graph has no cycles
            chain.add(cur);
        }
        for (int i = chain.size() - 1; i >= 0; i--) chain.get(i).computeStart(m, !(cycle && i == chain.size() - 1));
        return start;
    }
    private void computeStart(HydroModel m, boolean useNext) {
        if (startKnown) return;
        double wf = m.water(from), wt = endLevel(m, useNext);
        int bottom = (int) Math.floor(wt);
        start = firstWater(m, wf, bottom, neighbourCeilings(m, bottom)[0]);
        startKnown = true;
    }

    private Profile wetCore(HydroModel m) {
        double wf = m.water(from), wt = endLevel(m, true);
        int top = (int) Math.floor(wf), bottom = (int) Math.floor(wt);
        int blocks = (int) Math.ceil(length) + 1;
        int k = top - bottom;
        // Terrain-weighted progress of the drop along the reach: riffles where the ground descends most, or a
        // single fall where the ground has a cliff that can take most of the drop.
        double[] cumDrop = new double[center.length];
        double totalDrop = 0;
        for (int i = 1; i < center.length; i++) { totalDrop += Math.max(0, center[i - 1] - center[i]); cumDrop[i] = totalDrop; }
        double[] fallArcs = new double[0], fallDrop = new double[0];
        boolean[] fallKnick = new boolean[0];
        double[][] faceWindows = null;
        double drop = wf - wt;
        double[] extra = neighbourCeilings(m, bottom);
        if (k >= 3) {
            int bi = -1, bj = -1; double bestDrop = 0;
            for (int i = 0; i < center.length; i++) for (int j = i + 1; j < center.length && s[j] - s[i] <= 6; j++) {
                double d = center[i] - center[j];
                if (d > bestDrop) { bestDrop = d; bi = i; bj = j; }
            }
            double[][] faces;
            // A cliff in the ground takes the whole drop (the ground below it is already low).
            if (bestDrop >= 3 && bestDrop >= 0.6 * k) { fallArcs = new double[]{(s[bi] + s[bj]) / 2}; fallDrop = new double[]{drop}; fallKnick = new boolean[1]; }
            else if (k >= FACE_MIN && (faces = steepFaces(drop, reachable(extra))).length > 0) {
                // Steep faces (user 26.11, "I did not notice big waterfalls"): where the ground under the channel falls at
                // least FACE_SLOPE blocks per block over up to FACE_SPAN blocks, the stream leaves the top of the face as
                // one waterfall taking the drop of the face; the water below it runs in a short gorge down the face.
                // Before, a mountain stream losing 25-54 blocks on a 30-50-block reach ran down the face as riffles
                // (no cliff within 6 blocks), and a knickpoint took at most 3 + width blocks: 0 falls of 12+ blocks
                // among 180 such reaches (seed 31337, 6 x 6 km). The lip sits at the top of the face: below it the ground
                // is lower than the water above it, so a lip further down would be a level standing above its banks.
                fallArcs = new double[faces.length]; fallDrop = new double[faces.length]; fallKnick = new boolean[faces.length];
                for (int f = 0; f < faces.length; f++) { fallArcs[f] = faces[f][0]; fallDrop[f] = faces[f][1]; }
                faceWindows = faces;
            }
            else {
                // Knickpoint on a resistant rock step early in the reach. It takes at most KNICK_BASE + KNICK_WIDTH x
                // width blocks of the drop; the rest runs as riffles along the ground below it, so the river below
                // runs in a gorge a fall-height deep. An uncapped step took all of it: a creek 2.4 wide fell 35 blocks
                // into a pit 40 blocks deep in a slope with no cliff (seed 42 X254 Z-1566).
                long hsh = HydroModel.hash(m.seed ^ 0x4B1C, HydroModel.ni(from), HydroModel.nj(from));
                double u1 = (hsh >>> 11) * 0x1.0p-53, u2 = ((hsh * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
                if (u1 < knickChance(m.setting(from)) * Math.min(1, (k - 2) / 3.0)) {
                    double cap = KNICK_BASE + KNICK_WIDTH * Math.max(w0, w1);
                    if (k >= 10 && u2 < 0.5) {
                        fallArcs = new double[]{length * (0.15 + 0.12 * u2 * 2), length * (0.50 + 0.15 * u2 * 2)};
                        double each = Math.min(cap, drop / 2);
                        fallDrop = new double[]{each, each};
                        fallKnick = new boolean[]{true, true};
                    } else {
                        fallArcs = new double[]{length * (0.15 + 0.25 * u2)};
                        fallDrop = new double[]{Math.min(cap, drop)};
                        fallKnick = new boolean[]{true};
                    }
                }
            }
        }
        boolean fall = fallArcs.length > 0;
        double planned = 0;
        for (double d : fallDrop) planned += d;
        double rest = Math.max(0, drop - planned);
        if (faceWindows != null) {
            // The drop left to riffles runs where the ground descends outside the faces (they took theirs).
            totalDrop = 0;
            for (int i = 1; i < center.length; i++) {
                double mid = (s[i - 1] + s[i]) / 2;
                boolean inFace = false;
                for (double[] fw : faceWindows) inFace |= mid >= fw[2] && mid <= fw[3];
                if (!inFace) totalDrop += Math.max(0, center[i - 1] - center[i]);
                cumDrop[i] = totalDrop;
            }
        }
        // Continuous ramp from the exact upstream level to the exact downstream level; the water is its floor,
        // so riffles fall where the ramp crosses whole levels and the water never stands above the ramp. The drop not
        // taken by falls is spread by the terrain-weighted progress.
        double[] ramp = new double[blocks];
        for (int b = 0; b < blocks; b++) {
            double arc = Math.min(length, b), prog;
            if (totalDrop < 1e-6) prog = arc / length;
            else {
                int i = 1;
                while (i < s.length - 1 && s[i] < arc) i++;
                double f = s[i] - s[i - 1] < 1e-9 ? 1 : (arc - s[i - 1]) / (s[i] - s[i - 1]);
                prog = (cumDrop[i - 1] + (cumDrop[i] - cumDrop[i - 1]) * Math.max(0, Math.min(1, f))) / totalDrop;
            }
            double r = wf - rest * prog;
            for (int f = 0; f < fallArcs.length; f++) if (arc >= fallArcs[f]) r -= fallDrop[f];
            ramp[b] = r;
        }
        int[] water = new int[blocks];
        double[] bed = new double[blocks];
        double ceiling = Double.POSITIVE_INFINITY;
        // Receiving lakes and the sea hold the level up (backwater); a terminal reach may sink freely.
        boolean held = end == INTO_LAKE || end == INTO_SEA;
        int floorLevel = end == TERMINAL ? Integer.MIN_VALUE / 2 : bottom;
        int sample = 0;
        for (int b = 0; b < blocks; b++) {
            double arc = Math.min(length, b);
            while (sample < s.length && s[sample] <= arc + 1e-9) { ceiling = Math.min(ceiling, Math.min(bankCeil[sample], extra[sample])); sample++; }
            int w = (int) Math.floor(ramp[b] + 1e-9);
            w = (int) Math.min(w, held ? Math.max(bottom, Math.floor(ceiling)) : Math.floor(ceiling));
            w = Math.max(w, floorLevel);
            if (b > 0) w = Math.min(w, water[b - 1]);
            water[b] = w;
            bed[b] = w - depth(arc);
        }
        // Falls are kept only where the final water (after bank and confluence ceilings) still drops at the lip; the
        // plunge pool is sized from that real drop.
        int nf = 0;
        double[] keptArcs = new double[fallArcs.length];
        int[] tops = new int[fallArcs.length], lows = new int[fallArcs.length];
        boolean[] keptKnick = new boolean[fallArcs.length];
        for (int f = 0; f < fallArcs.length; f++) {
            double arc = fallArcs[f];
            int lip = (int) Math.ceil(arc);
            int above = water[Math.max(0, Math.min(blocks - 1, lip - 1))], below = water[Math.min(blocks - 1, lip + 1)];
            if (above - below < MIN_FALL) continue;
            keptArcs[nf] = arc; tops[nf] = above; lows[nf] = below; keptKnick[nf] = fallKnick[f]; nf++;
        }
        double[] arcs = java.util.Arrays.copyOf(keptArcs, nf);
        double[] poolX = new double[nf], poolZ = new double[nf], poolR = new double[nf], poolDepth = new double[nf];
        int[] poolLevel = java.util.Arrays.copyOf(lows, nf), fallTop = java.util.Arrays.copyOf(tops, nf);
        for (int f = 0; f < nf; f++) {
            int height = fallTop[f] - poolLevel[f];
            poolR[f] = Math.min(10, Math.max(3, maxWidth() / 2 + 2 + height / 6.0));
            poolDepth[f] = Math.min(7, Math.max(2, 2 + height / 4.0));
            // The rim is irregular (down to 0.75 of the radius): the centre sits closer than the smallest rim, so the
            // pool always reaches the lip - no wall left between the lip and the pool for the falling water to spill
            // around (seed 42 gorge after the alpine streams of audit 23.11 B-6).
            double arc = Math.min(length, arcs[f] + 0.72 * poolR[f] - 1);
            double[] at = pointAt(arc); poolX[f] = at[0]; poolZ[f] = at[1];
        }
        Profile result = new Profile(water, bed, arcs, poolX, poolZ, poolR, poolDepth, poolLevel, fallTop, k);
        result.ramp = ramp; result.knick = java.util.Arrays.copyOf(keptKnick, nf);
        return result;
    }
    /** Highest ground the water can follow along the centreline: the natural ground, but never more than a block over
     * the lowest bank or neighbour ceiling met so far (the water profile never rises above that running minimum).
     * A face of the natural ground below a receiving valley's carved side is no face for the water: the stream runs
     * down that side in steps (seed 31337 X1666 Z-548: a 39-block face, the water held 28 blocks lower from the start). */
    double[] reachable(double[] extra) {
        double[] g = new double[center.length];
        double run = Double.POSITIVE_INFINITY;
        for (int i = 0; i < center.length; i++) {
            run = Math.min(run, Math.min(bankCeil[i], extra[i]));
            g[i] = Math.min(center[i], run + 1);
        }
        return g;
    }
    /** Steep faces of the ground along the centreline, greedily by drop: {lip arc, fall drop, face top arc, face
     * bottom arc}, sorted by arc. A face falls at least FACE_SLOPE blocks per block over at most FACE_SPAN blocks
     * and at least FACE_MIN blocks; its lip is the top of the face (flat ground above it is trimmed off, so the
     * water never drops on the flat before the face), at least FACE_EDGE blocks from the start of the reach. The
     * falls together take at most the drop of the reach. */
    double[][] steepFaces(double drop, double[] ground) {
        int n = ground.length;
        boolean[] used = new boolean[n];
        java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        double remaining = drop;
        for (int f = 0; f < FACE_MAX && remaining >= FACE_MIN; f++) {
            int bi = -1, bj = -1; double bd = 0;
            for (int i = 0; i < n; i++) {
                if (s[i] < FACE_EDGE || used[i]) continue;
                for (int j = i + 1; j < n && s[j] - s[i] <= FACE_SPAN; j++) {
                    if (used[j]) break;
                    double d = ground[i] - ground[j];
                    if (d >= FACE_MIN && d >= FACE_SLOPE * (s[j] - s[i]) && d > bd) { bd = d; bi = i; bj = j; }
                }
            }
            if (bi < 0) break;
            // The face proper: no flat run at its top or bottom.
            while (bi + 1 < bj && ground[bi] - ground[bi + 1] < 0.5 * FACE_SLOPE * (s[bi + 1] - s[bi])) bi++;
            while (bj - 1 > bi && ground[bj - 1] - ground[bj] < 0.5 * FACE_SLOPE * (s[bj] - s[bj - 1])) bj--;
            double d = Math.min(remaining, ground[bi] - ground[bj]);
            for (int q = 0; q < n; q++) if (s[q] >= s[bi] - FACE_GAP && s[q] <= s[bj] + FACE_GAP) used[q] = true;
            if (d < FACE_MIN) continue;
            out.add(new double[]{Math.min(length - 1, s[bi] + 1), d, s[bi], s[bj]});
            remaining -= d;
        }
        out.sort((a, b) -> Double.compare(a[0], b[0]));
        return out.toArray(new double[0][]);
    }
    /** Steep faces: at least FACE_MIN blocks at FACE_SLOPE blocks per block or steeper over at most FACE_SPAN
     * blocks of channel; up to FACE_MAX per reach, FACE_GAP blocks apart. */
    static final double FACE_MIN = 10, FACE_SLOPE = 1.0, FACE_SPAN = 20, FACE_EDGE = 2, FACE_GAP = 8;
    static final int FACE_MAX = 3;
    /** Smallest drop (blocks) kept as a waterfall with a plunge pool; smaller steps are riffles. */
    static final int MIN_FALL = 2;
    /** Largest knickpoint fall: KNICK_BASE + KNICK_WIDTH blocks per block of channel width (6 for a creek, 13 for a
     * river 10 wide). Cliffs in the ground keep their full height. */
    static final double KNICK_BASE = 3, KNICK_WIDTH = 1.0;

    /** Last REF_BACK values {water, ramp} of the main upstream reach (continuity of the valley floor at the node). */
    private double[][] upstreamTail(HydroModel m) {
        long md = m.mainDonor(from);
        if (md == HydroModel.NONE || m.channel(md) != 2) {
            // A river leaving a lake through its outlet channel: its first banks follow the outlet's water.
            HydroOutlet o = m.outletInto(from);
            return o != null ? o.tail() : null;
        }
        HydroReach up = m.reach(md);
        if (up == null || !up.wet || up.to != from) return null;
        Profile p = up.core(m);
        int back = (int) REF_BACK;
        double[][] tail = new double[2][back];
        for (int i = 0; i < back; i++) {
            int at = Math.max(0, p.water.length - back + i);
            tail[0][i] = p.water[at];
            tail[1][i] = p.ramp != null ? p.ramp[at] : p.water[at];
        }
        return tail;
    }

    /** Smooth, continuous, non-increasing valley-floor reference through the upper corners of the water steps
     * delayed by REF_BACK blocks: never below the water within the last 8 blocks upstream (flowing water from a
     * riffle stays inside the banks), but the floodplain does not terrace with every riffle. */
    static double[] envelope(int[] water, double[] ramp, double[][] tail) {
        int n = water.length, back = (int) REF_BACK;
        double[] e = new double[n];
        for (int b = 0; b < n; b++) {
            int ref = b - back >= 0 ? water[b - back] : tail != null ? (int) Math.max(water[0], tail[0][b]) : water[0];
            double r = b - back >= 0 ? ramp[b - back] : tail != null ? Math.max(ramp[0], tail[1][b]) : ramp[0];
            // The water is the floor of the ramp, so the delayed ramp is never below the water within the last
            // REF_BACK blocks; it is continuous across nodes (the floodplain follows the valley slope).
            e[b] = Math.max(ref, r + 0.1);
        }
        // Below a fall the floor comes down at most FLOOR_FALL blocks per block of channel. Delayed by REF_BACK, a fall of
        // 22 blocks dropped the whole valley floor beside the stream at once, 8 blocks below the lip where the stream
        // itself ran level: a dry cliff 11-18 blocks high between two columns of the valley side, the flat floor below
        // it cut 20-28 blocks into the mountain flank (OpenAI random6 audit 26.11: seed 1243023250 X-842 Z-874, Y216 next
        // to Y198). The floor only rises by this: the inner gorge (SIDE_ABOVE, NOTCH) carves the walls beside the water.
        for (int b = 1; b < n; b++) e[b] = Math.max(e[b], e[b - 1] - FLOOR_FALL);
        return e;
    }
    /** Steepest descent of the valley floor reference along the channel (blocks per block). */
    static final double FLOOR_FALL = 1.0;
    /** The water block REF_BACK blocks upstream (the upstream reach's tail over the first blocks): the lowest bank top
     * that still holds the flowing water of a riffle step. */
    static double[] hold(int[] water, double[][] tail) {
        int n = water.length, back = (int) REF_BACK;
        double[] h = new double[n];
        for (int b = 0; b < n; b++) h[b] = b - back >= 0 ? water[b - back] : tail != null ? Math.max(water[0], tail[0][b]) : water[0];
        return h;
    }

    /** Ceilings from the receiving river at a confluence (the downstream reach and larger sibling tributaries):
     * where this reach's bank would lie on the receiving valley floor or in its channel, the water has to be
     * down at the receiving level already - the drop happens at the valley edge (cascade or hanging falls). */
    private double[] neighbourCeilings(HydroModel m, int bottom) {
        double[] out = new double[px.length];
        java.util.Arrays.fill(out, Double.POSITIVE_INFINITY);
        if (end != INTO_CHANNEL || m.channel(to) != 2 || intoSink(m)) return out;
        java.util.ArrayList<HydroReach> receivers = new java.util.ArrayList<>();
        HydroReach downstream = m.reach(to);
        if (downstream != null && downstream.wet) receivers.add(downstream);
        for (long sib : m.wetSiblings(to, from)) {
            HydroReach r = m.reach(sib);
            if (r != null && r.wet && m.area(sib) >= m.area(from)) receivers.add(r);
        }
        if (receivers.isEmpty()) return out;
        double[] pr = new double[2];
        for (int i = 0; i < px.length; i++) {
            double tx = px[Math.min(px.length - 1, i + 1)] - px[Math.max(0, i - 1)], tz = pz[Math.min(px.length - 1, i + 1)] - pz[Math.max(0, i - 1)];
            double tl = Math.max(1e-9, Math.hypot(tx, tz)), nx = -tz / tl, nz = tx / tl;
            double half = width(s[i]) / 2 + BANK_OFFSET;
            for (int side = -1; side <= 1; side += 2) {
                double bx = px[i] + side * nx * half, bz = pz[i] + side * nz * half;
                for (HydroReach q : receivers) {
                    if (bx < q.minX || bx > q.maxX || bz < q.minZ || bz > q.maxZ) continue;
                    q.project(bx, bz, pr);
                    // The part of a receiver inside a sink (a rift floor) carves nothing outside it (HydroField.evaluate).
                    if (pr[1] >= q.sinkArc(m) && !m.inputs.sink((int) Math.floor(bx), (int) Math.floor(bz))) continue;
                    double qHalf = q.width(pr[1]) / 2, xb = pr[0] - qHalf, c;
                    if (xb <= 0) c = bottom;
                    else {
                        double qfp = q.floodplainAt(pr[1]), qb = q.bankAt(pr[1]);
                        double zq = xb <= qfp ? bottom + Math.min(0.4 + 1.6 * xb, qb + 0.4) : bottom + qb + 0.4 + q.riseAt(pr[1], xb - qfp);
                        c = zq - 1 - 1e-6;
                    }
                    out[i] = Math.min(out[i], Math.max(bottom, c));
                }
            }
        }
        return out;
    }

    private Profile dryProfile(HydroModel m) {
        double b0 = m.dryBed(from), b1;
        if (end == INTO_CHANNEL && m.channel(to) == 1) b1 = m.dryBed(to);
        else if (end == INTO_CHANNEL && m.channel(to) == 2) b1 = m.water(to) - HydroClimate.depth(m.depthK(to), m.flow(to));
        else b1 = b0 - 1;
        // A dry wash into a sink keeps its level to the sink's edge, as the water of a wet one does (endLevel): its bed
        // went down to the rift floor before the brink and cut the ground beside the rift below the sea level, where
        // the native fill stood as sea water at Y62 over the dry rift (seed 1243023250 X10958 Z2580: ground 92 cut to
        // 61, X407 Z-9807: 114 to 63).
        if (intoSink(m)) b1 = b0 - 1;
        b1 = Math.min(b0, b1);
        int blocks = (int) Math.ceil(length) + 1;
        double[] bed = new double[blocks];
        for (int b = 0; b < blocks; b++) bed[b] = b0 + (b1 - b0) * Math.min(1, b / length);
        return new Profile(new int[blocks], bed, new double[0], new double[0], new double[0], new double[0], new double[0], new int[0], new int[0], 0);
    }

    double[] pointAt(double arc) {
        int i = 0;
        while (i + 1 < s.length - 1 && s[i + 1] < arc) i++;
        double f = s[i + 1] - s[i] < 1e-9 ? 0 : (arc - s[i]) / (s[i + 1] - s[i]);
        f = Math.max(0, Math.min(1, f));
        return new double[]{px[i] + (px[i + 1] - px[i]) * f, pz[i] + (pz[i + 1] - pz[i]) * f};
    }

    // ---------------------------------------------------------------- cross-section

    /** Carved ground height at a column given its distance to the centreline and arc position, and the water
     * level the reach provides there (NO_WATER outside the channel and pool). Writes {carve, water, inChannel, inPool,
     * hold}: hold is the highest water within REF_BACK blocks upstream (above the water in a riffle zone).
     * bars: point bars may lower the inner bank (no other water body's channel nearby). */
    void evaluate(HydroModel m, double x, double z, double d, double arc, double[] out, boolean bars) {
        evaluate(m, x, z, d, arc, arc, out, bars);
    }
    /** As above, with the valley side (beyond the channel) shaped by the side arc of project. */
    void evaluate(HydroModel m, double x, double z, double d, double arc, double sideArc, double[] out, boolean bars) {
        Profile p = profile(m);
        double half = width(arc) / 2, carve;
        // A terminal channel closes: it narrows to nothing over its last blocks (contained end pool).
        if (end == TERMINAL) half *= Math.max(0, Math.min(1, (length - arc) / 4));
        int water = HydroModel.NO_WATER;
        boolean channel = false, inPool = false;
        out[4] = HydroModel.NO_WATER;
        if (wet) {
            double at = arc;
            int w = p.waterAt(at);
            out[4] = p.holdAt(at);
            double ref = p.floorAt(at);
            double bed = p.bedAt(at);
            // A closed terminal end has no channel left (half = 0): its column is valley floor, never 0/0.
            if (half > MIN_HALF && d <= half) {
                double u = d / half;
                carve = bed + (w + 0.4 - bed) * u * u;
                water = w; channel = true;
            } else {
                double xb = d - half, fpa = floodplainAt(sideArc), bank = bankAt(sideArc), side = sideArc == arc ? ref : p.floorAt(sideArc);
                carve = xb <= fpa ? side + Math.min(0.4 + 1.6 * xb, bank + 0.4) : side + bank + 0.4 + riseAt(sideArc, xb - fpa);
                // On a steep cascade the floor reference (the water REF_BACK blocks upstream) stands far above the local
                // water, and the channel ran in a slot with vertical walls 14-26 blocks high (audits 25.11 Fable M-10,
                // OpenAI, Opus: seed 314159265 X-256 Z2400, a stream falling 1.3 blocks per block). An inner gorge opens
                // from SIDE_ABOVE blocks over the local water, its walls rising NOTCH blocks per block until they meet the
                // valley side (the floor itself stays: a lower stretch of a winding stream must not cut under the upper
                // one). The containment rule (HydroField.column) holds a riffle's water with banks up to two blocks over it.
                double local = (sideArc == arc ? w : p.waterAt(sideArc)) + SIDE_ABOVE;
                if (side > local) carve = Math.min(carve, local + 0.4 + amphitheatre(p, x, z, arc) * xb);
                // Point bars (player report 23.11: creeks ran between two vertical banks everywhere, like a dug ditch).
                // On the inner side of a bend the bank rises gently from a shelf level with the water - a gravel or
                // grass bar - while the outer bank stays a cut bank. The bar never drops below the water REF_BACK
                // blocks upstream plus 0.4 (a bank top at that block): the flowing water of a riffle step still
                // stays in the channel.
                if (bars && xb <= fpa && half > MIN_HALF) {
                    double inner = innerBend(x, z, arc);
                    if (inner > 0) {
                        double bar = p.holdAt(at) + 0.4 + xb / (BAR_RUN + BAR_PER_WIDTH * 2 * half);
                        carve += (Math.min(carve, bar) - carve) * inner;
                    }
                }
            }
            // Below a lip only: the pool hugs the cliff but never removes the lip itself.
            int pi = p.pool(x, z, at);
            if (pi >= 0) {
                double q = p.poolDistance(pi, x, z);
                double pool = p.poolLevel[pi] - p.poolDepth[pi] * (1 - q * q);
                if (pool < carve) carve = pool;
                water = p.poolLevel[pi]; channel = true; inPool = true;
            }
        } else {
            double bed = p.bedAt(arc);
            if (half > MIN_HALF && d <= half) { double u = d / half; carve = bed + 0.2 * u * u * u * u; channel = true; }
            else {
                double xb = d - half, side = sideArc == arc ? bed : p.bedAt(sideArc);
                carve = side + 0.2 + Math.min(1.8 * xb, bankAt(sideArc)) + riseAt(sideArc, xb - floodplainAt(sideArc));
            }
        }
        out[0] = carve; out[1] = water; out[2] = channel ? 1 : 0; out[3] = inPool ? 1 : 0;
    }

    /** Wall slope of the inner gorge at a column (blocks per block): NOTCH, gentler round the plunge pool of a waterfall
     * below its lip - an amphitheatre opening downward instead of a slot with near-vertical walls (audit 26.11 Opus N-8;
     * Lamb & Dietrich 2009). Its radius grows with the height of the fall and wanders a little round the pool. */
    static double amphitheatre(Profile p, double x, double z, double arc) {
        double notch = NOTCH;
        for (int f = 0; f < p.fallArcs.length; f++) {
            if (arc < p.fallArcs[f] + 0.5) continue;
            double dx = x - p.poolX[f], dz = z - p.poolZ[f], dist = Math.hypot(dx, dz);
            int height = p.fallTop[f] - p.poolLevel[f];
            double wander = 1 + 0.18 * Math.sin(3 * Math.atan2(dz, dx) + p.poolX[f] * 0.37);
            double reach = (p.poolR[f] + Math.max(4, AMPH_SHARE * height)) * wander;
            if (dist >= reach) continue;
            double t = HydroClimate.smooth(p.poolR[f], reach, dist);
            notch = Math.min(notch, AMPH_NOTCH + (NOTCH - AMPH_NOTCH) * t);
        }
        return notch;
    }
    /** Amphitheatre wall slope next to the pool and its extent beyond the pool as a share of the fall height. */
    static final double AMPH_NOTCH = 1.25, AMPH_SHARE = 0.45;

    boolean connected(HydroReach o) { return to == o.to || to == o.from || o.to == from; }
    /** Whether o continues this river at a node (the reach downstream of which this one is the main donor, or the
     * main donor reach upstream of this one): the same water profile, not a tributary or a sibling. */
    boolean sameRiver(HydroReach o) { return (to == o.from && mainstem) || (o.to == from && o.mainstem); }

    /** 0..1: how much a column lies on the inner side of a bend (signed curvature of the centreline at the arc,
     * BAR_K0..BAR_K1 radians per block, toward the column's side). */
    double innerBend(double x, double z, double arc) {
        int lo = 0, hi = s.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (s[mid] <= arc) lo = mid; else hi = mid; }
        double sl = s[hi] - s[lo], f = sl < 1e-9 ? 0 : Math.max(0, Math.min(1, (arc - s[lo]) / sl));
        double tx = px[hi] - px[lo], tz = pz[hi] - pz[lo];
        double cx = px[lo] + tx * f, cz = pz[lo] + tz * f;
        double side = tx * (z - cz) - tz * (x - cx);
        double k = bend[lo] + (bend[hi] - bend[lo]) * f;
        return HydroClimate.smooth(BAR_K0, BAR_K1, Math.signum(side) * k);
    }
    static final double BAR_K0 = 0.003, BAR_K1 = 0.015, BAR_RUN = 2, BAR_PER_WIDTH = 0.5;

    // ---------------------------------------------------------------- pockets

    /** Closed hollows beside a channel whose water stands above them (a river held at the level of a lake further
     * down, crossing low ground the lattice does not resolve): the water fills them in the game anyway, as a spill
     * of flowing water (audit of this round: seed 42 X-3531 Z-3420, a stream held at 72 beside a pocket at 69.5,
     * 163 blocks after the ticks). They are generated as still ponds at the channel level instead. A pocket is a
     * 4-connected set of columns whose natural ground is at or below the water, touching the channel, within
     * POCKET_REACH blocks of it, under a constant water level, and closed: a low column farther out means an open
     * hollow (the water would leave), which is left as it is. Computed once per reach (geometry and levels only),
     * the same whatever column asks first. */
    static final class Pockets {
        static final Pockets NONE = new Pockets(0, 0, 0, 0, new long[0], HydroModel.NO_WATER);
        final int x0, z0, w, h, level;
        final long[] bits;
        Pockets(int x0, int z0, int w, int h, long[] bits, int level) { this.x0 = x0; this.z0 = z0; this.w = w; this.h = h; this.bits = bits; this.level = level; }
        int levelAt(int x, int z) {
            int a = x - x0, b = z - z0;
            if (a < 0 || b < 0 || a >= w || b >= h) return HydroModel.NO_WATER;
            int i = a + w * b;
            return (bits[i >>> 6] & (1L << (i & 63))) != 0 ? level : HydroModel.NO_WATER;
        }
    }
    static final int POCKET_REACH = 24, POCKET_MAX = 4000;

    /** Water level of a pocket pond at a column, or NO_WATER. */
    int pocketLevel(HydroModel m, int x, int z) {
        if (!wet) return HydroModel.NO_WATER;
        Pockets p = pockets;
        if (p == null) pockets = p = findPockets(m);
        return p.levelAt(x, z);
    }

    private Pockets findPockets(HydroModel m) {
        Profile prof = profile(m);
        // Only a reach whose water stands above a bank can have pockets: water at or above the bank ceiling + 1
        // (the ceiling is a block below the lowest bank sample).
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        boolean perched = false;
        for (int i = 0; i < px.length; i++) {
            int w = prof.waterAt(s[i]);
            lo = Math.min(lo, w); hi = Math.max(hi, w);
            if (w > bankCeil[i] + 1) perched = true;
        }
        if (!perched || lo != hi) return Pockets.NONE;
        int level = lo;
        double reach = maxWidth() / 2 + POCKET_REACH + 1;
        int x0 = (int) Math.floor(minX + influence - reach), z0 = (int) Math.floor(minZ + influence - reach);
        int x1 = (int) Math.ceil(maxX - influence + reach), z1 = (int) Math.ceil(maxZ - influence + reach);
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        if ((long) w * h > 1 << 20) return Pockets.NONE;
        // 0 wall/outside, 1 channel, 2 low candidate, 3 low but beyond the strip (an outlet)
        byte[] cls = new byte[w * h];
        double[] pr = new double[2];
        for (int b = 0; b < h; b++) for (int a = 0; a < w; a++) {
            int x = x0 + a, z = z0 + b;
            project(x, z, pr);
            double half = width(pr[1]) / 2;
            if (half > MIN_HALF && pr[0] <= half) { cls[a + w * b] = 1; continue; }
            if (m.terrain.at(x, z) > level) continue;
            cls[a + w * b] = pr[0] <= half + POCKET_REACH ? (byte) 2 : (byte) 3;
        }
        long[] bits = new long[(w * h + 63) >>> 6];
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[64];
        int found = 0;
        for (int start = 0; start < w * h; start++) {
            if (cls[start] != 2 || seen[start] || !touchesChannel(cls, w, h, start)) continue;
            int head = 0, tail = 0;
            boolean open = false;
            seen[start] = true; queue[tail++] = start;
            while (head < tail) {
                int i = queue[head++], a = i % w, b = i / w;
                for (int d = 0; d < 4; d++) {
                    int na = a + (d == 0 ? 1 : d == 1 ? -1 : 0), nb = b + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (na < 0 || nb < 0 || na >= w || nb >= h) { open = true; continue; }
                    int ni = na + w * nb;
                    if (cls[ni] == 3) { open = true; continue; }
                    if (cls[ni] != 2 || seen[ni]) continue;
                    seen[ni] = true;
                    if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, Math.min(w * h, queue.length * 2));
                    queue[tail++] = ni;
                }
                if (tail > POCKET_MAX) open = true;
            }
            if (open) continue;
            for (int q = 0; q < tail; q++) { int i = queue[q]; bits[i >>> 6] |= 1L << (i & 63); }
            found += tail;
        }
        return found == 0 ? Pockets.NONE : new Pockets(x0, z0, w, h, bits, level);
    }
    private static boolean touchesChannel(byte[] cls, int w, int h, int i) {
        int a = i % w, b = i / w;
        return (a + 1 < w && cls[i + 1] == 1) || (a > 0 && cls[i - 1] == 1) || (b + 1 < h && cls[i + w] == 1) || (b > 0 && cls[i - w] == 1);
    }

    /** Chance of a rock-step waterfall on a reach that drops three or more blocks. */
    static double knickChance(HydroClimate.Setting s) {
        return switch (s) {
            case ALPINE -> 0.55; case TROPICAL -> 0.45; case SAVANNA, BADLANDS, DESERT -> 0.35; case BOREAL -> 0.30;
            case ARCTIC -> 0.25; case TEMPERATE -> 0.18; case WETLAND -> 0.0;
        };
    }

    /** Whether a column lies in this reach's channel - the channel test of {@link #evaluate}, without plunge
     * pools (geometry only: lake fills use it before any water level is known). */
    boolean inChannel(int x, int z, double[] scratch) {
        if (!inBox(x, z)) return false;
        project(x, z, scratch);
        double d = scratch[0], arc = scratch[1], half = width(arc) / 2;
        if (end == TERMINAL) half *= Math.max(0, Math.min(1, (length - arc) / 4));
        return half > MIN_HALF && d <= half;
    }
    static final double MIN_HALF = 1e-6;
}
