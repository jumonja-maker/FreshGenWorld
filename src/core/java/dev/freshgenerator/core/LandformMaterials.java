package dev.freshgenerator.core;

import java.util.List;

/** Ground cover of the rare landforms ({@link Landforms}): volcanic rock on the upper cones, lava flows radiating down
 * the flanks (fresh, dark and glowing on active volcanoes, with a burnt strip of ash beside them), scoria cones, ash,
 * basalt and glowing vents on seamounts, the breccia, melt and ejecta of fresh impact craters, and talus on the floor of
 * rifts. Lower volcano slopes and old crater floors keep the biome's own soil (forests climb the lower flanks; old crater
 * floors are sediment). */
public final class LandformMaterials {
    private LandformMaterials() { }
    public enum Cover { NATIVE, BASALT, SMOOTH_BASALT, BLACKSTONE, TUFF, ANDESITE, GRAVEL, COARSE_DIRT, MAGMA, OBSIDIAN, STONE }

    /** Cover at (x, y, z); top = the floor block (else the few blocks under it); wet = under water (only seamounts and
     * the drowned flanks of volcanic islands take a cover there). */
    public static Cover pick(List<Object> sites, int x, int y, int z, boolean top, boolean wet, long seed, GradientNoise patch) {
        Cover best = Cover.NATIVE;
        for (Object o : sites) {
            Cover c;
            if (o instanceof Landforms.Volcano v) c = wet && !v.ocean ? Cover.NATIVE : wet ? submarine(v, x, z, top, seed, patch) : volcano(v, x, z, top, seed, patch);
            else if (wet) c = Cover.NATIVE;
            else if (o instanceof Landforms.Crater k) c = crater(k, x, z, top, seed, patch);
            else if (o instanceof Landforms.Rift r) c = rift(r, x, z, top, seed, patch);
            else c = Cover.NATIVE;
            if (c != Cover.NATIVE) best = c;
        }
        return best;
    }

    /** Width of the burnt strip beside a fresh flow: this multiple of its half-width, at most BURN_MAX blocks. */
    static final double BURN = 1.8, BURN_MAX = 20;
    /** Whether (x, z) lies in the burnt strip beside a fresh flow (q and the flow's {share, half-width, distance}). */
    static boolean burnt(double q, double[] f) {
        return q >= 1 && f[2] - f[1] < Math.min(BURN * f[1], BURN_MAX);
    }

    static Cover volcano(Landforms.Volcano v, int x, int z, boolean top, long seed, GradientNoise patch) {
        double dx = x - v.x, dz = z - v.z, r = Math.hypot(dx, dz), theta = Math.atan2(dz, dx);
        double h = hash(seed, x, z), p = patch.value(x / 7.0, z / 7.0);
        switch (v.kind) {
            case STRATO: {
                double u = r / v.radius;
                if (r < v.craterR * 1.15) return top ? (v.active() && h < 0.18 ? Cover.MAGMA : h < 0.55 ? Cover.BLACKSTONE : h < 0.8 ? Cover.BASALT : Cover.SMOOTH_BASALT) : Cover.BLACKSTONE;
                Cover flow = flow(v, x, z, u, top, h, p, 0.82);
                if (flow != Cover.NATIVE) return flow;
                double edge = 0.52 + 0.10 * patch.value(Math.cos(theta) * 2.2, Math.sin(theta) * 2.2);
                if (u > edge) return Cover.NATIVE;
                if (!top) return u < 0.3 ? Cover.TUFF : Cover.ANDESITE;
                if (u < 0.28) return p > 0.25 ? Cover.GRAVEL : h < 0.62 ? Cover.TUFF : h < 0.8 ? Cover.BLACKSTONE : Cover.SMOOTH_BASALT;
                return p > 0.3 ? Cover.GRAVEL : h < 0.38 ? Cover.ANDESITE : h < 0.62 ? Cover.TUFF : h < 0.82 ? Cover.STONE : Cover.COARSE_DIRT;
            }
            case SHIELD: {
                double u = r / v.radius;
                if (r < v.craterR * 1.1) return top ? (v.active() && h < 0.2 ? Cover.MAGMA : h < 0.6 ? Cover.BLACKSTONE : Cover.BASALT) : Cover.BLACKSTONE;
                Cover flow = flow(v, x, z, u, top, h, p, 0.9);
                if (flow != Cover.NATIVE) return flow;
                double edge = 0.62 + 0.12 * patch.value(Math.cos(theta) * 2.6, Math.sin(theta) * 2.6);
                if (u > edge) return Cover.NATIVE;
                if (!top) return Cover.BASALT;
                return p > 0.35 ? Cover.SMOOTH_BASALT : h < 0.5 ? Cover.BASALT : h < 0.8 ? Cover.BLACKSTONE : Cover.TUFF;
            }
            case CALDERA: {
                if (r < v.craterR) {
                    // Inner walls of tuff and andesite; the floor keeps its sediment (or lake bed).
                    double wall = (r / v.craterR);
                    if (wall < 0.80) return Cover.NATIVE;
                    return !top ? Cover.TUFF : h < 0.45 ? Cover.TUFF : h < 0.8 ? Cover.ANDESITE : Cover.GRAVEL;
                }
                double u = (r - v.craterR) / (v.radius - v.craterR);
                double edge = 0.30 + 0.08 * patch.value(Math.cos(theta) * 2.4, Math.sin(theta) * 2.4);
                if (u > edge) return Cover.NATIVE;
                return !top ? Cover.ANDESITE : p > 0.3 ? Cover.GRAVEL : h < 0.5 ? Cover.TUFF : Cover.ANDESITE;
            }
            default: {
                for (int c = 0; c < v.coneX.length; c++) {
                    double rc = Math.hypot(x - v.coneX[c], z - v.coneZ[c]);
                    if (rc >= v.coneR[c] * 0.85) continue;
                    double u = rc / v.coneR[c];
                    if (u < 0.2) return top && h < 0.12 && v.active() ? Cover.MAGMA : Cover.BLACKSTONE;
                    return !top ? Cover.BLACKSTONE : h < 0.45 ? Cover.BLACKSTONE : h < 0.75 ? Cover.BASALT : Cover.TUFF;
                }
                return Cover.NATIVE;
            }
        }
    }

    /** The traced lava flows (Landforms.Flow): old flows of basalt and blackstone on the cone, overgrown beyond its
     * foot (their relief stays); fresh black flows with glowing magma - most near the vent, cooled toward the snout -
     * and a burnt strip of ash and cinders beside them. Nothing beyond the shore of a volcanic island. The last argument
     * is unused (the flows have their own extent). */
    static Cover flow(Landforms.Volcano v, int x, int z, double u, boolean top, double h, double p, double reach) {
        if (u > v.shore() + 0.04) return Cover.NATIVE;
        double[] f = new double[3];
        double q = v.flowQ(x, z, f);
        if (q < 1) {
            if (!v.active()) return u > 1.0 ? Cover.NATIVE : h < 0.6 ? Cover.BASALT : Cover.BLACKSTONE;
            if (!top) return h < 0.7 ? Cover.BLACKSTONE : Cover.BASALT;
            double magma = 0.16 * (1 - 0.75 * f[0]);
            return h < magma ? Cover.MAGMA : h < magma + 0.46 ? Cover.BLACKSTONE : h < magma + 0.69 ? Cover.BASALT : Cover.SMOOTH_BASALT;
        }
        if (v.active() && top && burnt(q, f)) {
            // Burnt strip: ash and cinders thinning out away from the flow.
            double keep = (f[2] - f[1]) / Math.min(BURN * f[1], BURN_MAX);
            if (h > keep * 0.9) return p > 0.2 ? Cover.GRAVEL : h < 0.5 ? Cover.COARSE_DIRT : Cover.TUFF;
        }
        return Cover.NATIVE;
    }

    /** Seamounts and the drowned flanks of volcanic islands: basalt pillows, tuff and cinders; glowing vents (magma,
     * bubble columns) in the crater of an active one. */
    static Cover submarine(Landforms.Volcano v, int x, int z, boolean top, long seed, GradientNoise patch) {
        double r = Math.hypot(x - v.x, z - v.z), h = hash(seed ^ 0x51, x, z), p = patch.value(x / 6.0, z / 6.0);
        if (v.kind == Landforms.Kind.CINDER) {
            for (int c = 0; c < v.coneX.length; c++) {
                double rc = Math.hypot(x - v.coneX[c], z - v.coneZ[c]);
                if (rc >= v.coneR[c] * 0.85) continue;
                if (rc < v.coneR[c] * 0.2) return top && h < 0.15 && v.active() ? Cover.MAGMA : Cover.BLACKSTONE;
                return h < 0.5 ? Cover.BLACKSTONE : Cover.BASALT;
            }
            return Cover.NATIVE;
        }
        double u = r / Math.max(1, v.radius);
        if (r < v.craterR * 1.1) return top ? (v.active() && h < 0.25 ? Cover.MAGMA : h < 0.6 ? Cover.BLACKSTONE : Cover.BASALT) : Cover.BASALT;
        if (u > 0.75 + 0.1 * p) return Cover.NATIVE;
        if (!top) return Cover.BASALT;
        return p > 0.32 ? Cover.GRAVEL : h < 0.45 ? Cover.BASALT : h < 0.7 ? Cover.SMOOTH_BASALT : h < 0.9 ? Cover.TUFF : Cover.BLACKSTONE;
    }

    static Cover crater(Landforms.Crater k, int x, int z, boolean top, long seed, GradientNoise patch) {
        double r = Math.hypot(x - k.x, z - k.z), u = r / k.radius;
        double h = hash(seed ^ 0x77, x, z), p = patch.value(x / 6.0, z / 6.0);
        if (k.age == Landforms.Age.ANCIENT) return Cover.NATIVE;
        if (k.age == Landforms.Age.OLD) {
            // Exposed rock on the walls only; the infilled floor and the rim keep their soil.
            if (u > 0.62 && u < 1.1 && p > 0.05) return top ? (h < 0.5 ? Cover.STONE : Cover.GRAVEL) : Cover.STONE;
            return Cover.NATIVE;
        }
        if (u < 1.0) {
            if (!top) return h < 0.5 ? Cover.TUFF : Cover.STONE;
            // Impact melt glass near the centre, breccia (tuff, gravel, smooth basalt) over the floor and walls.
            if (u < 0.32 && h < 0.10) return Cover.OBSIDIAN;
            return p > 0.28 ? Cover.GRAVEL : h < 0.42 ? Cover.TUFF : h < 0.62 ? Cover.SMOOTH_BASALT : h < 0.85 ? Cover.ANDESITE : Cover.STONE;
        }
        if (u < 1.35 && top) return p > 0.1 ? (h < 0.5 ? Cover.COARSE_DIRT : Cover.GRAVEL) : h < 0.4 ? Cover.STONE : Cover.NATIVE;
        if (u < 2.1 && top) {
            double keep = 0.55 * (2.1 - u) / 0.75;
            if (h < keep * 0.5) return p > 0 ? Cover.GRAVEL : Cover.COARSE_DIRT;
        }
        // Scorched ground of the blast zone: bare coarse soil thinning out to three radii.
        if (u < 3.0 && top && h < 0.6 * (1 - Landforms.smooth(1.2, 3.0, u))) return Cover.COARSE_DIRT;
        return Cover.NATIVE;
    }

    /** The floor of a rift: talus of fallen blocks (gravel, stone, andesite, coarse soil). Its walls take the cliff rock
     * of the native rules. */
    static Cover rift(Landforms.Rift rf, int x, int z, boolean top, long seed, GradientNoise patch) {
        double[] n = new double[3];
        if (!rf.nearest(x, z, n) || n[0] > rf.half(n[1], n[2])) return Cover.NATIVE;
        double h = hash(seed ^ 0x3A, x, z), p = patch.value(x / 5.0, z / 5.0);
        if (!top) return h < 0.7 ? Cover.STONE : Cover.ANDESITE;
        return p > 0.25 ? Cover.GRAVEL : h < 0.35 ? Cover.STONE : h < 0.6 ? Cover.ANDESITE : h < 0.8 ? Cover.COARSE_DIRT : Cover.TUFF;
    }

    /** Share of trees left standing at (x, z): fresh impact craters burn and fell the forest (0 inside the crater and
     * on the rim, rising through the blast zone to 1 at three radii); active volcanoes burn the upper cone and a strip
     * along each fresh flow. */
    public static double blastSurvival(List<Object> sites, int x, int z) {
        double s = 1;
        for (Object o : sites) {
            if (o instanceof Landforms.Crater k && k.age == Landforms.Age.FRESH) {
                double u = Math.hypot(x - k.x, z - k.z) / k.radius;
                s = Math.min(s, Landforms.smooth(1.15, 3.0, u));
            } else if (o instanceof Landforms.Volcano v && v.active() && !v.ocean) s = Math.min(s, volcanicSurvival(v, x, z));
        }
        return s;
    }
    /** Share of trees left standing round an active volcano (1 = untouched). */
    public static double volcanicSurvival(Landforms.Volcano v, int x, int z) {
        double r = Math.hypot(x - v.x, z - v.z);
        if (v.kind == Landforms.Kind.CINDER) {
            double s = 1;
            for (int c = 0; c < v.coneX.length; c++) {
                double u = Math.hypot(x - v.coneX[c], z - v.coneZ[c]) / v.coneR[c];
                s = Math.min(s, Landforms.smooth(0.7, 1.4, u));
            }
            return s;
        }
        double u = r / Math.max(1, v.radius);
        double cone = Landforms.smooth(0.30, 0.42, u);
        double[] f = new double[3];
        double q = v.flowQ(x, z, f);
        if (q < 1) return 0;
        if (!burnt(q, f)) return cone;
        double edge = Math.min(BURN * f[1], BURN_MAX);
        return Math.min(cone, Landforms.smooth(0.15 * edge, edge, f[2] - f[1]));
    }
    /** Hot ground where no snow lies: the summit crater of an active volcano and its fresh lava flows (dark streaks down
     * a snowy cone, as on Klyuchevskaya or Villarrica). */
    public static boolean hot(List<Object> sites, int x, int z) {
        for (Object o : sites) {
            if (!(o instanceof Landforms.Volcano v) || !v.active()) continue;
            if (v.kind == Landforms.Kind.CINDER) {
                for (int c = 0; c < v.coneX.length; c++) if (Math.hypot(x - v.coneX[c], z - v.coneZ[c]) < v.coneR[c] * 0.25) return true;
                continue;
            }
            double r = Math.hypot(x - v.x, z - v.z);
            if (r < v.craterR * 1.15) return true;
            double u = r / Math.max(1, v.radius);
            if (u <= v.shore() + 0.04 && v.flowQ(x, z, null) < 1) return true;
        }
        return false;
    }

    /** Whether no surface structure (village, outpost, temple, camp) may stand at (x, z): the upper cone of a
     * stratovolcano, the summit of a shield, the steep rim of a caldera, cinder cones, the cone, flows and burnt
     * ground of an active volcano, a fresh impact crater with its rim and ejecta, the walls of an old one, a rift and its
     * brink. A village stood on the ash cone of an active volcano 250 blocks from its lava lake (seed 31337 X-2708
     * Z-7814). */
    public static boolean noBuild(List<Object> sites, int x, int z) {
        double[] n = new double[3];
        for (Object o : sites) {
            if (o instanceof Landforms.Volcano v) {
                double r = Math.hypot(x - v.x, z - v.z);
                switch (v.kind) {
                    case STRATO -> { if (r < v.radius * (v.active() ? 0.62 : 0.48)) return true; }
                    case SHIELD -> { if (r < Math.max(v.craterR * 1.4, v.radius * (v.active() ? 0.45 : 0.2))) return true; }
                    case CALDERA -> { if (r > v.craterR * 0.8 && r < v.craterR + 0.3 * (v.radius - v.craterR)) return true; }
                    default -> { for (int c = 0; c < v.coneX.length; c++) if (Math.hypot(x - v.coneX[c], z - v.coneZ[c]) < v.coneR[c]) return true; }
                }
                if (v.active() && !v.ocean && volcanicSurvival(v, x, z) < 1) return true;
            } else if (o instanceof Landforms.Crater k) {
                double u = Math.hypot(x - k.x, z - k.z) / k.radius;
                if (k.age == Landforms.Age.FRESH ? u < 1.5 : k.age == Landforms.Age.OLD && u > 0.6 && u < 1.15) return true;
            } else if (o instanceof Landforms.Rift rf) {
                if (rf.nearest(x, z, n) && n[0] < rf.half(n[1], n[2]) + 14) return true;
            }
        }
        return false;
    }

    /** Share of the lost trees at (x, z) that remain as charred trunks: none on the cone of an active volcano (bare ash
     * and rock above its tree line - a dense stand of snags covered the whole upper cone, seed -582934 X9484 Z3253) and
     * none on its fresh lava, standing snags in the burnt belt below the cone and beside the flows, felled trunks in the
     * blast zone of a fresh crater. */
    public static double remains(List<Object> sites, int x, int z) {
        for (Object o : sites) {
            if (!(o instanceof Landforms.Volcano v) || !v.active() || v.ocean || volcanicSurvival(v, x, z) >= 1) continue;
            if (v.kind == Landforms.Kind.CINDER) {
                double u = Double.POSITIVE_INFINITY;
                for (int c = 0; c < v.coneX.length; c++) u = Math.min(u, Math.hypot(x - v.coneX[c], z - v.coneZ[c]) / v.coneR[c]);
                return 0.4 * Landforms.smooth(0.85, 1.0, u);
            }
            double u = Math.hypot(x - v.x, z - v.z) / Math.max(1, v.radius);
            if (v.flowQ(x, z, null) < 1.1) return 0;
            return 0.45 * Landforms.smooth(0.27, 0.36, u);
        }
        return 0.4;
    }

    /** Whether the native eroded-badlands pillar is left out at (x, z): in and beside a rift, in the bowl of an impact
     * crater (not an ancient one: it is only a low ring), in a summit crater and in a caldera. The pillars rise to an
     * absolute height and are filled down to the ground: in a deep bowl they stood as fins from its floor. */
    public static boolean noPillar(List<Object> sites, int x, int z) {
        if (Landforms.nearRift(sites, x, z, 3)) return true;
        for (Object o : sites) {
            if (o instanceof Landforms.Crater k) {
                if (k.age != Landforms.Age.ANCIENT && Math.hypot(x - k.x, z - k.z) < k.radius * 1.15) return true;
            } else if (o instanceof Landforms.Volcano v && v.kind != Landforms.Kind.CINDER) {
                if (Math.hypot(x - v.x, z - v.z) < v.craterR * 1.1) return true;
            }
        }
        return false;
    }

    /** Whether the trees at (x, z) were burnt by an active volcano (standing charred trunks rather than a felled blast). */
    public static boolean volcanicBurn(List<Object> sites, int x, int z) {
        for (Object o : sites) if (o instanceof Landforms.Volcano v && v.active() && !v.ocean && volcanicSurvival(v, x, z) < 1) return true;
        return false;
    }

    static double hash(long seed, int x, int z) { return GradientNoise.unit(seed, x, z); }
}
