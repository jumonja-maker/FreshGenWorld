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
    /** Belt heights (belt height = H + BELT_COLD_SHIFT x coldness): temperate mountains get slopes
     * from Y175 and peaks from Y220, cold regions from Y150 and Y195. */
    public static final double BELT_LOW = 160, BELT_SLOPE = 175, BELT_SLOPE_TOP = 205, BELT_PEAK = 220;
    /** |weirdness| of the native "high" band (slopes, PV about 0.44) and of the peak band (PV 1). */
    public static final double W_HIGH = .48, W_PEAK = 2 / 3.0;
    public static final String SETTINGS = SurfaceOnlyCompiler.SETTINGS, CL = L + "climate/";
    public static final Set<String> REPLACED = Set.of(path(L + "uplift"), SETTINGS);
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
        // The floor acts only in the mountain weirdness band (|W| > 0.4, where slope and peak rows
        // are chosen): in the valley band a raised erosion turns the native valley rows into river
        // (first try: 27 725 river samples on dry ground above Y70 on six seeds).
        node(staged, "belt_abs_w", cache(obj("type", "minecraft:abs", "input", ridgesIn)));
        node(staged, "belt_erosion_floor", cache(add(-1, mul(spline(M + "belt_abs_w", new double[][]{{.30, 0, 0}, {.40, 1, 0}}),
                add(1, spline(bh, new double[][]{{BELT_LOW - 10, -.37, 0}, {BELT_SLOPE, -1, 0}}))))));
        node(staged, "belt_erosion_cap", cache(spline(bh, new double[][]{{BELT_LOW, 1, 0}, {BELT_SLOPE, -.6, 0}, {BELT_SLOPE_TOP, -.6, 0}, {BELT_PEAK, -.9, 0}})));
        // Evaluated only where it can act (range_choice): erosion below 0.2 (the landscape raises
        // mountains only there) and the mountain weirdness band. The router erosion is also read
        // by the aquifer; unguarded, the height read cost the massif ~12%.
        // Audit R6 (BIO-01): the guard must not switch the classifier inputs. Inside it a continuous
        // gate (erosion 0.2 -> 0.1 and |W| 0.3 -> 0.4) blends the belts in, so E and W are
        // continuous across the guard edges (the hard switch gave meadow | snowy_slopes on one
        // height, seed -8675309 X1919/1920 Z-4256). Outside the gate is exactly 0 and nothing is computed.
        node(staged, "belt_gate", cache(guard(erosion, ridgesIn, mul(spline(erosion, new double[][]{{.1, 1, 0}, {.2, 0, 0}}),
                spline(M + "belt_abs_w", new double[][]{{.3, 0, 0}, {.4, 1, 0}})), 0.0)));
        node(staged, "belt_erosion", cache(guard(erosion, ridgesIn, add(erosion, mul(M + "belt_gate", add(obj("type", "minecraft:min", "right", M + "belt_erosion_cap",
                "left", obj("type", "minecraft:max", "left", erosion, "right", M + "belt_erosion_floor")), mul(-1, erosion)))), erosion)));
        node(staged, "belt_target", cache(spline(bh, new double[][]{{BELT_SLOPE_TOP, W_HIGH, 0}, {BELT_PEAK, W_PEAK, 0}})));
        node(staged, "belt_weight", cache(guard(erosion, ridgesIn, mul(M + "belt_gate", spline(bh, new double[][]{{BELT_LOW, 0, 0}, {BELT_SLOPE, 1, 0}})), 0.0)));
        node(staged, "belt_ridges", cache(add(ridgesIn, mul(M + "belt_weight", add(obj("type", "minecraft:range_choice", "input", ridgesIn,
                "min_inclusive", 0.0, "max_exclusive", 2.0, "when_in_range", M + "belt_target", "when_out_of_range", mul(-1, M + "belt_target")), mul(-1, ridgesIn))))));
        router.addProperty("erosion", M + "belt_erosion");
        router.addProperty("ridges", M + "belt_ridges");
        staged.put(SETTINGS, settings);
        output.putAll(staged);
    }
    /** inside when erosion &lt; 0.2 and |weirdness| &gt;= 0.3, else outside (one branch evaluated). */
    private static JsonObject guard(String erosion, String ridges, Object inside, Object outside) {
        return obj("type", "minecraft:range_choice", "input", erosion, "min_inclusive", -2.0, "max_exclusive", .2,
                "when_in_range", obj("type", "minecraft:range_choice", "input", M + "belt_abs_w", "min_inclusive", .3, "max_exclusive", 2.0,
                        "when_in_range", inside, "when_out_of_range", outside), "when_out_of_range", outside);
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
