package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** 0.9.1 (user request 20.11): great mountain massifs in the cores of large cold regions.
 * <p>Height follows how deep a place lies inside the cold zone, measured on the zone
 * temperature (a pure noise field, no height dependency, so there is no feedback through
 * altitude cooling). The edge of a snow region gets low hills; a region that is only a small
 * cold dip never gets a great mountain; a large cold region rises to a massif in its middle.
 * The form follows the existing folded ridge field (crests, saddles and valleys between
 * them; native river corridors keep their clearance, as for the giant provinces), with a
 * summit field and a slow size field so that peaks and whole massifs differ in height.
 * Only the land mask is raised; coasts and oceans stay. Outside the cold cores the branch is
 * not evaluated (range_choice).
 * <p>Altitude belts (all mountains; lower in cold regions): the native table picked mountain biomes from the erosion
 * and weirdness slices, independent of the real height, so peaks could stand at the height of
 * the surrounding forest and a high massif could stay "snowy plains". There the lookup axes
 * (only the classifier, never the terrain) follow height: from BELT_SLOPE the erosion falls into
 * the slope rows and weirdness into the peak band, from BELT_PEAK the erosion reaches the peak
 * rows (frozen or jagged peaks by the sign of weirdness, snowy slopes or grove below them). */
public final class SnowMassifCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", M = L + "massif/";
    /** Zone temperature where the massif starts (just inside the snow line) and where it is full. */
    public static final double T_EDGE = -.50, T_CORE = -.85;
    /** Full massif height in blocks (before the native smooth height ceiling). */
    public static final double HEIGHT = 140;
    /** Share of the massif that is a regional highland base; the rest follows the crests. */
    public static final double BASE = .30;
    public static final int PEAK_OCTAVE = -7, SIZE_OCTAVE = -9;
    /** Altitude belts: zone temperature band where they act, and the heights of the slope and peak rows. */
    public static final double BELT_T_FULL = -.62, BELT_T_OUT = -.48, BELT_COLD_SHIFT = 25;
    /** Belt contours (belt height = H + BELT_COLD_SHIFT x coldness): temperate mountains get slopes
     * from Y168 and peaks from Y212, cold regions from Y143 and Y187; each switch takes +-BELT_EDGE of height. */
    public static final double BELT_SLOPE = 168, BELT_PEAK = 212, BELT_EDGE = .5;
    /** Erosion floor below the slope contour (just above the slope row, native -0.375) and the
     * |weirdness| over which it fades in (valley band |W| &lt; 0.05 untouched). */
    public static final double FLOOR_E = -.37, W_FLOOR_IN = .05, W_FLOOR_FULL = .0502;
    /** Erosion floor in the slope belt: just above the peak rows (native -0.78). */
    public static final double SLOPE_FLOOR_E = -.77;
    /** Warm mountains: peak contour lowered by WARM_PEAK_SHIFT, fading in over zone T WARM_T_IN..WARM_T_FULL.
     * Zone 0.32 still has lookup T 0.22 at Y157 (altitude cooling 0.10 there), the native stony_peaks
     * column; higher summits cool into the jagged/frozen columns, so warm ranges carry snowy peaks above rock. */
    public static final double WARM_PEAK_SHIFT = 55, WARM_T_IN = .29, WARM_T_FULL = .32;
    /** |weirdness| of the native "high" band (slopes, PV about 0.44) and of the peak band (PV 1). */
    public static final double W_HIGH = .48, W_PEAK = 2 / 3.0;
    public static final String SETTINGS = SurfaceOnlyCompiler.SETTINGS, CL = L + "climate/";
    /** Lookup lerps that share the surface/deep weight. */
    public static final List<String> LOOKUP_LERPS = List.of(P + "regions/biome_temperature", P + "regions/biome_vegetation", CL + "biome_continents");
    public static final Set<String> REPLACED = Set.of(path(L + "uplift"), SETTINGS, path(P + "regions/biome_temperature"),
            path(P + "regions/biome_vegetation"), path(CL + "biome_continents"));
    private static final Gson G = new Gson();
    private SnowMassifCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        if (staged.containsKey(path(M + "massif"))) throw new IllegalStateException("Snow massif layer already present");
        JsonObject uplift = Objects.requireNonNull(staged.get(path(L + "uplift")), "Missing uplift").getAsJsonObject();
        if (!"minecraft:cache".equals(uplift.get("type").getAsString())) throw new IllegalStateException("Unexpected uplift");
        noise(staged, "massif_peaks", PEAK_OCTAVE, 2); noise(staged, "massif_size", SIZE_OCTAVE, 1);
        String zone = L + "climate/zone_temperature", ridges = P + "ridges_folded";
        // 0 at the edge, 1 in the cold core (cubic, zero slope at both ends).
        node(staged, "interior", cache(spline(zone, new double[][]{{T_CORE, 1, 0}, {T_EDGE, 0, 0}})));
        // Crest form as for the giant provinces: valley clearance times base + crest share.
        node(staged, "form", cache(mul(smooth(-.95, -.10, ridges), add(BASE, mul(1 - BASE, smooth(-.65, .95, ridges))))));
        node(staged, "peaks", cache(add(.70, mul(.60, smooth(-.20, .80, M + "peaks_noise")))));
        node(staged, "peaks_noise", cache(noiseRef("massif_peaks")));
        node(staged, "size", cache(add(.60, mul(.40, smooth(-.50, .50, M + "size_noise")))));
        node(staged, "size_noise", cache(noiseRef("massif_size")));
        JsonElement body = mul(mul(L + "land", M + "interior"), mul(M + "form", mul(M + "peaks", M + "size")));
        node(staged, "massif", cache(obj("type", "minecraft:range_choice", "input", zone, "min_inclusive", -2.0, "max_exclusive", T_EDGE,
                "when_in_range", body, "when_out_of_range", 0.0)));
        staged.put(path(L + "uplift"), cache(add(uplift.get("input").deepCopy(), mul(HEIGHT, M + "massif"))));
        // Altitude belts on the lookup axes for all mountains. Belt height = H + BELT_COLD_SHIFT x
        // coldness, so the belts sit lower in cold regions. Below the slope belt the erosion is
        // kept above the slope and peak rows (no frozen peaks or snowy slopes at the height of the
        // surrounding forest); above it the erosion falls into the slope rows and, on the summits,
        // the peak rows, while weirdness moves to the high band and then the peak band.
        JsonObject settings = staged.get(SETTINGS).deepCopy().getAsJsonObject();
        JsonObject router = settings.getAsJsonObject("noise_router");
        String erosion = router.get("erosion").getAsString(), ridgesIn = router.get("ridges").getAsString();
        node(staged, "belt_cold", cache(spline(zone, new double[][]{{BELT_T_FULL, 1, 0}, {BELT_T_OUT, 0, 0}})));
        node(staged, "belt_height", cache(add(P + "coherent/height", mul(BELT_COLD_SHIFT, M + "belt_cold"))));
        String bh = M + "belt_height";
        // Warm mountains (stony_peaks and plateaus, no snow) have no snow line; their peak contour sits
        // WARM_PEAK_SHIFT lower (stony_peaks from about Y157, as before the contours). Audit R8 D-01: the
        // shift reads the zone temperature, not the lookup temperature, which already includes altitude
        // cooling - a higher surface removed the shift faster than it added height (stony_peaks at H160,
        // forest at H161). With the zone field the peak height grows one block per block of height.
        node(staged, "belt_peak_height", cache(add(bh, mul(WARM_PEAK_SHIFT, spline(zone, new double[][]{{WARM_T_IN, 0, 0}, {WARM_T_FULL, 1, 0}})))));
        String bp = M + "belt_peak_height";
        // Audit R7 (BIO-02): the belts follow height contours. Up to 0.9.1 R6 the floor and cap ramped over
        // 15..25 blocks of height and the gate over erosion 0.1..0.2; inside those ramps the native erosion
        // still chose the row, so jagged_peaks met pale_garden (seed 0, X-6865 Z-5661, Y151) and snowy_slopes
        // met cherry_grove (X1242 Z1987, Y169) on one height. Now the slope row starts at the BELT_SLOPE
        // contour and the peak row at BELT_PEAK, each within +-BELT_EDGE of height, for any erosion.
        // Floor (no slope or peak rows below the slope contour, no peak rows below the peak contour):
        // from the low weirdness band up. In the
        // valley band (|W| < 0.05) a raised erosion turns the native valley rows into river (first try:
        // 27 725 river samples on dry ground above Y70), so the floor starts at the native valley-slice
        // edge. The low slice beyond it has no river rows; own audit 21.11: a fade over |W| 0.05..0.1
        // left 3 420 grove/snowy_slopes samples below Y90 on seven seeds.
        node(staged, "belt_abs_w", cache(obj("type", "minecraft:abs", "input", ridgesIn)));
        node(staged, "belt_erosion_floor", cache(add(-1, mul(spline(M + "belt_abs_w", new double[][]{{W_FLOOR_IN, 0, 0}, {W_FLOOR_FULL, 1, 0}}),
                add(1, obj("type", "minecraft:min", "left", spline(bh, new double[][]{{BELT_SLOPE - BELT_EDGE, FLOOR_E, 0}, {BELT_SLOPE + BELT_EDGE, SLOPE_FLOOR_E, 0}}),
                        "right", spline(bp, new double[][]{{BELT_PEAK - BELT_EDGE, 1, 0}, {BELT_PEAK + BELT_EDGE, -1, 0}})))))));
        // Cap (slope rows above the slope contour, peak rows above the peak contour) in the mountain band;
        // the gate fades it in over |W| 0.3..0.4 (continuous inputs at the guard edge, audit R6).
        node(staged, "belt_gate", cache(spline(M + "belt_abs_w", new double[][]{{.3, 0, 0}, {.4, 1, 0}})));
        node(staged, "belt_erosion_cap", cache(add(1, mul(M + "belt_gate", add(-1, obj("type", "minecraft:min",
                "left", spline(bh, new double[][]{{BELT_SLOPE - BELT_EDGE, 1, 0}, {BELT_SLOPE + BELT_EDGE, -.6, 0}}),
                "right", spline(bp, new double[][]{{BELT_PEAK - BELT_EDGE, 1, 0}, {BELT_PEAK + BELT_EDGE, -.9, 0}})))))));
        // Evaluated only where it can act (range_choice); the router erosion is also read by the
        // aquifer (an unguarded height read cost the massif ~12%). The floor can act only on erosion
        // below FLOOR_E, the cap only above the slope contour; elsewhere both are identities.
        node(staged, "belt_erosion", cache(erosionGuard(erosion, bp, obj("type", "minecraft:min", "right", M + "belt_erosion_cap",
                "left", obj("type", "minecraft:max", "left", erosion, "right", M + "belt_erosion_floor")))));
        // Weirdness: high band (slopes, not peaks, on the slope rows) from just below the slope contour,
        // peak band from the peak contour.
        node(staged, "belt_target", cache(spline(bp, new double[][]{{BELT_PEAK - BELT_EDGE, W_HIGH, 0}, {BELT_PEAK + BELT_EDGE, W_PEAK, 0}})));
        node(staged, "belt_weight", cache(mul(M + "belt_gate", obj("type", "minecraft:max",
                "left", spline(bh, new double[][]{{BELT_SLOPE - 3 * BELT_EDGE, 0, 0}, {BELT_SLOPE - BELT_EDGE, 1, 0}}),
                "right", spline(bp, new double[][]{{BELT_PEAK - 3 * BELT_EDGE, 0, 0}, {BELT_PEAK - BELT_EDGE, 1, 0}})))));
        node(staged, "belt_ridges", cache(ridgesGuard(ridgesIn, bp, add(ridgesIn, mul(M + "belt_weight", add(obj("type", "minecraft:range_choice", "input", ridgesIn,
                "min_inclusive", 0.0, "max_exclusive", 2.0, "when_in_range", M + "belt_target", "when_out_of_range", mul(-1, M + "belt_target")), mul(-1, ridgesIn)))))));
        // Audit R8 D-02: temperature, humidity and continentalness return to their native fields 24..64
        // blocks below the surface (the lookup lerp); the belt E/W did not, so a surface belt switched
        // deep cave biomes (deep_dark, sulfur caves) while the aquifer dry guard read the raw erosion.
        // E/W now fade the same way to the raw fields, the ones the aquifer exclusion reads; the belt
        // chain is not evaluated where the weight is 0 (deep) and the raw one not where it is 1 (surface).
        JsonObject vegetation = staged.get(path(P + "regions/biome_vegetation")).getAsJsonObject();
        if (!"minecraft:lerp".equals(vegetation.get("type").getAsString())) throw new IllegalStateException("Unexpected lookup vegetation");
        node(staged, "belt_depth", cache(vegetation.get("alpha").deepCopy()));
        // The temperature, humidity and continentalness lerps carry the same weight inline; they read
        // the shared cached node instead (the weight is computed once per position, not four times).
        for (String lookup : LOOKUP_LERPS) {
            JsonObject lerp = staged.get(path(lookup)).deepCopy().getAsJsonObject();
            if (!"minecraft:lerp".equals(lerp.get("type").getAsString()) || !lerp.get("alpha").equals(vegetation.get("alpha")))
                throw new IllegalStateException("Unexpected lookup weight " + lookup);
            lerp.addProperty("alpha", M + "belt_depth");
            staged.put(path(lookup), lerp);
        }
        node(staged, "routed_erosion", cache(depthFade(M + "belt_erosion", P + "erosion")));
        node(staged, "routed_ridges", cache(depthFade(M + "belt_ridges", ridgesIn)));
        router.addProperty("erosion", M + "routed_erosion");
        router.addProperty("ridges", M + "routed_ridges");
        // Audit R8 O-02: the debug E/W show the raw fields; add the inputs the biome lookup really uses.
        JsonArray debug = settings.getAsJsonArray("debug_functions");
        debug.add(obj("label", "FG lookup E", "function", M + "routed_erosion"));
        debug.add(obj("label", "FG lookup W", "function", M + "routed_ridges"));
        staged.put(SETTINGS, settings);
        output.putAll(staged);
    }
    /** Surface value near the surface, native value from 64 blocks down, the lookup lerp between. */
    private static JsonObject depthFade(String surface, String deep) {
        return range(M + "belt_depth", -1.0e6, 1.0e-9, surface,
                range(M + "belt_depth", 1 - 1.0e-9, 1.0e6, deep,
                        obj("type", "minecraft:lerp", "alpha", M + "belt_depth", "first", surface, "second", deep)));
    }
    /** Belt erosion only where floor or cap can act; the identity elsewhere (one branch evaluated). The height
     * guard reads the peak height, which is never below the belt height. */
    private static JsonObject erosionGuard(String erosion, String bh, Object inside) {
        return range(M + "belt_abs_w", W_FLOOR_IN, 2.0,
                range(erosion, -2.0, FLOOR_E, inside,
                        range(M + "belt_abs_w", .3, 2.0, range(bh, BELT_SLOPE - BELT_EDGE, 1.0e6, inside, erosion), erosion)),
                erosion);
    }
    /** Belt weirdness only in the mountain band from just below the slope contour. */
    private static JsonObject ridgesGuard(String ridges, String bh, Object inside) {
        return range(M + "belt_abs_w", .3, 2.0, range(bh, BELT_SLOPE - 3 * BELT_EDGE, 1.0e6, inside, ridges), ridges);
    }
    private static JsonObject range(String input, double min, double max, Object in, Object out) {
        return obj("type", "minecraft:range_choice", "input", input, "min_inclusive", min, "max_exclusive", max, "when_in_range", in, "when_out_of_range", out);
    }
    private static void noise(Map<String, JsonElement> out, String name, int octave, int count) {
        // 26.3 noise parameters (as the other landscape noises).
        if (out.putIfAbsent(VanillaAssets.path("noise", L + name), obj("base_octave", octave, "octave_count", count, "base_amplitude", 1.0)) != null)
            throw new IllegalStateException("Massif noise exists: " + name);
    }
    private static void node(Map<String, JsonElement> out, String name, JsonElement value) {
        if (out.putIfAbsent(path(M + name), value) != null) throw new IllegalStateException("Massif node exists: " + name);
    }
    private static JsonObject noiseRef(String name) { return obj("type", "minecraft:noise", "noise", L + name, "xz_scale", 1.0, "y_scale", 0.0); }
    /** Cubic smoothstep of a referenced value between lo and hi. */
    static JsonObject smooth(double lo, double hi, String value) {
        JsonObject t = obj("type", "minecraft:clamp", "input", mul(add(value, -lo), 1 / (hi - lo)), "min", 0.0, "max", 1.0);
        return mul(mul(t, t.deepCopy()), add(3.0, mul(-2.0, t.deepCopy())));
    }
    private static JsonObject spline(String coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", coordinate, "points", list));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement e(Object o) { return o instanceof JsonElement j ? j : G.toJsonTree(o); }
    private static JsonObject obj(Object... p) { var o = new JsonObject(); for (int i = 0; i < p.length; i += 2) o.add((String) p[i], e(p[i + 1])); return o; }
    private static JsonObject add(Object a, Object b) { return obj("type", "minecraft:add", "left", a, "right", b); }
    private static JsonObject mul(Object a, Object b) { return obj("type", "minecraft:mul", "left", a, "right", b); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
}
