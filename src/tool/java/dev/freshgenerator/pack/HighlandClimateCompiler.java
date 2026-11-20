package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** 0.9.1 (audit R4): cool highland plateaus are not savanna.
 * In the native table the slope band switches from snowy_slopes (T &lt; 0.2) straight to
 * savanna_plateau (T 0.2..0.55, dry); a shallow temperature extremum on a mountain
 * then left a real snow island inside a savanna (seed -8675309, X-2746 Z-2075; 123
 * columns of snow_block, precipitation NONE around it). Just above that threshold and only
 * on high ground (H 110..130 and up) the lookup humidity gets a floor in the forest band:
 * the cool side of the snow line is montane forest, savanna stays in warmer or lower land.
 * Own audit (20.11): the humid side had the same jump (grove and peaks next to jungle at H157..196),
 * so the band also caps humidity below the jungle rows: the cool highland is forest on both sides.
 * Physical climate fields, terrain and the dry belt (T &gt;= 0.46) are untouched.
 * <p>Audit R5 adds two cold-edge rules on the same lookup axes:
 * <ul>
 * <li>Boreal belt: at mid humidity the native table puts forest (T &gt; -0.45) straight next to
 *     snowy_plains; its taiga rows need humidity 0.1..0.3 (seed 1, X-6785 Z-4958: forest and
 *     snowy_plains on one flat Y65 ground). Just above the snow line forest-band humidity is
 *     lifted into the taiga band, so forest passes through taiga (and snowy_taiga on the cold
 *     side) into the snowy plain. Dry plains, which border snowy plains as open land, stay.</li>
 * <li>Cold coasts: low coastal erosion selects stony_shore at any temperature; its native weather
 *     is warm (RAIN at T -0.82, seed -20261119, X7680 Z3392). On cold coasts the lookup erosion
 *     gets a floor in the beach band, so the native table picks snowy_beach (SNOW). Coast
 *     materials stay with the coast rule (rock on steep shores).</li>
 * </ul> */
public final class HighlandClimateCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, C = P + "landscape/climate/";
    /** Lookup temperature band of the floor (ramps in from T_IN, full T_FULL..T_HOLD, gone at T_OUT). */
    public static final double T_IN = .16, T_FULL = .20, T_HOLD = .30, T_OUT = .33;
    /** Boreal belt: lookup temperature band, humidity band that is lifted, target humidity (taiga band). */
    public static final double B_IN = -.47, B_FULL = -.452, B_HOLD = -.33, B_OUT = -.26, B_M_LOW = -.16, B_M_HIGH = -.10, B_FLOOR = .15;
    /** Taiga fringe on the dry side (user request 20.11): plains next to the snow line also pass
     * through taiga; full to F_HOLD, gone at F_OUT (a real transition band, not a sliver). */
    public static final double F_HOLD = -.40, F_OUT = -.34;
    /** Cold coasts: fully at T &lt;= C_T_FULL, gone at C_T_OUT; erosion floor in the native beach band. */
    public static final double C_T_FULL = -.55, C_T_OUT = -.47, C_EROSION = -.2;
    public static final String SETTINGS = SurfaceOnlyCompiler.SETTINGS;
    /** Frozen humid cap (audit R6 BIO-03): below the snow line the native table puts rainy taiga in its
     * wettest column (M &gt; 0.3); the cap keeps such places in the snowy_taiga column. */
    public static final double FROZEN_FULL = -.47, FROZEN_OUT = -.452, FROZEN_CAP = .29;
    /** Surface height band where the floor fades in, and the floor itself (native forest humidity band). */
    public static final double H_LOW = 90, H_HIGH = 110, FLOOR = -.05, CAP = .09;
    public static final Set<String> REPLACED = Set.of(path(P + "regions/biome_vegetation"), SETTINGS);
    public static final Set<String> ADDED = Set.of(path(C + "highland_moisture_floor"), path(C + "highland_moisture"),
            path(C + "highland_moisture_cap"), path(C + "highland_band"), path(C + "boreal_moisture_floor"), path(C + "boreal_fringe_floor"), path(C + "boreal_moisture"), path(C + "cold_coast_floor"), path(C + "coast_erosion"),
            path(C + "frozen_moisture_cap"), path(C + "frozen_moisture"));
    private static final Gson G = new Gson();
    private HighlandClimateCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        for (String p : ADDED) if (staged.containsKey(p)) throw new IllegalStateException("Highland climate layer already present");
        JsonObject vegetation = Objects.requireNonNull(staged.get(path(P + "regions/biome_vegetation")), "Missing lookup vegetation").deepCopy().getAsJsonObject();
        if (!vegetation.get("first").getAsString().equals(C + "lookup_moisture")) throw new IllegalStateException("Unexpected lookup vegetation");
        // band(T) * band(H) once; height is read only inside the temperature band (range_choice),
        // since biome lookups evaluate this chain for every quart of a chunk (benchmark: a second
        // copy of the height spline cost the massif ~10%).
        staged.put(path(C + "highland_band"), cache(obj("type", "minecraft:range_choice", "input", C + "surface_temperature",
                "min_inclusive", T_IN, "max_exclusive", T_OUT, "when_in_range", mul(
                spline(C + "surface_temperature", new double[][]{{T_IN, 0, 0}, {T_FULL, 1, 0}, {T_HOLD, 1, 0}, {T_OUT, 0, 0}}),
                spline(P + "coherent/height", new double[][]{{H_LOW, 0, 0}, {H_HIGH, 1, 0}})), "when_out_of_range", 0.0)));
        // floor: -1 (no effect) outside, FLOOR inside; cap: 1 outside, CAP inside (native forest band).
        staged.put(path(C + "highland_moisture_floor"), cache(add(-1, mul(1 + FLOOR, C + "highland_band"))));
        staged.put(path(C + "highland_moisture_cap"), cache(add(1, mul(CAP - 1, C + "highland_band"))));
        staged.put(path(C + "highland_moisture"), cache(obj("type", "minecraft:min", "right", C + "highland_moisture_cap",
                "left", obj("type", "minecraft:max", "left", C + "lookup_moisture", "right", C + "highland_moisture_floor"))));
        staged.put(path(C + "boreal_moisture_floor"), cache(add(-1, mul(1 + B_FLOOR, mul(
                spline(C + "surface_temperature", new double[][]{{B_IN, 0, 0}, {B_FULL, 1, 0}, {B_HOLD, 1, 0}, {B_OUT, 0, 0}}),
                spline(C + "highland_moisture", new double[][]{{B_M_LOW, 0, 0}, {B_M_HIGH, 1, 0}}))))));
        staged.put(path(C + "boreal_fringe_floor"), cache(add(-1, mul(1 + B_FLOOR,
                spline(C + "surface_temperature", new double[][]{{B_IN, 0, 0}, {B_FULL, 1, 0}, {F_HOLD, 1, 0}, {F_OUT, 0, 0}})))));
        staged.put(path(C + "boreal_moisture"), cache(obj("type", "minecraft:max", "left", C + "highland_moisture",
                "right", obj("type", "minecraft:max", "left", C + "boreal_moisture_floor", "right", C + "boreal_fringe_floor"))));
        // Audit R6 (BIO-03): rainy taiga over a snowy beach at T -0.66, M 0.34 (seed 1, X-1920 Z-6464).
        staged.put(path(C + "frozen_moisture_cap"), cache(add(1, mul(FROZEN_CAP - 1,
                spline(C + "surface_temperature", new double[][]{{FROZEN_FULL, 1, 0}, {FROZEN_OUT, 0, 0}})))));
        staged.put(path(C + "frozen_moisture"), cache(obj("type", "minecraft:min", "left", C + "boreal_moisture", "right", C + "frozen_moisture_cap")));
        vegetation.addProperty("first", C + "frozen_moisture");
        staged.put(path(P + "regions/biome_vegetation"), vegetation);
        // The router erosion is also read by the aquifer in 3-D, so the floor is evaluated only
        // where it can act: erosion below the floor, and continentalness in the coast band
        // (range_choice evaluates one branch; benchmark: unguarded cost the massif ~15%).
        staged.put(path(C + "cold_coast_floor"), cache(obj("type", "minecraft:range_choice", "input", C + "surface_continents",
                "min_inclusive", -.21, "max_exclusive", -.09, "when_in_range", add(-1, mul(1 + C_EROSION, mul(
                spline(C + "surface_temperature", new double[][]{{C_T_FULL, 1, 0}, {C_T_OUT, 0, 0}}),
                spline(C + "surface_continents", new double[][]{{-.21, 0, 0}, {-.19, 1, 0}, {-.11, 1, 0}, {-.09, 0, 0}})))),
                "when_out_of_range", -1.0)));
        staged.put(path(C + "coast_erosion"), cache(obj("type", "minecraft:range_choice", "input", C + "biome_erosion",
                "min_inclusive", -1.0e6, "max_exclusive", C_EROSION,
                "when_in_range", obj("type", "minecraft:max", "left", C + "biome_erosion", "right", C + "cold_coast_floor"),
                "when_out_of_range", C + "biome_erosion")));
        JsonObject settings = staged.get(SETTINGS).deepCopy().getAsJsonObject();
        JsonObject router = settings.getAsJsonObject("noise_router");
        if (!router.get("erosion").getAsString().equals(C + "biome_erosion")) throw new IllegalStateException("Unexpected erosion routing");
        router.addProperty("erosion", C + "coast_erosion");
        staged.put(SETTINGS, settings);
        output.putAll(staged);
    }
    private static JsonObject spline(String coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", coordinate, "points", list));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
    private static JsonObject add(Object a, Object b) { return obj("type", "minecraft:add", "left", a, "right", b); }
    private static JsonObject mul(Object a, Object b) { return obj("type", "minecraft:mul", "left", a, "right", b); }
    private static JsonObject obj(Object... p) {
        var o = new JsonObject();
        for (int i = 0; i < p.length; i += 2) o.add((String) p[i], p[i + 1] instanceof JsonElement j ? j : G.toJsonTree(p[i + 1]));
        return o;
    }
}
