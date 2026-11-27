package dev.freshgenerator.core;

import java.util.ArrayList;
import java.util.List;

/** Rare large landforms of the current world (user requests 26.11): volcanoes of several kinds, impact craters and
 * rifts, part of the terrain itself (the drainage network, climate and materials see them), a pure function of the seed.
 * <p><b>Volcanoes</b> gather in rare volcanic provinces (a slow noise, ~15% of the world); a few stand alone outside.
 * One candidate site per {@link #VOLCANO_CELL} cell, and a candidate gives way to a stronger neighbour closer than
 * {@link #SPACING} of their summed radii (arc volcanoes stand several base diameters apart, de Bremond d'Ars et al.
 * 1995). Kinds:
 * <ul>
 * <li>STRATO - a steep concave cone 100-200 blocks high, radius ~5.4 heights, summit crater, radial gullies (barrancos)
 * cut by rain into the loose flanks; the rare giant rises a little above the usual mountain summits;</li>
 * <li>SHIELD - a broad convex dome 35-80 blocks high and ~20 heights wide (basalt lava shields) with a pit crater;</li>
 * <li>CALDERA - a ring mountain round a collapsed summit 300-700 blocks across, its floor far below the rim (a lake
 * where the climate holds water);</li>
 * <li>CINDER - a field of 5-9 small scoria cones with craters.</li>
 * </ul>
 * On the sea floor (Y{@link #OCEAN_MIN}..58) the same cones rise as volcanic islands or stay below the waves as
 * seamounts. About {@link #P_ACTIVE} of the cones are active: a lava lake in the summit crater, fresh flows down the
 * flanks and a burnt forest round them ({@link LandformMaterials}).
 * <p>A cone rises from the ground of its site: the relief of the ground under it fades toward the summit (radial
 * drainage, no closed hollows on the flanks; audits 26.11 Opus N-2/KO-02: a lake 1.2 km long stood on a flank 150
 * blocks above the foot), and craters and calderas cut down from that same level (no seam at the rim; OpenAI 26.11:
 * 18 blocks between two columns of a caldera rim).
 * <p><b>Impact craters</b> (one candidate per {@link #CRATER_CELL} cell): simple bowls (depth ~0.2 of the diameter,
 * Melosh 1989), larger ones with a flat floor and a central peak, a raised rim and an ejecta blanket thinning with the
 * cube of the distance (McGetchin et al. 1973). The excavation is a bowl at a fixed height below the pre-impact ground
 * at the centre: on a slope it cuts the uphill side into a steep crescent scarp and opens downhill. Three ages: fresh
 * (sharp), old (softened, partly filled) and ancient (a low ring). Rare: about one per 100 km2 of land, a fifth fresh.
 * <p><b>Rifts</b> (one candidate per {@link #RIFT_CELL} cell, likelier in mountains): a chasm along a bent line, from a
 * few hundred blocks long and some twenty deep to three kilometres long and up to {@link #RIFT_DEPTH_MAX} deep. Its
 * floor follows the low ground along its line, so a rift crossing a mountain splits it with walls far higher than its
 * depth on the plain; it never goes below Y{@link #RIFT_FLOOR_MIN} (far above the bedrock). Rifts are sinks for the
 * drainage network: rivers and lake outlets pour into them and end on their floor. */
public final class Landforms {
    public static final int VOLCANO_CELL = 2048, CRATER_CELL = 2560, RIFT_CELL = 3072;
    /** Volcano probability per cell inside a province and outside one (audit 26.11: one per 45 km2 was far too common). */
    public static final double P_PROVINCE = 0.30, P_LONE = 0.02;
    /** A candidate gives way to a stronger neighbour closer than this share of their summed radii. */
    public static final double SPACING = 0.9;
    /** Share of the cones (not calderas) that are active. */
    public static final double P_ACTIVE = 0.3;
    /** Crater probability per cell; ages fresh / old / ancient. */
    public static final double P_CRATER = 0.10, FRESH_SHARE = 0.20, OLD_SHARE = 0.45;
    /** Lowest ground at a land volcano, and at a crater (inland: 39% of the craters near the sea were flooded). */
    public static final double MIN_BASE = 62, CRATER_MIN_BASE = 70;
    /** Sea floor depth band of ocean volcanoes (no landform in the coastal shallows Y58-62). */
    public static final double OCEAN_MIN = 18, OCEAN_MAX = 58, SEA = 63;
    /** Soft ceiling of a summit: above SOFT_TOP heights approach SOFT_TOP + SOFT_ROOM. */
    public static final double SOFT_TOP = 268, SOFT_ROOM = 32;
    /** Rift probability per cell on low ground and on mountains (centre ground Y90 -> Y160). */
    public static final double P_RIFT_LOW = 0.09, P_RIFT_HIGH = 0.26;
    public static final double RIFT_FLOOR_MIN = 8, RIFT_DEPTH_MAX = 170, RIFT_STEP = 16;
    /** Lowest original ground on the line of a rift (its centre and every sample it keeps): just above the sea. At Y67
     * the low coastal plains (Y64-66) cut every rift there into pieces of 300-800 blocks (seed 424242: three short
     * rifts in 24.6 km, now five up to 1701 blocks); the sea stays out by the land beside the line and the trim before it. */
    public static final double RIFT_LAND = SEA + 1;

    public enum Kind { STRATO, SHIELD, CALDERA, CINDER }
    public enum Age { FRESH, OLD, ANCIENT }

    /** Base terrain (before the landforms) at a point. */
    public interface Ground { double at(int x, int z); }

    public static final class Volcano {
        public final int x, z; public final Kind kind; public final double height, radius, craterR, craterD; final long seed;
        /** Lowest point of a caldera's rim crest (NaN until measured; a pure value). */
        volatile double rimLow = Double.NaN;
        /** On the sea floor (an island or a seamount) and active (lava lake, fresh flows, burnt forest). */
        public final boolean ocean, activeFlag;
        final double[] coneX, coneZ, coneH, coneR;
        final GradientNoise flows;
        /** Ground at the site before the volcano (the sea floor under an ocean volcano; NaN for a spacing candidate). */
        public final double base;
        Volcano(int x, int z, Kind kind, double height, double radius, double craterR, double craterD, long seed, double[][] cones,
                boolean ocean, boolean active, GradientNoise flows, double base) {
            this.base = base;
            this.x = x; this.z = z; this.kind = kind; this.height = height; this.radius = radius; this.craterR = craterR; this.craterD = craterD; this.seed = seed;
            coneX = cones[0]; coneZ = cones[1]; coneH = cones[2]; coneR = cones[3];
            this.ocean = ocean; this.activeFlag = active; this.flows = flows;
            pitR = craterR * (0.36 + 0.16 * GradientNoise.unit(seed, 92, 0));
            pitD = 7 + 0.22 * craterD;
        }
        /** Mean radius and depth of the pit in the floor of a lava crater: the lake fills the pit, not the whole crater
         * (Nyiragongo, Erta Ale, Villarrica: the lake lies on the crater floor, far inside its rim). */
        final double pitR, pitD;
        /** Radius of the lava pit toward the angle theta: its outline wanders by about a sixth round the turn. */
        public double pitRadius(double theta) {
            return pitR * (1 + 0.32 * flows.value(Math.cos(theta) * 1.4 + 40 + (seed & 15), Math.sin(theta) * 1.4 - 17));
        }
        /** Whether the lava lake at (px, pz) is crusted over: dark plates parted by glowing cracks, few in the middle
         * where the lava wells up, most toward the cooler margin. */
        public boolean crust(int px, int pz) {
            double dx = px - x, dz = pz - z, s = Math.hypot(dx, dz) / Math.max(1, pitRadius(Math.atan2(dz, dx)));
            double n = Math.abs(flows.value(px / 6.5 + 97.3 + (seed & 7), pz / 6.5 - 31.7));
            double middle = Math.max(0, 1 - s);
            return n > 0.07 + 0.30 * middle * middle;
        }
        /** Lava flows traced down the real ground from the crater rim (set once, before the volcano is shared). */
        Flow[] lavaFlows = NO_FLOWS;
        /** Farthest reach of the flows from the centre. */
        double flowReach;
        /** Largest distance from the centre the volcano changes the ground. */
        public double reach() {
            double r = Math.max(radius, flowReach);
            for (int i = 0; i < coneX.length; i++) r = Math.max(r, Math.hypot(coneX[i] - x, coneZ[i] - z) + coneR[i]);
            return r;
        }
        /** The ground at (px, pz) with the lava flows over it: the flows fill the low ground across them up to their
         * surface (the centreline ground plus their thickness) and stand at most their thickness over a lower side. */
        public double flowSurface(int px, int pz, double h) {
            for (Flow f : lavaFlows) h = f.surface(px, pz, h);
            return h;
        }
        /** Distance to the nearest flow over its half-width there (below 1 on the flow; +infinity when none is near), with
         * {share of the flow's length, half-width, distance} into out when out is not null. */
        public double flowQ(int px, int pz, double[] out) {
            double best = Double.POSITIVE_INFINITY;
            double[] tmp = new double[3];
            for (Flow f : lavaFlows) {
                double q = f.q(px, pz, tmp);
                if (q < best) { best = q; if (out != null) System.arraycopy(tmp, 0, out, 0, 3); }
            }
            return best;
        }
        /** The lava flows of the volcano (empty for calderas, cinder fields and seamounts). */
        public Flow[] flows() { return lavaFlows.clone(); }
        public boolean active() { return activeFlag; }
        /** Radial share of the shore of a volcanic island (where its cone rises above the sea floor of the site to the
         * sea level), 1 for land volcanoes: the subaerial cover of an island - flows, burnt strips - stays on the
         * island (its flows crossed 200 blocks of sea onto the mainland, seed 31337 X7452 Z-7416). */
        public double shore() {
            if (!ocean || Double.isNaN(base) || height <= 0) return 1;
            double d = Math.max(0, SEA - base) / height;
            if (d >= 1) return 0;
            return kind == Kind.SHIELD ? Math.sqrt(1 - Math.pow(d, 2 / 3.0)) : 1 - Math.pow(d, 1 / 2.4);
        }
        /** A summit crater that can hold a lava lake (active strato and shield cones above the sea). */
        public boolean lavaCrater() { return activeFlag && (kind == Kind.STRATO || kind == Kind.SHIELD); }
    }

    static final Flow[] NO_FLOWS = new Flow[0];

    /** A lava flow traced down the real ground from the crater rim (user request 26.11: a process, not a shape): its
     * centreline every FLOW_STEP blocks with the ground there, its half-width (wandering along it) and thickness -
     * narrow and thin on the steep cone, spreading wide and thick where the ground flattens along its way - an irregular
     * lobe at its snout, and a dam where it came down into a valley and filled it from side to side. On the ground it
     * fills the low ground across it up to its surface (deposits come before the water: the drainage model fills the
     * hollow behind a dam with a lake). */
    public static final class Flow {
        final double[] x, z, s, zc, w, t;
        final int n;
        public final double length;
        /** The snout lobe and the dam (null when the flow made none). */
        final Lobe snout, dam;
        public final boolean fresh;
        final double minX, minZ, maxX, maxZ;
        final double[] bMinX, bMinZ, bMaxX, bMaxZ;
        Flow(double[] x, double[] z, double[] s, double[] zc, double[] w, double[] t, boolean fresh, Lobe snout, Lobe dam) {
            this.x = x; this.z = z; this.s = s; this.zc = zc; this.w = w; this.t = t; this.fresh = fresh;
            this.snout = snout; this.dam = dam;
            n = x.length;
            length = s[n - 1];
            int buckets = Math.max(1, (n - 2) / BUCKET + 1);
            bMinX = new double[buckets]; bMinZ = new double[buckets]; bMaxX = new double[buckets]; bMaxZ = new double[buckets];
            double ax = snout.cx - snout.extent, az = snout.cz - snout.extent, bx = snout.cx + snout.extent, bz = snout.cz + snout.extent;
            if (dam != null) {
                ax = Math.min(ax, dam.cx - dam.extent); az = Math.min(az, dam.cz - dam.extent);
                bx = Math.max(bx, dam.cx + dam.extent); bz = Math.max(bz, dam.cz + dam.extent);
            }
            for (int b = 0; b < buckets; b++) {
                double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
                for (int i = b * BUCKET; i <= Math.min(n - 1, (b + 1) * BUCKET); i++) {
                    x0 = Math.min(x0, x[i] - w[i] - 1); x1 = Math.max(x1, x[i] + w[i] + 1);
                    z0 = Math.min(z0, z[i] - w[i] - 1); z1 = Math.max(z1, z[i] + w[i] + 1);
                }
                bMinX[b] = x0; bMinZ[b] = z0; bMaxX[b] = x1; bMaxZ[b] = z1;
                ax = Math.min(ax, x0); az = Math.min(az, z0); bx = Math.max(bx, x1); bz = Math.max(bz, z1);
            }
            minX = ax; minZ = az; maxX = bx; maxZ = bz;
        }
        /** Whether the flow dammed a valley. */
        public boolean dammed() { return dam != null; }
        /** The centre of the dam ({x, z}) or null. */
        public double[] damPoint() { return dam == null ? null : new double[]{dam.cx, dam.cz}; }
        /** Farthest point of the flow from (px, pz) plus its margin. */
        double reachFrom(double px, double pz) {
            double r = Math.hypot(snout.cx - px, snout.cz - pz) + snout.extent;
            if (dam != null) r = Math.max(r, Math.hypot(dam.cx - px, dam.cz - pz) + dam.extent);
            for (int i = 0; i < n; i++) r = Math.max(r, Math.hypot(x[i] - px, z[i] - pz) + w[i]);
            return r + 2;
        }
        /** Cross profile of the surface: flat over the middle, steep margins (levees and the fronts of blocky flows). */
        static double prof(double q) { return 1 - smooth(0.55, 1.0, q); }
        double surface(double px, double pz, double h) {
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) return h;
            double best = h;
            for (int b = 0; b < bMinX.length; b++) {
                if (px < bMinX[b] || px > bMaxX[b] || pz < bMinZ[b] || pz > bMaxZ[b]) continue;
                for (int i = b * BUCKET; i < Math.min(n - 1, (b + 1) * BUCKET); i++) {
                    double vx = x[i + 1] - x[i], vz = z[i + 1] - z[i], l2 = vx * vx + vz * vz;
                    double f = l2 < 1e-9 ? 0 : ((px - x[i]) * vx + (pz - z[i]) * vz) / l2;
                    f = f < 0 ? 0 : f > 1 ? 1 : f;
                    double ww = w[i] + (w[i + 1] - w[i]) * f, d = Math.hypot(px - x[i] - f * vx, pz - z[i] - f * vz);
                    if (d >= ww) continue;
                    double tp = (t[i] + (t[i + 1] - t[i]) * f) * prof(d / ww), zz = zc[i] + (zc[i + 1] - zc[i]) * f;
                    best = Math.max(best, Math.min(zz + tp, h + tp + 2));
                }
            }
            best = snout.surface(px, pz, h, best);
            if (dam != null) best = dam.surface(px, pz, h, best);
            return best;
        }
        /** Distance over the half-width to the nearest point of the flow, {share of length, half-width, distance} into out. */
        double q(double px, double pz, double[] out) {
            double best = Double.POSITIVE_INFINITY, pad = 24;
            if (px < minX - pad || px > maxX + pad || pz < minZ - pad || pz > maxZ + pad) return best;
            for (int b = 0; b < bMinX.length; b++) {
                if (px < bMinX[b] - pad || px > bMaxX[b] + pad || pz < bMinZ[b] - pad || pz > bMaxZ[b] + pad) continue;
                for (int i = b * BUCKET; i < Math.min(n - 1, (b + 1) * BUCKET); i++) {
                    double vx = x[i + 1] - x[i], vz = z[i + 1] - z[i], l2 = vx * vx + vz * vz;
                    double f = l2 < 1e-9 ? 0 : ((px - x[i]) * vx + (pz - z[i]) * vz) / l2;
                    f = f < 0 ? 0 : f > 1 ? 1 : f;
                    double ww = w[i] + (w[i + 1] - w[i]) * f, d = Math.hypot(px - x[i] - f * vx, pz - z[i] - f * vz), q = d / ww;
                    if (q < best) { best = q; out[0] = (s[i] + (s[i + 1] - s[i]) * f) / Math.max(1, length); out[1] = ww; out[2] = d; }
                }
            }
            for (Lobe l : dam == null ? new Lobe[]{snout} : new Lobe[]{snout, dam}) {
                double r = l.rho(px, pz);
                if (r < best) { best = r; out[0] = l.share; out[1] = l.scale; out[2] = r * l.scale; }
            }
            return best;
        }
    }

    /** A lobe of lava spread round a point: a superellipse in its own frame (u along the flow or the valley, v across),
     * with its own extent forward, back and to either side and, for a snout, an outline wandering round the turn. */
    static final class Lobe {
        final double cx, cz, ux, uz, ahead, back, left, right, t, zc, power, wander, share, scale, extent;
        final long phase;
        final GradientNoise noise;
        Lobe(double cx, double cz, double ux, double uz, double ahead, double back, double left, double right, double t,
             double zc, double power, double wander, double share, long phase, GradientNoise noise) {
            this.cx = cx; this.cz = cz; this.ux = ux; this.uz = uz; this.ahead = ahead; this.back = back; this.left = left;
            this.right = right; this.t = t; this.zc = zc; this.power = power; this.wander = wander; this.share = share;
            this.phase = phase; this.noise = noise;
            scale = Math.max(Math.max(ahead, back), Math.max(left, right));
            extent = scale * (1 + wander) + 2;
        }
        /** Normalized radius of (px, pz): below 1 inside the lobe. */
        double rho(double px, double pz) {
            double dx = px - cx, dz = pz - cz;
            if (Math.abs(dx) > extent || Math.abs(dz) > extent) return Double.POSITIVE_INFINITY;
            double a = dx * ux + dz * uz, b = -dx * uz + dz * ux;
            double qa = Math.abs(a) / (a >= 0 ? ahead : back), qb = Math.abs(b) / (b >= 0 ? left : right);
            double r = Math.pow(Math.pow(qa, power) + Math.pow(qb, power), 1 / power);
            if (wander > 0) {
                double ang = Math.atan2(b, a);
                r /= 1 + wander * noise.value(Math.cos(ang) * 1.3 + (phase & 63), Math.sin(ang) * 1.3 + 9.5);
            }
            return r;
        }
        double surface(double px, double pz, double h, double best) {
            double r = rho(px, pz);
            if (r >= 1) return best;
            double tp = t * Flow.prof(r);
            return Math.max(best, Math.min(zc + tp, h + tp + 2));
        }
    }
    /** Segments per bounding box of a flow, the step of its trace and its greatest length. */
    static final int BUCKET = 8;
    static final double FLOW_STEP = 8, FLOW_MAX = 1800, FLOW_PROBE = 6;
    /** A dam closes a valley only if both its sides rise above its crest within DAM_REACH blocks. */
    static final double DAM_REACH = 160;
    /** Dams only beyond this share of the cone radius from the summit. */
    static final double DAM_OFF_CONE = 0.85;
    /** Directions tried when a flow's next step would climb (radians off its heading). */
    private static final double[] TURNS = {0.52, -0.52, 1.05, -1.05, 1.57, -1.57};

    public static final class Crater {
        public final int x, z; public final double radius, depth, rim; public final Age age; public final boolean complex;
        /** Lowest point of the rim crest on the ground with the volcanoes and craters (NaN until measured; a pure value,
         * a race only measures it twice). */
        volatile double rimLow = Double.NaN;
        Crater(int x, int z, double radius, double depth, double rim, Age age, boolean complex) {
            this.x = x; this.z = z; this.radius = radius; this.depth = depth; this.rim = rim; this.age = age; this.complex = complex;
        }
        public double reach() { return radius * 3; }
    }

    /** A rift: a bent line of vertices, its width and depth along the line (tapering to nothing at both ends) and its
     * floor level sampled every {@link #RIFT_STEP} blocks. */
    public static final class Rift {
        public final int x, z; public final double length, depth, width;
        final double[] px, pz, ps, floor;
        final double minX, minZ, maxX, maxZ;
        final long seed;
        final GradientNoise wobble;
        Rift(int x, int z, double[] px, double[] pz, double depth, double width, double[] floor, long seed, GradientNoise wobble) {
            this.x = x; this.z = z; this.px = px; this.pz = pz; this.depth = depth; this.width = width; this.floor = floor; this.seed = seed; this.wobble = wobble;
            ps = new double[px.length];
            for (int i = 1; i < px.length; i++) ps[i] = ps[i - 1] + Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
            length = ps[px.length - 1];
            double ax = Double.MAX_VALUE, az = Double.MAX_VALUE, bx = -Double.MAX_VALUE, bz = -Double.MAX_VALUE;
            for (int i = 0; i < px.length; i++) { ax = Math.min(ax, px[i]); bx = Math.max(bx, px[i]); az = Math.min(az, pz[i]); bz = Math.max(bz, pz[i]); }
            double pad = width + 8;
            minX = ax - pad; minZ = az - pad; maxX = bx + pad; maxZ = bz + pad;
        }
        public double reach() { return Math.max(maxX - minX, maxZ - minZ); }
        boolean inBox(double x, double z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        /** {distance to the line, arc of the nearest point, side (+1 / -1)} into out; false outside the bounding box. */
        boolean nearest(double qx, double qz, double[] out) {
            if (!inBox(qx, qz)) return false;
            double best = Double.MAX_VALUE, arc = 0, side = 1;
            for (int i = 0; i + 1 < px.length; i++) {
                double vx = px[i + 1] - px[i], vz = pz[i + 1] - pz[i], l2 = vx * vx + vz * vz;
                double t = l2 < 1e-9 ? 0 : ((qx - px[i]) * vx + (qz - pz[i]) * vz) / l2;
                t = t < 0 ? 0 : t > 1 ? 1 : t;
                double cx = px[i] + t * vx, cz = pz[i] + t * vz, d = Math.hypot(qx - cx, qz - cz);
                if (d < best) { best = d; arc = ps[i] + t * Math.sqrt(l2); side = vx * (qz - pz[i]) - vz * (qx - px[i]) >= 0 ? 1 : -1; }
            }
            out[0] = best; out[1] = arc; out[2] = side;
            return true;
        }
        /** The point of the line at arc s. */
        double[] pointAt(double s) {
            int i = 0;
            while (i + 2 < px.length && ps[i + 1] < s) i++;
            double l = ps[i + 1] - ps[i], t = l < 1e-9 ? 0 : Math.max(0, Math.min(1, (s - ps[i]) / l));
            return new double[]{px[i] + (px[i + 1] - px[i]) * t, pz[i] + (pz[i + 1] - pz[i]) * t};
        }
        /** 0 at both ends of the rift, 1 in its middle. */
        double taper(double s) { double t = Math.sin(Math.PI * Math.max(0, Math.min(1, s / length))); return t; }
        /** Half-width of the rift on one side at arc s: jagged walls, narrower at the ends. */
        double half(double s, double side) {
            double t = taper(s);
            if (t <= 0) return 0;
            double n = wobble.value(s / 40.0 + (seed & 15), 0.5), m = wobble.value(s / 23.0, 7.5 + side);
            return 0.5 * width * Math.pow(t, 0.55) * (0.8 + 0.35 * n) * (1 + 0.22 * side * m);
        }
        /** Floor level at arc s (interpolated between the samples). */
        double floorAt(double s) {
            double f = Math.max(0, Math.min(floor.length - 1.0001, s / RIFT_STEP));
            int i = (int) f;
            return floor[i] + (floor[i + 1] - floor[i]) * (f - i);
        }
    }

    private final long seed;
    private final GradientNoise province, gully, flows, rubble, wobble;

    public Landforms(long seed) {
        this.seed = seed;
        province = new GradientNoise(seed ^ 0x2545F4914F6CDD1DL);
        gully = new GradientNoise(seed ^ 0x9E3779B97F4A7C15L);
        flows = new GradientNoise(seed ^ 0x4F1BBCDCBFA53E0BL);
        rubble = new GradientNoise(seed ^ 0x1B03738712FAD5C9L);
        wobble = new GradientNoise(seed ^ 0x68E31DA4B4F13C59L);
    }

    /** Province weight 0..1 at (x, z): regions ~10-20 km across. */
    public double province(double x, double z) {
        double n = province.fractal(x, z, 14000, 2);
        return smooth(0.18, 0.34, n);
    }

    /** Position, chance and size of the candidate of a cell without the ground (the spacing rule compares these). */
    /** A volcano candidate before its ground is known; land says whether it also passes the land chance. */
    private record Raw(long cs, int x, int z, double w, double radius, double priority, boolean land) { }
    private Raw raw(int i, int j) {
        long cs = cellSeed(i, j, 0x7F4A7C15L);
        int x = (int) Math.floor((i + 0.2 + 0.6 * GradientNoise.unit(cs, 1, 0)) * VOLCANO_CELL);
        int z = (int) Math.floor((j + 0.2 + 0.6 * GradientNoise.unit(cs, 2, 0)) * VOLCANO_CELL);
        double w = province(x, z), p = P_LONE + (P_PROVINCE - P_LONE) * w, roll = GradientNoise.unit(cs, 3, 0);
        if (roll >= p * OCEAN_BOOST) return null;
        Volcano shape = shape(cs, x, z, w, Double.NaN);
        return new Raw(cs, x, z, w, shape == null ? 0 : shape.reach(), GradientNoise.unit(cs, 50, 0), roll < p);
    }

    /** Ocean volcanoes are this many times likelier per cell than land ones: hot spots build island chains and the sea
     * floor carries most of the volcanoes of a planet (the census of seed 31337 found 2 of 15 in the sea). */
    public static final double OCEAN_BOOST = 2.0;

    /** Whether a candidate stands at all: on land when it passed the land chance, or on the sea floor of the ocean band. */
    private static boolean placed(Raw r, Ground ground) {
        double base = ground.at(r.x, r.z);
        if (base >= MIN_BASE) return r.land;
        return base >= OCEAN_MIN && base <= OCEAN_MAX;
    }

    /** The volcano candidate of cell (i, j), or null. Placement uses the ground at the site. */
    public Volcano volcano(int i, int j, Ground ground) {
        Raw me = raw(i, j);
        if (me == null || !placed(me, ground)) return null;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            if (di == 0 && dj == 0) continue;
            Raw n = raw(i + di, j + dj);
            // Only a neighbour that really stands takes the place (a candidate in the coastal shallows or one that failed
            // the land chance does not).
            if (n != null && n.priority > me.priority && Math.hypot(n.x - me.x, n.z - me.z) < SPACING * (n.radius + me.radius)
                    && placed(n, ground)) return null;
        }
        Volcano v = shape(me.cs, me.x, me.z, me.w, ground.at(me.x, me.z));
        if (v != null) traceFlows(v, ground);
        return v;
    }

    /** Trace the lava flows of a volcano over the real ground (its cone over the base ground): 3-7 fresh flows on an
     * active strato or shield cone, 2-5 old ones on a dormant one, none on calderas, cinder fields and seamounts. */
    void traceFlows(Volcano v, Ground ground) {
        if (Double.isNaN(v.base) || v.kind == Kind.CALDERA || v.kind == Kind.CINDER) return;
        if (v.ocean && v.base + v.height <= SEA + 2) return;
        boolean fresh = v.active();
        long cs = v.seed;
        int count = fresh ? 3 + (int) (GradientNoise.unit(cs, 93, 0) * 5) : 2 + (int) (GradientNoise.unit(cs, 93, 0) * 4);
        List<Flow> flows = new ArrayList<>();
        double reach = 0;
        for (int k = 0; k < count; k++) {
            double a = GradientNoise.unit(cs, 94 + k, 0) * 2 * Math.PI;
            double len = Math.min(FLOW_MAX, v.radius * (fresh ? 0.7 + 1.6 * GradientNoise.unit(cs, 94 + k, 1) : 0.5 + 1.0 * GradientNoise.unit(cs, 94 + k, 1)));
            double w0 = (v.kind == Kind.SHIELD ? 7 : 5) + 6 * GradientNoise.unit(cs, 94 + k, 2);
            double t0 = ((v.kind == Kind.SHIELD ? 3 : 5) + 4 * GradientNoise.unit(cs, 94 + k, 3)) * (fresh ? 1 : 0.7);
            Flow f = traceFlow(v, ground, a, len, w0, t0, fresh);
            if (f == null) continue;
            flows.add(f);
            reach = Math.max(reach, f.reachFrom(v.x, v.z));
        }
        v.lavaFlows = flows.toArray(NO_FLOWS);
        v.flowReach = reach;
    }

    /** One flow from the crater rim at angle a, down the steepest slope of the cone and the ground beyond (with some
     * inertia), until its length is used up, the ground is flat for three steps, it would have to climb (it ponds), or it
     * reaches the sea. Half-width w0 and thickness t0 on flat ground; on a steep slope 0.6 w0 and 0.6 t0. */
    Flow traceFlow(Volcano v, Ground ground, double a, double len, double w0, double t0, boolean fresh) {
        double r0 = Math.max(6, v.craterR * 1.1);
        double x = v.x + Math.cos(a) * r0, z = v.z + Math.sin(a) * r0, dx = Math.cos(a), dz = Math.sin(a), s = 0;
        ArrayList<double[]> pts = new ArrayList<>();
        int flat = 0;
        for (int step = 0; step <= (int) (FLOW_MAX / FLOW_STEP) + 2; step++) {
            double hc = flowGround(v, ground, x, z);
            if (hc < SEA + 1) break;
            double gx = (flowGround(v, ground, x + FLOW_PROBE, z) - flowGround(v, ground, x - FLOW_PROBE, z)) / (2 * FLOW_PROBE);
            double gz = (flowGround(v, ground, x, z + FLOW_PROBE) - flowGround(v, ground, x, z - FLOW_PROBE)) / (2 * FLOW_PROBE);
            double g = Math.hypot(gx, gz);
            pts.add(new double[]{x, z, s, hc, g, dx, dz});
            if (s >= len) break;
            if (g < 0.004) { if (++flat >= 3) break; } else flat = 0;
            double nx = g > 1e-6 ? -gx / g : dx, nz = g > 1e-6 ? -gz / g : dz;
            double mx = 0.55 * dx + 0.45 * nx, mz = 0.55 * dz + 0.45 * nz, ml = Math.hypot(mx, mz);
            if (ml < 1e-6) break;
            dx = mx / ml; dz = mz / ml;
            double x2 = x + dx * FLOW_STEP, z2 = z + dz * FLOW_STEP, h2 = flowGround(v, ground, x2, z2);
            if (h2 > hc + 0.5) {
                // Carried across a valley floor and up its far side: turn down the valley - the lowest of the directions
                // up to 90 degrees either way - and stop only in a hollow (the flow ponds there). Stopping at the first
                // climb left every flow at the axis of the first valley it met.
                double bx = dx, bz = dz;
                for (double turn : TURNS) {
                    double cx = dx * Math.cos(turn) - dz * Math.sin(turn), cz = dx * Math.sin(turn) + dz * Math.cos(turn);
                    double hh = flowGround(v, ground, x + cx * FLOW_STEP, z + cz * FLOW_STEP);
                    if (hh < h2) { h2 = hh; bx = cx; bz = cz; }
                }
                if (h2 > hc + 0.5) break;
                dx = bx; dz = bz; x2 = x + dx * FLOW_STEP; z2 = z + dz * FLOW_STEP;
            }
            x = x2; z = z2; s += FLOW_STEP;
        }
        int n = pts.size();
        if (n < 4) return null;
        double[] px = new double[n], pz = new double[n], ps = new double[n], zc = new double[n], w = new double[n], t = new double[n];
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i), q = pts.get(Math.min(n - 1, i + 1)), o = pts.get(Math.max(0, i - 1));
            // The slope along the flow's own path: on the floor of a V-shaped valley it is the gentle fall of the floor,
            // not the steep sides (the walls hold the flow, max(ground, surface)).
            double along = i + 1 < n ? (p[3] - q[3]) / Math.max(1, q[2] - p[2]) : (o[3] - p[3]) / Math.max(1, p[2] - o[2]);
            double flatness = 1 - smooth(0.02, 0.12, Math.max(0, along)), start = smooth(0, 24, p[2]);
            px[i] = p[0]; pz[i] = p[1]; ps[i] = p[2]; zc[i] = p[3];
            // The edge wanders along the flow (a constant ribbon read as a road).
            double wobble = 1 + 0.4 * v.flows.value(p[2] / 37.0 + a * 13.7, a * 3.1 + 5.5);
            w[i] = Math.max(2.5, w0 * (0.9 + 3.1 * flatness * flatness) * (0.4 + 0.6 * start) * wobble);
            t[i] = t0 * (0.6 + 0.4 * flatness) * start;
        }
        // The snout: a lobe stretched ahead of the flow, its outline wandering.
        double[] last = pts.get(n - 1);
        double sr = 1.3 * w[n - 1] + 4;
        Lobe snout = new Lobe(px[n - 1], pz[n - 1], last[5], last[6], 1.4 * sr, 0.7 * sr, sr, sr, 1.15 * t[n - 1], zc[n - 1],
                2, 0.25, 1, Double.doubleToLongBits(a), v.flows);
        // The valley entry: the flow turns by 40 degrees or more within three steps onto ground that is clearly gentler
        // along its way. There it spread up and down the valley floor and filled it from side to side: a dam, if both sides
        // of the valley rise above its crest within DAM_REACH blocks (on an open slope there is none).
        Lobe dam = null;
        for (int i = 2; i + 2 < n && dam == null; i++) {
            double[] before = pts.get(i - 2), after = pts.get(i + 1);
            double turn = before[5] * after[5] + before[6] * after[6];
            double fa = 1 - smooth(0.02, 0.12, Math.max(0, (before[3] - pts.get(i - 1)[3]) / FLOW_STEP));
            double fb = 1 - smooth(0.02, 0.12, Math.max(0, (pts.get(i)[3] - after[3]) / FLOW_STEP));
            if (!(turn < Math.cos(Math.toRadians(40)) && fb - fa >= 0.3 && ps[i] > 40)) continue;
            // Only off the cone: its gullies drain nothing but the cone itself (12 dams in cone gullies on seed
            // -77777123, none with a lake behind); a river valley of the land round it does.
            if (Math.hypot(px[i] - v.x, pz[i] - v.z) < DAM_OFF_CONE * v.radius) continue;
            // A valley the water runs through, not the floor of a basin: the flow goes on down the valley for eight steps
            // and more and falls half a block or more on the way. On the floor of a closed basin the flow stops, and a
            // "dam" there only filled part of the old lake basin (seed -77777123: three dams, all at existing lakes).
            if (i + 8 >= n || zc[i] - zc[i + 8] < 0.5) continue;
            double[] down = pts.get(i + 2);
            double ux = down[5], uz = down[6], crestT = Math.max(4, 1.1 * t[i]), crest = zc[i] + crestT;
            double[] sides = new double[2];
            boolean closed = true;
            for (int side = 0; side < 2 && closed; side++) {
                double nx = side == 0 ? -uz : uz, nz = side == 0 ? ux : -ux, d = 4;
                while (d <= DAM_REACH && flowGround(v, ground, px[i] + nx * d, pz[i] + nz * d) < crest - 0.5) d += 4;
                if (d > DAM_REACH) closed = false;
                sides[side] = d + 6;
            }
            if (!closed) continue;
            double along = 0.6 * w[i] + 6;
            dam = new Lobe(px[i], pz[i], ux, uz, along, along, sides[0], sides[1], crestT, zc[i], 4, 0, ps[i] / Math.max(1, ps[n - 1]),
                    Double.doubleToLongBits(a) ^ 0x5DEECE66DL, v.flows);
        }
        return new Flow(px, pz, ps, zc, w, t, fresh, snout, dam);
    }
    private double flowGround(Volcano v, Ground ground, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        return volcano(v, ix, iz, ground.at(ix, iz), v.base);
    }

    /** The volcano of a candidate: the land shapes, or (base on the sea floor) an island or a seamount. A NaN base gives
     * the land shape for the spacing rule. */
    private Volcano shape(long cs, int x, int z, double w, double base) {
        double k = GradientNoise.unit(cs, 4, 0), u1 = GradientNoise.unit(cs, 5, 0), u2 = GradientNoise.unit(cs, 6, 0);
        boolean ocean = base < MIN_BASE;
        // Lone volcanoes outside the provinces are smaller.
        double scale = 0.65 + 0.35 * w;
        Kind kind = ocean ? (k < 0.55 ? Kind.STRATO : k < 0.90 ? Kind.SHIELD : Kind.CINDER)
                : k < 0.50 ? Kind.STRATO : k < 0.70 ? Kind.SHIELD : k < 0.88 ? Kind.CALDERA : Kind.CINDER;
        boolean active = kind != Kind.CALDERA && GradientNoise.unit(cs, 91, 7) < P_ACTIVE;
        double[][] none = {new double[0], new double[0], new double[0], new double[0]};
        if (ocean && kind != Kind.CINDER) {
            // An island (two in three) rises 15-105 blocks above the sea; a seamount stays 6+ blocks below it.
            boolean island = GradientNoise.unit(cs, 10, 0) < 0.65;
            double h = island ? SEA + 15 + 90 * u1 * scale - base : (SEA - 6 - base) * (0.55 + 0.4 * u1);
            if (h < 15) return null;
            if (kind == Kind.STRATO) {
                double r = h * (4.2 + 1.2 * u2);
                double cr = clamp(0.055 * r, 18, 50), cd = clamp(0.3 * cr + 5, 10, 26);
                return new Volcano(x, z, kind, h, r, cr, cd, cs, none, true, active, flows, base);
            }
            double r = h * (14 + 6 * u2), cr = clamp(0.06 * r, 30, 90), cd = clamp(0.18 * cr, 8, 20);
            return new Volcano(x, z, kind, h, r, cr, cd, cs, none, true, active, flows, base);
        }
        switch (kind) {
            case STRATO: {
                double h = (100 + 100 * u1) * scale;
                // The rare giant (about one stratovolcano in eight) stands a little above the usual summits.
                if (u2 > 0.88) h = Math.max(h, 190 + 25 * GradientNoise.unit(cs, 7, 0));
                double r = h * (4.8 + 1.2 * u2);
                double cr = clamp(0.055 * r, 22, 55), cd = clamp(0.3 * cr + 6, 12, 30);
                return new Volcano(x, z, kind, h, r, cr, cd, cs, none, false, active, flows, base);
            }
            case SHIELD: {
                double h = (35 + 45 * u1) * scale, r = h * (18 + 6 * u2);
                double cr = clamp(0.06 * r, 40, 100), cd = clamp(0.18 * cr, 10, 22);
                return new Volcano(x, z, kind, h, r, cr, cd, cs, none, false, active, flows, base);
            }
            case CALDERA: {
                double rim = (55 + 60 * u1) * scale, cr = 150 + 200 * u2, r = cr + rim * 7;
                double floorDepth = clamp(rim * (0.7 + 0.35 * GradientNoise.unit(cs, 8, 0)), 35, 110);
                return new Volcano(x, z, kind, rim, r, cr, floorDepth, cs, none, false, false, flows, base);
            }
            default: {
                int n = 5 + (int) (GradientNoise.unit(cs, 9, 0) * 5);
                double[][] cones = new double[4][n];
                double hs = ocean ? Math.max(0.5, Math.min(1.6, (SEA + 8 - base) / 30.0)) : 1;
                for (int c = 0; c < n; c++) {
                    double a = GradientNoise.unit(cs, 20 + c, 1) * 2 * Math.PI, d = 80 + 560 * Math.sqrt(GradientNoise.unit(cs, 20 + c, 2));
                    double h = (15 + 30 * GradientNoise.unit(cs, 20 + c, 3)) * (0.75 + 0.25 * scale) * hs;
                    cones[0][c] = x + Math.cos(a) * d; cones[1][c] = z + Math.sin(a) * d; cones[2][c] = h; cones[3][c] = h * (3.0 + 0.8 * GradientNoise.unit(cs, 20 + c, 4));
                }
                return new Volcano(x, z, kind, 0, 0, 0, 0, cs, cones, ocean, active, flows, base);
            }
        }
    }

    /** The impact crater candidate of cell (i, j), or null. */
    public Crater crater(int i, int j, Ground ground) {
        long cs = cellSeed(i, j, 0x1F83D9ABL);
        if (GradientNoise.unit(cs, 1, 1) >= P_CRATER) return null;
        int x = (int) Math.floor((i + 0.15 + 0.7 * GradientNoise.unit(cs, 2, 1)) * CRATER_CELL);
        int z = (int) Math.floor((j + 0.15 + 0.7 * GradientNoise.unit(cs, 3, 1)) * CRATER_CELL);
        double base = ground.at(x, z);
        if (!(base >= CRATER_MIN_BASE)) return null;
        double s = GradientNoise.unit(cs, 4, 1);
        double r = s < 0.70 ? 35 + 55 * GradientNoise.unit(cs, 5, 1) : s < 0.95 ? 90 + 90 * GradientNoise.unit(cs, 5, 1) : 180 + 200 * GradientNoise.unit(cs, 5, 1);
        boolean complex = r > 140;
        double a = GradientNoise.unit(cs, 6, 1);
        Age age = a < FRESH_SHARE ? Age.FRESH : a < FRESH_SHARE + OLD_SHARE ? Age.OLD : Age.ANCIENT;
        double depth = complex ? 0.11 * 2 * r : 0.2 * 2 * r, rim = 0.045 * 2 * r;
        depth = Math.min(depth, 60);
        rim = Math.min(rim, 16);
        if (age == Age.OLD) { depth *= 0.55; rim *= 0.55; }
        else if (age == Age.ANCIENT) { depth *= 0.22; rim *= 0.35; }
        return new Crater(x, z, r, depth, rim, age, complex);
    }

    /** The rift candidate of cell (i, j) tested on the base ground alone (tests and tools). */
    public Rift rift(int i, int j, Ground ground) { return rift(i, j, ground, null); }

    /** The rift candidate of cell (i, j), or null. Its floor is measured on the ground along its line; the land test
     * reads the ground with the volcanoes and craters of the cache (null: the base ground alone). */
    public Rift rift(int i, int j, Ground ground, SiteCache cache) {
        long cs = cellSeed(i, j, 0x3C79AC49L);
        int x = (int) Math.floor((i + 0.2 + 0.6 * GradientNoise.unit(cs, 1, 2)) * RIFT_CELL);
        int z = (int) Math.floor((j + 0.2 + 0.6 * GradientNoise.unit(cs, 2, 2)) * RIFT_CELL);
        double chanceRoll = GradientNoise.unit(cs, 3, 2);
        if (chanceRoll >= P_RIFT_HIGH) return null;
        double base = ground.at(x, z);
        if (!(base >= RIFT_LAND)) return null;
        if (chanceRoll >= P_RIFT_LOW + (P_RIFT_HIGH - P_RIFT_LOW) * smooth(90, 160, base)) return null;
        double u1 = GradientNoise.unit(cs, 4, 2), u2 = GradientNoise.unit(cs, 5, 2), u3 = GradientNoise.unit(cs, 6, 2);
        // Most rifts are short and shallow, a few are long and very deep.
        double planned = 300 + 2700 * Math.pow(u1, 1.7);
        double widthPlanned = clamp(10 + 0.009 * planned * (0.6 + 0.8 * u3), 10, 38);
        double angle = GradientNoise.unit(cs, 7, 2) * Math.PI;
        // A bent line: short segments (~200 blocks) wandering sideways and a bow over the whole length - a straight
        // 2 km fissure read as a canal (seed 31337 X-4098 Z-6901).
        int segments = 4 + (int) (planned / 220);
        double ca = Math.cos(angle), sa = Math.sin(angle), segment = planned / segments;
        double bow = (GradientNoise.unit(cs, 8, 2) - 0.5) * 0.22 * planned;
        double[] px = new double[segments + 1], pz = new double[segments + 1];
        double walk = 0;
        for (int k = 0; k <= segments; k++) {
            double along = -planned / 2 + k * segment, t = 2 * along / planned;
            if (k > 0 && k < segments) walk += (GradientNoise.unit(cs, 20 + k, 2) - 0.5) * 0.6 * segment;
            double lateral = walk + bow * (1 - t * t);
            px[k] = x + ca * along - sa * lateral;
            pz[k] = z + sa * along + ca * lateral;
        }
        // The rift stays on land: it ends two steps before the line or ground beside it comes down to the sea (a rift
        // floor below the sea level next to the sea was the sea - seed 31337: a straight water channel 2 km long).
        // The ground beside it is tested out to 60 blocks from its edge, with the volcanoes and craters on it: the open
        // air of a rift is dry, the native fill makes any other ground below the sea level sea water, and where the two
        // met a wall of water stood over the rift (audit 26.11 Opus N-1: a crater bowl at Y23 filled to Y62 on both
        // sides of a rift, seed 248163264 X-6870 Z5505; lowland and a saddle below the sea 8-24 blocks from its walls,
        // seeds 248163264 X2830 Z-8340, 902194 X-7672 Z-10610, -98765432 X11042 Z9938).
        Rift probe = new Rift(x, z, px, pz, 0, widthPlanned, new double[]{0, 0}, cs, wobble);
        double total = probe.length;
        int m = (int) Math.ceil(total / RIFT_STEP) + 1, mid = (int) Math.round(total / 2 / RIFT_STEP);
        boolean[] land = new boolean[m];
        for (int k = 0; k < m; k++) {
            double s = Math.min(total, k * RIFT_STEP);
            double[] p = probe.pointAt(s), q = probe.pointAt(Math.min(total, s + 1));
            double tx = q[0] - p[0], tz = q[1] - p[1], tl = Math.max(1e-6, Math.hypot(tx, tz));
            boolean ok = siteGround((int) Math.floor(p[0]), (int) Math.floor(p[1]), ground, cache) >= RIFT_LAND;
            for (int o = 0; o < RIFT_SIDES.length && ok; o++) {
                double side = widthPlanned / 2 + RIFT_SIDES[o], nx = -tz / tl * side, nz = tx / tl * side;
                ok = siteGround((int) Math.floor(p[0] + nx), (int) Math.floor(p[1] + nz), ground, cache) >= SEA + 1
                        && siteGround((int) Math.floor(p[0] - nx), (int) Math.floor(p[1] - nz), ground, cache) >= SEA + 1;
            }
            land[k] = ok;
        }
        if (!land[mid]) return null;
        int a = mid, b = mid;
        while (a > 0 && land[a - 1]) a--;
        while (b < m - 1 && land[b + 1]) b++;
        double s0 = a == 0 ? 0 : (a + 2) * RIFT_STEP, s1 = b == m - 1 ? total : (b - 2) * RIFT_STEP;
        // The ground beyond each end, too: low ground past the end of a rift stood as sea water at Y62 beside its dry
        // tip (seed 248163264 X2880 Z-8365). An end moves in a step at a time until the ground ahead of it is land.
        for (int guard = 0; guard < 96 && s1 - s0 >= 300; guard++) {
            boolean okA = tipLand(probe, s0, -1, widthPlanned, ground, cache), okB = tipLand(probe, s1, 1, widthPlanned, ground, cache);
            if (okA && okB) break;
            if (!okA) s0 += RIFT_STEP;
            if (!okB) s1 -= RIFT_STEP;
        }
        if (s1 - s0 < 300) return null;
        if (s0 > 0 || s1 < total) {
            // The part of the line between s0 and s1.
            List<double[]> kept = new ArrayList<>();
            kept.add(probe.pointAt(s0));
            for (int k = 1; k < px.length - 1; k++) if (probe.ps[k] > s0 && probe.ps[k] < s1) kept.add(new double[]{px[k], pz[k]});
            kept.add(probe.pointAt(s1));
            px = new double[kept.size()]; pz = new double[kept.size()];
            for (int k = 0; k < kept.size(); k++) { px[k] = kept.get(k)[0]; pz[k] = kept.get(k)[1]; }
            probe = new Rift(x, z, px, pz, 0, widthPlanned, new double[]{0, 0}, cs, wobble);
            total = probe.length;
        }
        double length = total;
        double depth = clamp(18 + 0.055 * length * (0.6 + 0.8 * u2), 18, RIFT_DEPTH_MAX);
        double width = clamp(10 + 0.009 * length * (0.6 + 0.8 * u3), 10, 38);
        // Floor: the low ground along the line (a running minimum over ~200 blocks) minus the depth, which tapers to
        // nothing at the ends; never below RIFT_FLOOR_MIN. Through a mountain the floor stays near the level of the low
        // ground on either side: the rift splits the mountain.
        int n = (int) Math.ceil(total / RIFT_STEP) + 1;
        double[] g = new double[n];
        for (int k = 0; k < n; k++) {
            double[] p = probe.pointAt(Math.min(total, k * RIFT_STEP));
            g[k] = ground.at((int) Math.floor(p[0]), (int) Math.floor(p[1]));
        }
        int window = 12;
        double[] floor = new double[n];
        for (int k = 0; k < n; k++) {
            double low = Double.POSITIVE_INFINITY;
            for (int q = Math.max(0, k - window); q <= Math.min(n - 1, k + window); q++) low = Math.min(low, g[q]);
            double t = Math.sin(Math.PI * Math.min(1, k * RIFT_STEP / total));
            double d = depth * Math.pow(t, 0.7) * (0.85 + 0.3 * wobble.value(k * RIFT_STEP / 160.0, 3.5 + (cs & 7)));
            floor[k] = Math.max(RIFT_FLOOR_MIN, Math.min(g[k] - 2, low - d));
            if (t < 0.02) floor[k] = Math.max(floor[k], g[k] - 2);
        }
        double[] smooth = floor.clone();
        for (int k = 1; k + 1 < n; k++) smooth[k] = (floor[k - 1] + 2 * floor[k] + floor[k + 1]) / 4;
        return new Rift(x, z, px, pz, depth, width, smooth, cs, wobble);
    }

    /** Whether the ground ahead of a rift's end at arc s (dir -1 before the start, +1 past the end) is land: on its axis
     * and half its width to either side, 8 to 72 blocks out. */
    private boolean tipLand(Rift probe, double s, int dir, double width, Ground ground, SiteCache cache) {
        double[] p = probe.pointAt(s), a = probe.pointAt(Math.max(0, s - 1)), b = probe.pointAt(Math.min(probe.length, s + 1));
        double tx = (b[0] - a[0]) * dir, tz = (b[1] - a[1]) * dir, tl = Math.hypot(tx, tz);
        if (tl < 1e-6) return true;
        tx /= tl; tz /= tl;
        for (double d : RIFT_TIP) {
            for (int side = -1; side <= 1; side++) {
                double lx = -tz * side * width / 2, lz = tx * side * width / 2;
                if (siteGround((int) Math.floor(p[0] + tx * d + lx), (int) Math.floor(p[1] + tz * d + lz), ground, cache) < SEA + 1) return false;
            }
        }
        return true;
    }
    /** Distances past a rift's end (blocks) at which its land test reads the ground. */
    static final double[] RIFT_TIP = {8, 24, 48, 72};
    /** Distances beside the edge of a rift (blocks) at which its land test reads the ground. */
    static final double[] RIFT_SIDES = {4, 16, 36, 60};
    /** Margin beyond the walls of a rift where its open air stays dry (HydroAquifer, HydroIce, seaFree). */
    public static final double RIFT_DRY_MARGIN = 8;

    /** The ground at (x, z) with the volcanoes and craters there, without the rifts: the ground a rift is laid on and a
     * crater rim is measured on (the base ground alone without a cache). */
    double siteGround(int x, int z, Ground ground, SiteCache cache) {
        double h = ground.at(x, z);
        if (cache == null) return h;
        List<Object> sites = new ArrayList<>(2);
        int vi = Math.floorDiv(x, VOLCANO_CELL), vj = Math.floorDiv(z, VOLCANO_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Volcano v = cache.volcano(vi + di, vj + dj, this, ground);
            if (v != null && Math.hypot(x - v.x, z - v.z) < v.reach()) sites.add(v);
        }
        int ci = Math.floorDiv(x, CRATER_CELL), cj = Math.floorDiv(z, CRATER_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Crater c = cache.crater(ci + di, cj + dj, this, ground);
            if (c != null && Math.hypot(x - c.x, z - c.z) < c.reach()) sites.add(c);
        }
        return sites.isEmpty() ? h : apply(x, z, h, sites, ground, cache);
    }

    /** Whether (x, z) lies in the bowl of a crater whose rim stands at or above the sea level all round. Its floor may
     * lie below the sea: there the native fill made sea water at Y62 in any climate, against the drainage model that
     * keeps a desert crater dry (audit 26.11 Opus N-9, code KO-08). Such a bowl is closed: the drainage model alone
     * decides its water (HydroField seaFree). A bowl open below the sea level stays sea. */
    public boolean closedBowl(List<Object> sites, int x, int z, Ground ground, SiteCache cache) {
        for (Object o : sites) {
            if (o instanceof Crater c && Math.hypot(x - c.x, z - c.z) < c.radius && rimLow(c, ground, cache) >= SEA) return true;
            // A caldera too: its floor lies up to 1.05 rim heights under the rim, below the sea on a low base, and the sea
            // filled a round "sea" at Y62 inside a ring of mountains to Y143 (audit 27.11 Opus N-2, code KO-02: seed
            // 486122604016 X-6646 Z10659).
            if (o instanceof Volcano v && v.kind == Kind.CALDERA && Math.hypot(x - v.x, z - v.z) < v.craterR && rimLow(v, ground, cache) >= SEA) return true;
        }
        return false;
    }
    /** Lowest point of a caldera's rim crest (48 points on its inner radius), measured once. */
    double rimLow(Volcano v, Ground ground, SiteCache cache) {
        double r = v.rimLow;
        if (!Double.isNaN(r)) return r;
        double low = Double.POSITIVE_INFINITY;
        for (int k = 0; k < 48; k++) {
            double a = k * Math.PI / 24;
            low = Math.min(low, siteGround((int) Math.round(v.x + Math.cos(a) * v.craterR), (int) Math.round(v.z + Math.sin(a) * v.craterR), ground, cache));
        }
        v.rimLow = low;
        return low;
    }
    /** Lowest point of a crater's rim crest (48 points on its radius), measured once. */
    double rimLow(Crater c, Ground ground, SiteCache cache) {
        double v = c.rimLow;
        if (!Double.isNaN(v)) return v;
        double low = Double.POSITIVE_INFINITY;
        for (int k = 0; k < 48; k++) {
            double a = k * Math.PI / 24;
            low = Math.min(low, siteGround((int) Math.round(c.x + Math.cos(a) * c.radius), (int) Math.round(c.z + Math.sin(a) * c.radius), ground, cache));
        }
        c.rimLow = low;
        return low;
    }

    /** Side of the influence cells of {@link #candidates}. */
    public static final int INFLUENCE_CELL = 512;

    /** Sites whose reach touches the influence cell (ci, cj) of INFLUENCE_CELL blocks (a superset of {@link #near} for
     * every point of the cell). */
    public List<Object> candidates(int ci, int cj, Ground ground, SiteCache cache) {
        List<Object> out = new ArrayList<>(2);
        double x0 = (double) ci * INFLUENCE_CELL, z0 = (double) cj * INFLUENCE_CELL, x1 = x0 + INFLUENCE_CELL, z1 = z0 + INFLUENCE_CELL;
        int vi = Math.floorDiv((int) x0 + INFLUENCE_CELL / 2, VOLCANO_CELL), vj = Math.floorDiv((int) z0 + INFLUENCE_CELL / 2, VOLCANO_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Volcano v = cache.volcano(vi + di, vj + dj, this, ground);
            if (v != null && boxDistance(v.x, v.z, x0, z0, x1, z1) < v.reach()) out.add(v);
        }
        int ki = Math.floorDiv((int) x0 + INFLUENCE_CELL / 2, CRATER_CELL), kj = Math.floorDiv((int) z0 + INFLUENCE_CELL / 2, CRATER_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Crater c = cache.crater(ki + di, kj + dj, this, ground);
            if (c != null && boxDistance(c.x, c.z, x0, z0, x1, z1) < c.reach()) out.add(c);
        }
        int ri = Math.floorDiv((int) x0 + INFLUENCE_CELL / 2, RIFT_CELL), rj = Math.floorDiv((int) z0 + INFLUENCE_CELL / 2, RIFT_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Rift r = cache.rift(ri + di, rj + dj, this, ground);
            if (r != null && r.maxX >= x0 && r.minX <= x1 && r.maxZ >= z0 && r.minZ <= z1) out.add(r);
        }
        return out;
    }
    private static double boxDistance(double px, double pz, double x0, double z0, double x1, double z1) {
        double dx = px < x0 ? x0 - px : px > x1 ? px - x1 : 0, dz = pz < z0 ? z0 - pz : pz > z1 ? pz - z1 : 0;
        return Math.hypot(dx, dz);
    }
    /** The candidates of a point's influence cell that really touch it. */
    public static List<Object> touching(List<Object> candidates, int x, int z) {
        if (candidates.isEmpty()) return candidates;
        List<Object> out = new ArrayList<>(candidates.size());
        for (Object o : candidates) {
            boolean in = o instanceof Volcano v ? Math.hypot(x - v.x, z - v.z) < v.reach()
                    : o instanceof Crater c ? Math.hypot(x - c.x, z - c.z) < c.reach()
                    : o instanceof Rift r && r.inBox(x, z);
            if (in) out.add(o);
        }
        return out;
    }

    /** All volcanoes, craters and rifts that can touch (x, z). */
    public List<Object> near(int x, int z, Ground ground, SiteCache cache) {
        List<Object> out = new ArrayList<>(2);
        int vi = Math.floorDiv(x, VOLCANO_CELL), vj = Math.floorDiv(z, VOLCANO_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Volcano v = cache.volcano(vi + di, vj + dj, this, ground);
            if (v != null && Math.hypot(x - v.x, z - v.z) < v.reach()) out.add(v);
        }
        int ci = Math.floorDiv(x, CRATER_CELL), cj = Math.floorDiv(z, CRATER_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Crater c = cache.crater(ci + di, cj + dj, this, ground);
            if (c != null && Math.hypot(x - c.x, z - c.z) < c.reach()) out.add(c);
        }
        int ri = Math.floorDiv(x, RIFT_CELL), rj = Math.floorDiv(z, RIFT_CELL);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Rift r = cache.rift(ri + di, rj + dj, this, ground);
            if (r != null && r.inBox(x, z)) out.add(r);
        }
        return out;
    }

    /** Height at (x, z) after the landforms, from the base height h there. */
    public double apply(int x, int z, double h, Ground ground, SiteCache cache) {
        return apply(x, z, h, near(x, z, ground, cache), ground, cache);
    }

    /** Height at (x, z) after the given sites (those touching the point). */
    public double apply(int x, int z, double h, List<Object> sites, Ground ground, SiteCache cache) {
        double out = h;
        if (sites.isEmpty()) return h;
        for (Object o : sites) if (o instanceof Volcano v) out = volcano(v, x, z, out, cache.base(v.x, v.z, ground));
        for (Object o : sites) if (o instanceof Volcano v && v.lavaFlows.length > 0) out = v.flowSurface(x, z, out);
        for (Object o : sites) if (o instanceof Crater c) out = crater(c, x, z, out, cache.base(c.x, c.z, ground));
        if (out > SOFT_TOP && out > h) { double e = out - SOFT_TOP; out = Math.max(h, SOFT_TOP + SOFT_ROOM * e / (SOFT_ROOM + e)); }
        for (Object o : sites) if (o instanceof Rift r) out = rift(r, x, z, out);
        return out;
    }

    double volcano(Volcano v, int x, int z, double h, double base) {
        double dx = x - v.x, dz = z - v.z, r = Math.hypot(dx, dz);
        switch (v.kind) {
            case STRATO: {
                if (r >= v.radius) return h;
                double u = r / v.radius, uc = v.craterR / v.radius;
                // The ground under the cone fades to the site level toward the summit: radial drainage, no hollows.
                double he = h + (base - h) * (1 - smooth(DAMP_IN, 1.0, u));
                // Concave profile: about 26 degrees near the summit, a few degrees at the foot.
                double f = Math.pow(1 - Math.max(u, uc * 0.6), 2.4);
                // Radial gullies: deeper on the middle flanks, shallow at the summit and the foot.
                // A noise read on a circle round the summit (continuous over the full turn) with a slow radial drift:
                // its zero lines are some 35 gullies that wander a little down the slope.
                double theta = Math.atan2(dz, dx), drift = r / 420.0;
                double g = gully.value(Math.cos(theta) * 3 + drift + (v.seed & 63), Math.sin(theta) * 3 + drift);
                double ridge = Math.max(0, 1 - 2.2 * Math.abs(g));
                double gullies = 1 - 0.16 * smooth(0.05, 0.45, u) * (1 - smooth(0.8, 1.0, u)) * ridge;
                // Never below the ground the cone stands on: a cone in a valley between plateaus damped the plateau
                // toward the valley floor and ringed itself with a moat, a lake of 0.63 km2 at Y121 round the cone
                // (audit 26.11 Opus N-3, code KO-03: seed 248163264 X-10676 Z10787). Lava and ash only add.
                double cone = Math.max(h, he + v.height * f * gullies);
                if (r < v.craterR) {
                    double rimTop = base + v.height * Math.pow(1 - uc, 2.4);
                    // A lava crater has a flat floor under steep walls (the floor is a cooled lake) and the pit of the
                    // living lake in it.
                    double e = v.lavaCrater() ? 4 : 2.4;
                    double floor = rimTop - v.craterD * (1 - Math.pow(r / v.craterR, e));
                    if (v.lavaCrater()) floor = pit(v, dx, dz, r, rimTop - v.craterD, floor);
                    cone = Math.min(cone, floor);
                }
                return cone;
            }
            case SHIELD: {
                if (r >= v.radius) return h;
                double u = r / v.radius;
                double he = h + (base - h) * (1 - smooth(DAMP_IN, 1.0, u));
                double f = Math.pow(1 - u * u, 1.5);
                double cone = Math.max(h, he + v.height * f);
                if (r < v.craterR) {
                    double top = base + v.height * Math.pow(1 - Math.pow(v.craterR / v.radius, 2), 1.5);
                    double floor = top - v.craterD * smooth(1.0, 0.75, r / v.craterR);
                    if (v.lavaCrater()) floor = pit(v, dx, dz, r, top - v.craterD, floor);
                    cone = Math.min(cone, floor);
                }
                return cone;
            }
            case CALDERA: {
                if (r >= v.radius) return h;
                double uc = v.craterR, outer = v.radius - uc;
                // The site level inside the caldera, fading to the local ground at the foot: the rim is one level.
                double he = h + (base - h) * (1 - smooth(uc, v.radius, r));
                if (r >= uc) {
                    double u = (r - uc) / outer;
                    return Math.max(h, he + v.height * Math.pow(1 - u, 1.4));
                }
                // Inner wall over the outer 18% of the radius, then a broad floor with a gentle bowl.
                double rimTop = base + v.height, floor = rimTop - v.craterD;
                double wall = smooth(0.82, 1.0, r / uc);
                double inner = floor - 4 * (1 - Math.pow(r / uc, 2)) + (rimTop - floor) * wall;
                return Math.min(he + v.height, inner);
            }
            default: {
                double best = h;
                for (int c = 0; c < v.coneX.length; c++) {
                    double rc = Math.hypot(x - v.coneX[c], z - v.coneZ[c]);
                    if (rc >= v.coneR[c]) continue;
                    double u = rc / v.coneR[c], cr = 0.22;
                    double cone = h + v.coneH[c] * Math.pow(1 - Math.max(u, cr * 0.7), 1.3);
                    if (u < cr) {
                        double rimTop = h + v.coneH[c] * Math.pow(1 - cr, 1.3);
                        cone = Math.min(cone, rimTop - v.coneH[c] * 0.35 * (1 - Math.pow(u / cr, 2)));
                    }
                    best = Math.max(best, cone);
                }
                return best;
            }
        }
    }
    /** Share of a cone's radius from the summit over which the ground under it is the site level. */
    static final double DAMP_IN = 0.30;

    /** The crater floor with the lava pit of an active volcano cut into it: steep walls (a cubic bowl) falling pitD
     * blocks from the floor level at its wandering outline. */
    static double pit(Volcano v, double dx, double dz, double r, double floorLevel, double floor) {
        double pr = v.pitRadius(Math.atan2(dz, dx));
        if (r >= pr) return floor;
        double s = r / pr;
        return Math.min(floor, floorLevel - v.pitD * (1 - s * s * s));
    }

    double crater(Crater c, int x, int z, double h, double base) {
        double r = Math.hypot(x - c.x, z - c.z), R = c.radius;
        if (r >= 3 * R) return h;
        double u = r / R, carve, rimTerm;
        if (u < 1) {
            if (c.complex) {
                // Flat floor within half the radius, terraced-looking steep wall, central peak.
                double wall = smooth(0.45, 1.0, u);
                carve = base - c.depth + c.depth * wall * wall;
                double peak = c.age == Age.ANCIENT ? 0 : c.depth * 0.32 * (1 - smooth(0.0, 0.16, u));
                carve += peak;
            } else {
                carve = base - c.depth * (1 - u * u);
            }
            if (c.age != Age.FRESH) {
                // Infill: the floor of old craters is flattened by sediment.
                double fill = base - c.depth * (c.age == Age.OLD ? 0.55 : 0.25);
                carve = Math.max(carve, Math.min(fill, base - 1));
            }
            rimTerm = c.rim * Math.pow(u, 6);
        } else {
            // Beyond the rim the excavation level climbs as a steep wall: uphill ground is cut into a crescent scarp of
            // that slope (one column held the whole scarp before - 13 blocks between two columns, OpenAI 26.11).
            carve = base + (c.age == Age.FRESH ? 1.6 : c.age == Age.OLD ? 0.7 : 0.35) * (r - R);
            // Ejecta blanket: rim height times (R/r)^3, faded to zero at 3R.
            rimTerm = c.rim * Math.pow(R / r, 3) * (1 - smooth(2.2, 3.0, u));
        }
        return Math.min(h, carve) + rimTerm;
    }

    double rift(Rift rf, int x, int z, double h) {
        double[] n = new double[3];
        if (!rf.nearest(x, z, n)) return h;
        double d = n[0], s = n[1], side = n[2];
        double w = rf.half(s, side);
        if (w <= 0 || d >= w + WALL_BAND) return h;
        // Talus and fallen blocks on the floor.
        double fl = rf.floorAt(s) + 2.2 * Math.max(0, rubble.fractal(x, z, 14, 2) + 0.15);
        double target = d <= w ? fl : fl + (h - fl) * smooth(0, 1, (d - w) / WALL_BAND);
        return Math.min(h, target);
    }
    /** Width of the wall of a rift beyond its floor (blocks; the terrain grid of 4 blocks makes it a steep slope). */
    static final double WALL_BAND = 2.5;

    /** Whether (x, z) lies inside a rift: the drainage network ends there (a sink - no lake fills it, rivers pour in and
     * end on its floor). The lava crater of an active volcano is a sink too, where its lake exists (the world checks
     * its lava level). */
    public static boolean sink(List<Object> sites, int x, int z) {
        double[] n = new double[3];
        for (Object o : sites)
            if (o instanceof Rift rf && rf.nearest(x, z, n) && n[0] < rf.half(n[1], n[2]) + WALL_BAND) return true;
        return false;
    }

    /** Whether (x, z) lies within margin blocks beyond the walls of a rift. */
    public static boolean nearRift(List<Object> sites, int x, int z, double margin) {
        double[] n = new double[3];
        for (Object o : sites)
            if (o instanceof Rift rf && rf.nearest(x, z, n) && n[0] < rf.half(n[1], n[2]) + WALL_BAND + margin) return true;
        return false;
    }

    /** The axis point of a rift nearest to (cx, cz) when it lies in the cell [cx - half, cx + half) on both axes (each
     * point belongs to one cell: a rift along the line midway between two cell centres was snapped by neither) and the
     * rift is at least 8 blocks wide there (not at its tapering tips), or null. */
    public static int[] riftSnap(Rift rf, int cx, int cz, int half) {
        double[] n = new double[3];
        if (!rf.nearest(cx, cz, n)) return null;
        double[] p = rf.pointAt(n[1]);
        if (p[0] >= cx - half && p[0] < cx + half && p[1] >= cz - half && p[1] < cz + half && rf.half(n[1], 1) >= 4 && rf.half(n[1], -1) >= 4)
            return new int[]{(int) Math.floor(p[0]), (int) Math.floor(p[1])};
        // The nearest point of the whole axis lies outside the cell, but the axis may still cross it (a corner): the point
        // of the axis inside the cell nearest to its centre. Before, 15% of the crossed cells kept their node outside the
        // rift (code audit 27.11 OpenAI K-03).
        double best = Double.POSITIVE_INFINITY;
        int[] out = null;
        double x0 = cx - half, x1 = cx + half - 1e-6, z0 = cz - half, z1 = cz + half - 1e-6;
        for (int i = 0; i + 1 < rf.px.length; i++) {
            double ax = rf.px[i], az = rf.pz[i], dx = rf.px[i + 1] - ax, dz = rf.pz[i + 1] - az;
            double t0 = 0, t1 = 1;
            double[] pp = {-dx, dx, -dz, dz}, qq = {ax - x0, x1 - ax, az - z0, z1 - az};
            boolean in = true;
            for (int k = 0; k < 4 && in; k++) {
                if (pp[k] == 0) { if (qq[k] < 0) in = false; }
                else { double t = qq[k] / pp[k]; if (pp[k] < 0) t0 = Math.max(t0, t); else t1 = Math.min(t1, t); }
            }
            if (!in || t0 > t1) continue;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-9 ? t0 : Math.max(t0, Math.min(t1, ((cx - ax) * dx + (cz - az) * dz) / len2));
            double qx = ax + dx * t, qz = az + dz * t, d = Math.hypot(qx - cx, qz - cz), arc = rf.ps[i] + t * Math.sqrt(len2);
            if (d < best && rf.half(arc, 1) >= 4 && rf.half(arc, -1) >= 4) { best = d; out = new int[]{(int) Math.floor(qx), (int) Math.floor(qz)}; }
        }
        return out;
    }

    /** Lava level of an active summit crater: two blocks under the lowest point round its pit (the final ground at 32
     * points three blocks beyond the pit outline, on the crater floor), or NaN when the crater holds no lake (not
     * active, a seamount or a crater near the sea, or a lake under three blocks deep). */
    public double lavaLevel(Volcano v, Ground ground, SiteCache cache) {
        if (!v.lavaCrater()) return Double.NaN;
        double low = Double.POSITIVE_INFINITY;
        for (int k = 0; k < 32; k++) {
            double a = k * Math.PI / 16, d = Math.min(v.pitRadius(a) + 3, v.craterR - 1);
            int px = (int) Math.round(v.x + Math.cos(a) * d), pz = (int) Math.round(v.z + Math.sin(a) * d);
            low = Math.min(low, apply(px, pz, ground.at(px, pz), near(px, pz, ground, cache), ground, cache));
        }
        double centre = apply(v.x, v.z, ground.at(v.x, v.z), near(v.x, v.z, ground, cache), ground, cache);
        double level = Math.floor(low) - 2;
        if (level < centre + 3 || level <= SEA + 3) return Double.NaN;
        return level;
    }

    /** Memo of sites and site ground heights (pure: the same values whichever thread fills them). */
    public interface SiteCache {
        Volcano volcano(int i, int j, Landforms model, Ground ground);
        Crater crater(int i, int j, Landforms model, Ground ground);
        double base(int x, int z, Ground ground);
        default Rift rift(int i, int j, Landforms model, Ground ground) { return model.rift(i, j, ground, this); }
    }

    /** A simple bounded memo for tests and tools. */
    public static final class MapCache implements SiteCache {
        private final java.util.Map<Long, Object> sites = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<Long, Double> bases = new java.util.concurrent.ConcurrentHashMap<>();
        private static final Object NONE = new Object();
        @Override public Volcano volcano(int i, int j, Landforms m, Ground g) {
            long key = key(i, j) ^ 0x5555555555555555L;
            Object o = sites.get(key);
            if (o == null) { Volcano v = m.volcano(i, j, g); o = v == null ? NONE : v; trim(); sites.put(key, o); }
            return o == NONE ? null : (Volcano) o;
        }
        @Override public Crater crater(int i, int j, Landforms m, Ground g) {
            long key = key(i, j) ^ 0x2AAAAAAAAAAAAAAAL;
            Object o = sites.get(key);
            if (o == null) { Crater c = m.crater(i, j, g); o = c == null ? NONE : c; trim(); sites.put(key, o); }
            return o == NONE ? null : (Crater) o;
        }
        @Override public Rift rift(int i, int j, Landforms m, Ground g) {
            long key = key(i, j) ^ 0x1234567876543210L;
            Object o = sites.get(key);
            if (o == null) { Rift r = m.rift(i, j, g, this); o = r == null ? NONE : r; trim(); sites.put(key, o); }
            return o == NONE ? null : (Rift) o;
        }
        @Override public double base(int x, int z, Ground g) {
            return bases.computeIfAbsent(key(x, z), k -> g.at(x, z));
        }
        private void trim() { if (sites.size() > 8192) sites.clear(); if (bases.size() > 8192) bases.clear(); }
        private static long key(int i, int j) { return GradientNoise.mix(((long) i << 32) ^ (j & 0xFFFFFFFFL)); }
    }

    /** A sum, not a xor: with a xor the cells with odd i and j repeated the volcano or crater of the cell mirrored
     * through the origin ((-i)A ^ (-j)B == iA ^ jB for odd A, B) - a quarter of them had a twin. */
    long cellSeed(int i, int j, long salt) { return GradientNoise.mix(seed ^ GradientNoise.mix(i * 0x9E3779B97F4A7C15L + j * 0xC2B2AE3D27D4EB4FL + salt)); }
    static double smooth(double a, double b, double v) {
        double t = (v - a) / (b - a);
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        return t * t * (3 - 2 * t);
    }
    static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
}
