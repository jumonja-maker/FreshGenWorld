package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;
import static dev.freshgenerator.core.MountainRanges.*;

/** Mountain ranges of the current world (user request 26.11), see {@link dev.freshgenerator.core.MountainRanges}.
 * Adds the range uplift to {@code landscape/uplift} and scales the older erosion/ridge mountain uplift by the
 * proximity to a range; the snow massifs of the cold cores keep their height. Historical compilers are unchanged. */
public final class MountainRangeCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", R = L + "range/";
    public static final String SETTINGS = SurfaceOnlyCompiler.SETTINGS;
    public static final Set<String> REPLACED = Set.of(path(L + "uplift"), SETTINGS);
    private static final Gson G = new Gson();
    private MountainRangeCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        if (staged.containsKey(path(R + "uplift"))) throw new IllegalStateException("Range layer already present");
        JsonObject uplift = Objects.requireNonNull(staged.get(path(L + "uplift")), "Missing uplift").getAsJsonObject();
        // Expected after the snow massifs: cache(add(<erosion/ridge mountains>, mul(140, massif))).
        JsonObject sum = uplift.getAsJsonObject("input");
        if (!"minecraft:cache".equals(uplift.get("type").getAsString()) || !"minecraft:add".equals(sum.get("type").getAsString())
                || !sum.getAsJsonObject("right").get("right").getAsString().equals(L + "massif/massif"))
            throw new IllegalStateException("Unexpected uplift: snow massif layer must come first");
        JsonElement old = sum.get("left").deepCopy(), massif = sum.get("right").deepCopy();

        noise(staged, "axis", AXIS_OCTAVE, AXIS_OCTAVES);
        noise(staged, "branch", BRANCH_OCTAVE, 2);
        for (String w : List.of("warp_x", "warp_z", "branch_warp_x", "branch_warp_z")) noise(staged, w, WARP_OCTAVE, 1);
        noise(staged, "activity", ACTIVITY_OCTAVE, 1);
        noise(staged, "width", WIDTH_OCTAVE, 1);
        noise(staged, "height", HEIGHT_OCTAVE, 1);
        noise(staged, "peaks", PEAK_OCTAVE, 2);
        noise(staged, "spurs", SPUR_OCTAVE, 1);
        noise(staged, "gaps", GAP_OCTAVE, 1);

        String wx = node(staged, "warp_x", cache(mul(WARP, noiseRef("warp_x", null, null))));
        String wz = node(staged, "warp_z", cache(mul(WARP, noiseRef("warp_z", null, null))));
        String bwx = node(staged, "branch_warp_x", cache(mul(WARP, noiseRef("branch_warp_x", null, null))));
        String bwz = node(staged, "branch_warp_z", cache(mul(WARP, noiseRef("branch_warp_z", null, null))));
        // Distance (blocks) to the nearest zero line of each axis noise: |n| / |grad n|.
        String dMain = node(staged, "axis_distance", cache(distance("axis", wx, wz)));
        String dBranch = node(staged, "branch_distance", cache(distance("branch", bwx, bwz)));
        String act = node(staged, "activity", cache(spline(noiseRef("activity", null, null), new double[][]{{ACTIVITY_IN, 0, 0}, {ACTIVITY_FULL, 1, 0}})));
        String width = node(staged, "half_width", cache(mul(add(WIDTH_BASE, mul(WIDTH_SPREAD, spline(noiseRef("width", null, null),
                new double[][]{{-.5, 0, 0}, {.5, 1, 0}}))), add(TAPER, mul(1 - TAPER, act)))));
        String uMain = node(staged, "main_u", cache(mul(div(dMain, width), add(1.0, mul(SPUR_WARP, noiseRef("spurs", null, null))))));
        String uBranch = node(staged, "branch_u", cache(div(dBranch, mul(BRANCH_WIDTH, width))));
        String crest = node(staged, "crest", cache(max(profile(uMain), mul(BRANCH_HEIGHT, profile(uBranch)))));
        String height = node(staged, "crest_height", cache(mul(add(HEIGHT_BASE, mul(HEIGHT_SPREAD, spline(noiseRef("height", null, null),
                new double[][]{{-.5, 0, 0}, {.5, 1, 0}}))), add(PEAK_LOW, mul(PEAK_SPREAD, spline(noiseRef("peaks", null, null),
                new double[][]{{-.6, 0, 0}, {.6, 1, 0}}))))));
        String gaps = node(staged, "gaps", cache(spline(noiseRef("gaps", null, null), new double[][]{{GAP_FULL, 0, 0}, {GAP_OUT, 1, 0}})));
        String raw = node(staged, "uplift_raw", mul(mul(mul(L + "land", act), gaps), mul(height, crest)));
        String near = node(staged, "near_raw", mul(mul(act, gaps), spline(obj("type", "minecraft:min", "left", uMain, "right", uBranch),
                new double[][]{{NEAR_FULL, 1, 0}, {NEAR_OUT, 0, 0}})));
        String shape = node(staged, "shape", cache(max(0.0, obj("type", "freshgenerator:lattice", "spacing", LATTICE, "interpolation", "cubic", "input", raw))));
        String rangeUplift = node(staged, "uplift", cache(shape));
        String rangeNear = node(staged, "near", cache(obj("type", "minecraft:clamp", "min", 0.0, "max", 1.0,
                "input", obj("type", "freshgenerator:lattice", "spacing", LATTICE, "interpolation", "cubic", "input", near))));
        // Older erosion/ridge mountains: full near a range, OLD_FLOOR away from one.
        JsonObject scaled = mul(old, add(OLD_FLOOR, mul(1 - OLD_FLOOR, rangeNear)));
        staged.put(path(L + "uplift"), cache(add(add(scaled, massif), rangeUplift)));

        JsonObject settings = staged.get(SETTINGS).deepCopy().getAsJsonObject();
        settings.getAsJsonArray("debug_functions").add(obj("label", "FG range uplift", "function", rangeUplift));
        settings.getAsJsonArray("debug_functions").add(obj("label", "FG range near", "function", rangeNear));
        staged.put(SETTINGS, settings);
        output.putAll(staged);
    }

    private static JsonObject distance(String axis, String wx, String wz) {
        JsonObject n0 = noiseRef(axis, wx, wz);
        JsonObject gx = div(sub(noiseRef(axis, add(wx, GRADIENT_STEP), wz), noiseRef(axis, add(wx, -GRADIENT_STEP), wz)), 2 * GRADIENT_STEP);
        JsonObject gz = div(sub(noiseRef(axis, wx, add(wz, GRADIENT_STEP)), noiseRef(axis, wx, add(wz, -GRADIENT_STEP))), 2 * GRADIENT_STEP);
        JsonObject g = unary("sqrt", add(add(unary("square", gx), unary("square", gz)), 1e-12));
        return div(unary("abs", n0), g);
    }
    private static JsonObject profile(String u) { return spline(u, PROFILE); }

    private static void noise(Map<String, JsonElement> out, String name, int octave, int count) {
        if (out.putIfAbsent(VanillaAssets.path("noise", R + name), obj("base_octave", octave, "octave_count", count, "base_amplitude", 1.0)) != null)
            throw new IllegalStateException("Range noise exists: " + name);
    }
    private static String node(Map<String, JsonElement> out, String name, JsonElement value) {
        if (out.putIfAbsent(path(R + name), value) != null) throw new IllegalStateException("Range node exists: " + name);
        return R + name;
    }
    private static JsonObject noiseRef(String name, Object shiftX, Object shiftZ) {
        JsonObject o = obj("type", "minecraft:noise", "noise", R + name, "xz_scale", 1.0, "y_scale", 0.0);
        if (shiftX != null) o.add("shift_x", e(shiftX));
        if (shiftZ != null) o.add("shift_z", e(shiftZ));
        return o;
    }
    static JsonObject spline(Object coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", e(coordinate), "points", list));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement e(Object o) { return o instanceof JsonElement j ? j.deepCopy() : G.toJsonTree(o); }
    static JsonObject obj(Object... p) { var o = new JsonObject(); for (int i = 0; i < p.length; i += 2) o.add((String) p[i], e(p[i + 1])); return o; }
    private static JsonObject unary(String type, Object a) { return obj("type", "minecraft:" + type, "input", a); }
    private static JsonObject add(Object a, Object b) { return obj("type", "minecraft:add", "left", a, "right", b); }
    private static JsonObject sub(Object a, Object b) { return obj("type", "minecraft:sub", "left", a, "right", b); }
    private static JsonObject mul(Object a, Object b) { return obj("type", "minecraft:mul", "left", a, "right", b); }
    private static JsonObject div(Object a, Object b) { return obj("type", "minecraft:div", "left", a, "right", b); }
    private static JsonObject max(Object a, Object b) { return obj("type", "minecraft:max", "left", a, "right", b); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
}
