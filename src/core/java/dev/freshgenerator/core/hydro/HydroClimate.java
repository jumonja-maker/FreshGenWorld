package dev.freshgenerator.core.hydro;

/** Landscape settings and their hydrological parameters (docs/hydrology-1.0.md, sections 3.5 and 8).
 * Units: areas in km² (1 block = 1 m), flow in km² of runoff-weighted catchment ("effective area"),
 * heights in blocks. Values are procedural calibrations on the game scale, not measured constants. */
public final class HydroClimate {
    private HydroClimate() { }

    /** Hydrological landscape setting of a lattice node.
     * wetK scales the flow at which a channel carries permanent water (savannas need far more catchment than
     * forests, mountains far less); lakeMax/lakeMean are the block-scale depth (max, mean) a hollow needs to hold an
     * open lake rather than a sheet of water - lake districts (tundra, taiga, wetlands) accept shallower lakes;
     * lakeShare is the share of the lake area that must hold at least two blocks of water: one deeper hole no longer
     * passes a sheet of tens of thousands of 1-block columns as a lake (audit 23.11 B-1/R-2: taiga of seed 31337
     * 38.6% under water, 98.8% of it 1 block deep). Tropical hollows need more depth (B-3: 8.6% of the land). */
    public enum Setting {
        //         runoff  channel km²  width  depth  lake coarse  wetK  lakeMax lakeMean  share  arid   semi
        ARCTIC   (0.60,   0.18,        1.10,  0.90,  0.8,         0.8,  1.5,    0.5,      0.35,  false, false),
        BOREAL   (0.80,   0.15,        1.00,  1.00,  1.0,         0.9,  1.5,    0.6,      0.35,  false, false),
        ALPINE   (1.25,   0.03,        0.80,  0.85,  1.2,         0.4,  2.0,    0.8,      0.35,  false, false),
        TEMPERATE(0.95,   0.12,        1.00,  1.00,  4.0,         1.0,  3.0,    1.2,      0.35,  false, false),
        WETLAND  (1.05,   0.10,        1.10,  0.80,  0.8,         0.7,  1.0,    0.3,      0.10,  false, false),
        TROPICAL (1.45,   0.09,        1.35,  1.20,  6.0,         0.9,  4.0,    1.6,      0.35,  false, false),
        SAVANNA  (0.35,   0.10,        1.40,  0.70,  3.0,         2.5,  2.5,    1.0,      0.35,  false, true),
        DESERT   (0.015,  0.06,        1.30,  0.60,  99,          1.0,  99,     99,       99,    true,  false),
        BADLANDS (0.02,   0.03,        1.10,  0.60,  99,          1.0,  99,     99,       99,    true,  false);

        public final double runoff, channelArea, widthK, depthK, lakeDepth, wetK, lakeMax, lakeMean, lakeShare;
        public final boolean arid, semiArid;
        Setting(double runoff, double channelArea, double widthK, double depthK, double lakeDepth, double wetK, double lakeMax,
                double lakeMean, double lakeShare, boolean arid, boolean semiArid) {
            this.runoff = runoff; this.channelArea = channelArea; this.widthK = widthK; this.depthK = depthK;
            this.lakeDepth = lakeDepth; this.wetK = wetK; this.lakeMax = lakeMax; this.lakeMean = lakeMean; this.lakeShare = lakeShare;
            this.arid = arid; this.semiArid = semiArid;
        }
        public boolean cold() { return this == ARCTIC || this == BOREAL || this == ALPINE; }
    }

    /** Valley cross-section beyond the channel: floodplain half-extra (absolute + per channel width),
     * bank height above water, then the valley side rises s1*x + s2*x^2 (x beyond the floodplain). */
    public enum Valley {
        //        fpAbs  fpW   bank  s1    s2
        V        (0.5,   0.2,  1.0,  0.75, 0.020),
        HILL     (1.5,   0.6,  1.0,  0.40, 0.010),
        LOWLAND  (4.0,   1.6,  1.0,  0.14, 0.004),
        U        (3.0,   1.2,  1.0,  0.18, 0.040),
        CANYON   (0.5,   0.3,  2.0,  2.40, 0.000),
        WADI     (0.0,   0.0,  1.5,  0.80, 0.010),
        /** River mouths: a broad low valley opening to the sea instead of a slot cut through the coastal plain
         * (audit 23.11 B-6: a desert stream 4-5 blocks below the plain 150 blocks from the sea). */
        ESTUARY  (5.0,   1.5,  0.6,  0.12, 0.002);

        public final double fpAbs, fpW, bank, s1, s2;
        Valley(double fpAbs, double fpW, double bank, double s1, double s2) {
            this.fpAbs = fpAbs; this.fpW = fpW; this.bank = bank; this.s1 = s1; this.s2 = s2;
        }
        public double floodplain(double width) { return fpAbs + fpW * width; }
        /** Height of the valley side above the bank top at x blocks beyond the floodplain. */
        public double rise(double x) { return x <= 0 ? 0 : s1 * x + s2 * x * x; }
        /** Carving reach beyond the floodplain; the profile is capped here. */
        public static final double REACH = 96;
    }

    /** Altitude cooling of the surface climate - the very curve of the biome surface temperature
     * (landscape/climate/altitude_cooling: 0.42 x quintic smootherstep of (H - 96) / 176): hydrology and biomes
     * see the same climate on a mountain (audits 22.11 N-05, 23.11 C02/H05). */
    public static double cooled(double zoneTemperature, double height) {
        double u = clamp((height - 96) * 0.0056818184, 0, 1);
        // Weaker cooling in the hot zone (pack ClimateShapeCompiler HOT_*: hot summits stay snow-free).
        double hot = 1 - HOT_RELIEF * smooth(HOT_T0, HOT_T1, zoneTemperature);
        return zoneTemperature - 0.42 * u * u * u * (10 - 15 * u + 6 * u * u) * hot;
    }
    public static final double HOT_T0 = 0.45, HOT_T1 = 0.60, HOT_RELIEF = 0.25;
    /** Landscape setting of a point from its zone temperature (altitude-cooled here). */
    public static Setting setting(double zoneTemperature, double moisture, double erosion, double height) {
        double t = cooled(zoneTemperature, height);
        return classify(t, dryBelt(t, moisture), erosion, height);
    }

    /** The biome table's dry belt (pack ClimateNicheCompiler, same constants, checked by a test): the lookup humidity
     * is capped at BELT_MOISTURE between BELT_FULL and BELT_END of the surface temperature, ramped in from
     * BELT_START and released at DESERT_T. The water applies it to the regional humidity, so a savanna picked by the
     * belt drains like a savanna, not like a rain forest (audit 23.11: seed 8675309 X-11982 Z-6037). */
    public static final double BELT_START = .46, BELT_FULL = .49, BELT_END = .5500, DESERT_T = .5510, BELT_MOISTURE = -.15;
    public static double dryBelt(double surfaceTemperature, double moisture) {
        double t = surfaceTemperature, cap;
        if (t <= BELT_START || t >= DESERT_T) return moisture;
        if (t < BELT_FULL) cap = hermite(BELT_START, 1, BELT_FULL, BELT_MOISTURE, t);
        else if (t <= BELT_END) cap = BELT_MOISTURE;
        else cap = hermite(BELT_END, BELT_MOISTURE, DESERT_T, 1, t);
        return Math.min(moisture, cap);
    }
    /** Spline segment with zero end derivatives (as the native spline points with derivative 0). */
    private static double hermite(double a, double va, double b, double vb, double t) {
        double u = (t - a) / (b - a);
        return va + (vb - va) * u * u * (3 - 2 * u);
    }

    /** Continuous hydrological parameters of a point: the class parameters averaged over a box around it in
     * (cooled temperature, moisture, erosion, height). The classes are axis-aligned regions, so the box splits
     * exactly into cells of one class each; every cell weighs its share of the box volume and the smooth factors
     * (humidity, orographic gain) are integrated over it. The parameters are therefore continuous functions of
     * the climate - no step where a class border crosses the box (audit 23.11 F-03/FG3-04/H03/C01). */
    public record Params(double runoff, double channelArea, double widthK, double depthK, double lakeDepth, double wetK,
                         double lakeMax, double lakeMean, double lakeShare, double arid, double semiArid) { }
    static final double BLEND_T = 0.05, BLEND_M = 0.05, BLEND_E = 0.05, BLEND_H = 12;
    static final double[] T_BREAKS = {-0.45, -0.15, 0.2, 0.55}, M_BREAKS = {-0.1, 0.1}, E_BREAKS = {-0.375, 0.55}, H_BREAKS = {75, 160, 165};
    private static final double[] GAUSS = {-Math.sqrt(0.6), 0, Math.sqrt(0.6)}, GAUSS_W = {5 / 9.0, 8 / 9.0, 5 / 9.0};

    public static Params params(double zoneTemperature, double moisture, double erosion, double height) {
        double t = cooled(zoneTemperature, height);
        moisture = dryBelt(t, moisture);
        double[] ts = cut(t, BLEND_T, T_BREAKS), ms = cut(moisture, BLEND_M, M_BREAKS), es = cut(erosion, BLEND_E, E_BREAKS), hs = cut(height, BLEND_H, H_BREAKS);
        double runoff = 0, logArea = 0, widthK = 0, depthK = 0, wetK = 0, lakeDepth = 0, lakeMax = 0, lakeMean = 0, lakeShare = 0, lakeW = 0, arid = 0, semi = 0;
        for (int i = 0; i + 1 < ts.length; i++) for (int j = 0; j + 1 < ms.length; j++) for (int k = 0; k + 1 < es.length; k++)
            for (int l = 0; l + 1 < hs.length; l++) {
                double w = (ts[i + 1] - ts[i]) * (ms[j + 1] - ms[j]) * (es[k + 1] - es[k]) * (hs[l + 1] - hs[l])
                        / (16 * BLEND_T * BLEND_M * BLEND_E * BLEND_H);
                if (w <= 0) continue;
                Setting s = classify((ts[i] + ts[i + 1]) / 2, (ms[j] + ms[j + 1]) / 2, (es[k] + es[k + 1]) / 2, (hs[l] + hs[l + 1]) / 2);
                runoff += w * s.runoff * mean(ms[j], ms[j + 1], true) * mean(hs[l], hs[l + 1], false);
                logArea += w * Math.log(s.channelArea);
                widthK += w * s.widthK; depthK += w * s.depthK; wetK += w * s.wetK;
                if (s.arid) arid += w;
                else { lakeDepth += w * s.lakeDepth; lakeMax += w * s.lakeMax; lakeMean += w * s.lakeMean; lakeShare += w * s.lakeShare; lakeW += w; }
                if (s.semiArid) semi += w;
            }
        return new Params(runoff, Math.exp(logArea), widthK, depthK, lakeW > 0 ? lakeDepth / lakeW : 99, wetK,
                lakeW > 0 ? lakeMax / lakeW : 99, lakeW > 0 ? lakeMean / lakeW : 99, lakeW > 0 ? lakeShare / lakeW : 99, arid, semi);
    }
    /** Box [c - d, c + d] split at the breaks inside it (ascending, endpoints included). */
    private static double[] cut(double c, double d, double[] breaks) {
        double[] out = new double[breaks.length + 2];
        int n = 0;
        out[n++] = c - d;
        for (double b : breaks) if (b > c - d && b < c + d) out[n++] = b;
        out[n++] = c + d;
        return java.util.Arrays.copyOf(out, n);
    }
    /** Mean of the humidity factor (moisture = true) or the orographic gain over an interval (3-point Gauss). */
    private static double mean(double a, double b, boolean moisture) {
        if (b - a < 1e-12) return moisture ? humidity(a) : orographic(a);
        double sum = 0;
        for (int g = 0; g < 3; g++) {
            double v = (a + b) / 2 + (b - a) / 2 * GAUSS[g];
            sum += GAUSS_W[g] * (moisture ? humidity(v) : orographic(v));
        }
        return sum / 2;
    }
    static double humidity(double moisture) { return 0.75 + 0.5 * smooth(-0.35, 0.35, moisture); }
    static double orographic(double height) { return 1 + 0.4 * smooth(110, 200, height); }

    /** Catchment area of a channel head at a local slope. Gentle ground keeps the S^-0.5 scaling; on steep ground the
     * head area falls as S^-STEEP_EXP (field data: A ~ S^-2, Montgomery & Dietrich 1988): mountains are dissected by
     * many small channels instead of standing dry (audit 23.11 B-4: 0.02-0.05 km/km2 of wet channels above H100
     * against 1.26 in the lowlands). */
    public static double channelArea(double area, double slope) { return channelArea(area, slope, 0); }
    /** Channel-head area at a slope and height: the S^-0.5 scaling of the lowlands and hills, blending into the
     * steep-ground law of the mountains between MOUNTAIN_H0 and MOUNTAIN_H1. */
    public static double channelArea(double area, double slope, double height) {
        double s = clamp(slope, 0.01, 0.4), gentle = Math.sqrt(0.06 / s);
        return area * (gentle + (slopeFactor(slope) - gentle) * mountain(height));
    }
    /** Slope scaling of mountain channel heads: S^-0.5 on gentle ground, S^-STEEP_EXP on steep ground, at least
     * STEEP_MIN (a head drains a few lattice cells at least: no channel in every fold of a cliff). */
    static double slopeFactor(double slope) {
        double s = clamp(slope, 0.01, 0.4);
        return s <= 0.06 ? Math.sqrt(0.06 / s) : Math.max(STEEP_MIN, Math.pow(0.06 / s, STEEP_EXP));
    }
    /** Weight of the mountain rules by height. Only real mountains: on hills the denser heads made dry ditches,
     * and newly wet hill creeks crossed lowland hollows below the water further down (a stream held at the level of
     * a lake beyond a rim, seed 42 X-3531 Z-3420). */
    static double mountain(double height) { return smooth(MOUNTAIN_H0, MOUNTAIN_H1, height); }
    static final double STEEP_EXP = 1.25, STEEP_MIN = 0.2, MOUNTAIN_H0 = 100, MOUNTAIN_H1 = 130;
    /** Effective flow at which a channel carries permanent water: WET_FLOW scaled by the climate and, on steep ground,
     * by the same factor as the channel heads - in humid mountains a channel head is a spring-fed stream, not a dry
     * gully (springs, snowmelt; the runoff is raised by the orographic gain too); dry climates stay dry (their runoff
     * is a few percent). Gentle ground keeps WET_FLOW. */
    public static double wetFlow(double wetK, double slope) { return wetFlow(wetK, slope, 0); }
    public static double wetFlow(double wetK, double slope, double height) {
        return WET_FLOW * wetK * (1 + (Math.min(1, slopeFactor(slope)) - 1) * mountain(height));
    }
    /** Share of the flow a donor passes on over a link of the given length: transmission losses of dry-land rivers
     * (infiltration and evaporation, Tooth 2000) - a river entering a desert narrows downstream. Humid ground loses
     * nothing; full aridity halves the flow every LOSS_HALF blocks. */
    public static double transmission(double runoff, double length) {
        double aridity = 1 - smooth(0.03, 0.25, runoff);
        return aridity <= 0 ? 1 : Math.exp(-length * aridity * Math.log(2) / LOSS_HALF);
    }
    static final double LOSS_HALF = 2500;
    /** Width (blocks): width ~ Q^0.45 (Leopold-Maddock downstream geometry), widened for trunk rivers (large
     * catchments: 12-30 blocks and more); with the shallower depth below, a creek is 1 deep instead of a 2x2 ditch
     * (audit 23.11 water B-4/B-5). Small channels have a soft floor: MIN_WIDTH + a quarter of the geometric width,
     * up to 4 blocks where the two meet - a creek 2 blocks wide showed a single line of water in a slot (player
     * report 23.11). The first try of a floor of 3 failed at seed 42 X255 Z-1564 because that creek ran beside the
     * 40-block pit of an uncapped knickpoint fall (HydroReach.KNICK_*), not because of the width. */
    public static double width(double widthK, double flow) {
        double q = Math.max(flow, 0);
        double raw = widthK * 3.2 * Math.pow(q, 0.45) * (1 + 0.6 * smooth(4, 40, q));
        return clamp(Math.max(raw, MIN_WIDTH + 0.25 * raw), MIN_WIDTH, 64);
    }
    public static final double MIN_WIDTH = 3;
    /** Water depth (blocks): depth ~ Q^0.33, a 1-block creek up to Q 2 (a 3-4 wide stream 2 deep was a ditch: width
     * to depth 1.6-2.2, audit 23.11 B-7), 2-3 for streams, 4-5 for trunk rivers. */
    public static int depth(double depthK, double flow) {
        return (int) clamp(Math.round(depthK * (0.35 + 0.9 * Math.pow(Math.max(flow, 0), 0.33))), 1, 8);
    }
    public static double dryWidth(double widthK, double area) { return clamp(widthK * (2.0 + 2.6 * Math.pow(Math.max(area, 0), 0.4)), 2, 30); }

    public static Setting classify(double temperature, double moisture, double erosion, double height) {
        if (temperature < -0.45) return height > 165 ? Setting.ALPINE : Setting.ARCTIC;
        if (temperature < 0.55 && height > 160) return Setting.ALPINE;
        if (temperature < -0.15) return Setting.BOREAL;
        // Warm and hot swamp lowlands (the biome table: swamp and mangrove_swamp in the erosion column 0.55..1 at any
        // humidity) are wetlands, not desert or savanna: a mangrove swamp drained like a desert (audit 23.11, seed
        // 8675309 X-9676 Z-7958).
        if (temperature >= 0.2 && erosion > 0.55 && height < 75) return Setting.WETLAND;
        if (temperature >= 0.55) return erosion < -0.375 ? Setting.BADLANDS : Setting.DESERT;
        if (temperature >= 0.2) {
            if (moisture < -0.1) return Setting.SAVANNA;
            if (moisture > 0.1) return Setting.TROPICAL;
            return Setting.TEMPERATE;
        }
        if (erosion > 0.55 && moisture > -0.1 && height < 75) return Setting.WETLAND;
        return Setting.TEMPERATE;
    }

    /** Runoff coefficient of one setting: its base, wetter within the humidity band, orographic gain. */
    public static double runoff(Setting s, double moisture, double height) {
        return s.runoff * humidity(moisture) * orographic(height);
    }

    /** Catchment area needed for a channel head at local slope (Montgomery-Dietrich: steeper heads
     * need a smaller area). */
    public static double channelArea(Setting s, double slope) { return channelArea(s.channelArea, slope, 0); }

    /** Effective (runoff-weighted) area at which a channel carries permanent water (scaled by the setting's wetK). */
    public static final double WET_FLOW = 0.08;
    /** Smallest channel-head area of any setting and slope (prunes the inheritance search). */
    public static final double MIN_CHANNEL_AREA = 0.03 * STEEP_MIN;

    public static double width(Setting s, double flow) { return width(s.widthK, flow); }
    public static int depth(Setting s, double flow) { return depth(s.depthK, flow); }
    /** Dry wash width from the catchment area (flash floods): wide flat beds. */
    public static double dryWidth(Setting s, double area) { return dryWidth(s.widthK, area); }
    public static int dryDepth(double area) { return area > 2 ? 2 : 1; }

    /** Valley form. Rivers of dry land cut steep-sided washes (no 200-block parabolic troughs around a 3-block
     * stream: audit 23.11 water B-9); badlands get terraced canyons. */
    public static Valley valley(Setting s, boolean wet, double slope, double height) {
        if (s == Setting.BADLANDS) return Valley.CANYON;
        if (s == Setting.DESERT || (!wet && s == Setting.SAVANNA)) return Valley.WADI;
        if (slope > 0.07) return s.cold() && height > 120 ? Valley.U : Valley.V;
        if (slope > 0.025) return s.cold() && height > 140 ? Valley.U : Valley.HILL;
        return Valley.LOWLAND;
    }

    public static double smooth(double a, double b, double v) {
        double t = clamp((v - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }
    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
}
