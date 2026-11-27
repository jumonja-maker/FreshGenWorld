package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Rare large landforms (user request 26.11): volcanoes and impact craters ({@link dev.freshgenerator.core.Landforms})
 * applied over the finished dry-land height, before the drainage network: rivers, lakes (calderas, old craters),
 * climate and materials all see them.
 * <p>Escarpments (user request 26.11, "I did not see big waterfalls"): the drainage model makes a big waterfall only
 * where a river crosses a cliff in the ground (elsewhere a step is capped at 3 + width blocks and the rest runs as
 * riffles). In escarpment regions (a slow mask, ~25% of the land) the ground above a regional contour (Y88-128) is
 * raised by 16-30 blocks over a 0.5-block band of the original height: the contour becomes a cliff band - a plateau
 * edge, a cliff belt on a mountain flank, a mesa rim in the desert - and rivers that cross it fall over it. */
public final class LandformCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/";
    public static final String LABEL = "FG landforms";
    public static final Set<String> REPLACED = Set.of(path(L + "dry_floor"), SurfaceOnlyCompiler.SETTINGS);
    private LandformCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        String floor = path(L + "dry_floor");
        JsonElement old = Objects.requireNonNull(staged.get(floor), "Dry land must come first").deepCopy();
        if (staged.containsKey(path(L + "pre_landform"))) throw new IllegalStateException("Landforms already present");
        staged.put(path(L + "pre_landform"), old);
        String e = L + "escarpment/";
        for (String[] n : new String[][]{{"mask", "-11"}, {"level", "-10"}, {"drop", "-9"}}) {
            JsonObject noise = new JsonObject();
            noise.addProperty("base_octave", Integer.parseInt(n[1])); noise.addProperty("octave_count", 1); noise.addProperty("base_amplitude", 1.0);
            if (staged.putIfAbsent(VanillaAssets.path("noise", e + n[0]), noise) != null) throw new IllegalStateException("Escarpment noise exists");
        }
        staged.put(path(e + "mask"), cache(mul(spline(noiseRef(e + "mask"), new double[][]{{.15, 0, 0}, {.32, 1, 0}}), L + "land")));
        staged.put(path(e + "level"), cache(add(88.0, mul(40.0, spline(noiseRef(e + "level"), new double[][]{{-.5, 0, 0}, {.5, 1, 0}})))));
        staged.put(path(e + "drop"), cache(add(16.0, mul(14.0, spline(noiseRef(e + "drop"), new double[][]{{-.5, 0, 0}, {.5, 1, 0}})))));
        // Dry country takes a sharp scarp with a talus apron below it (mesas, buttes, cuesta fronts); humid country the
        // same uplift as a smooth rise with no wall (audits 26.11 Opus N-1/KO-01: every hill above the level became a
        // round "cake" ringed by a vertical wall 16-30 blocks high in the forest). Both profiles are monotone in the
        // original height: no closed hollows.
        JsonObject hot = spline(L + "climate/zone_temperature", new double[][]{{ARID_T0, 0, 0}, {ARID_T1, 1, 0}});
        JsonObject dry = add(1.0, mul(-1.0, spline(L + "climate/biome_moisture", new double[][]{{ARID_M0, 0, 0}, {ARID_M1, 1, 0}})));
        staged.put(path(e + "arid"), cache(obj("type", "minecraft:clamp", "min", 0.0, "max", 1.0,
                "input", obj("type", "freshgenerator:lattice", "spacing", 32, "interpolation", "cubic", "input", mul(hot, dry)))));
        JsonObject p = sub(L + "pre_landform", e + "level");
        double b = ESCARP_HALF_BAND;
        JsonObject sharp = spline(p, new double[][]{{-b - 9, 0, 0}, {-b - 5, 0.05, 0.02}, {-b - 2, 0.17, 0.06}, {-b, TALUS, 0.15}, {b, 1, 0}});
        // The smooth rise follows the regional contour (the original height on a cubic lattice of 32 blocks), not the
        // shape of each hill: a band of the local height was only ~15 blocks wide on a hill of slope 1.6 and a rise
        // of 28 blocks over it stood as the same ringed "cake" (audit 26.11 Opus N-6, code KO-07: seed 20261126 X6054
        // Z8360). Weathered humid scarps are lower, too.
        JsonObject regional = sub(obj("type", "freshgenerator:lattice", "spacing", 32, "interpolation", "cubic", "input", L + "pre_landform"), e + "level");
        JsonObject gentle = mul(HUMID_DROP, spline(regional, new double[][]{{-SMOOTH_HALF, 0, 0}, {SMOOTH_HALF, 1, 0}}));
        JsonObject profile = add(gentle, mul(e + "arid", sub(sharp, gentle)));
        JsonObject raised = add(L + "pre_landform", mul(e + "mask", mul(e + "drop", profile)));
        staged.put(path(e + "height"), cache(range(e + "mask", 1.0e-4, 2.0, raised, L + "pre_landform")));
        JsonObject landforms = new JsonObject();
        landforms.addProperty("type", "freshgenerator:landforms");
        landforms.addProperty("height", e + "height");
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("type", "minecraft:cache");
        wrapped.add("input", landforms);
        staged.put(floor, wrapped);
        // Ground cover of the landforms first in the surface sequence (passes elsewhere): the floor block and the
        // blocks under it, never under deep water.
        String surfacePath = VanillaAssets.path("material_rule", P + "surface");
        if (staged.containsKey(surfacePath)) {
            JsonObject surface = staged.get(surfacePath).deepCopy().getAsJsonObject();
            JsonArray sequence = surface.getAsJsonArray("sequence");
            JsonArray updated = new JsonArray();
            // Not limited to dry floors: the rule itself passes under water except on seamounts and drowned flanks of
            // volcanic islands.
            updated.add(condition("minecraft:on_floor", rule(true)));
            updated.add(condition("minecraft:under_floor", rule(false)));
            sequence.forEach(updated::add);
            surface.add("sequence", updated);
            staged.put(surfacePath, surface);
        }
        JsonObject settings = staged.get(SurfaceOnlyCompiler.SETTINGS).deepCopy().getAsJsonObject();
        JsonObject debug = new JsonObject();
        debug.addProperty("label", LABEL);
        debug.addProperty("function", L + "dry_floor");
        settings.getAsJsonArray("debug_functions").add(debug);
        staged.put(SurfaceOnlyCompiler.SETTINGS, settings);
        output.putAll(staged);
    }
    /** Half of the original height band over which an escarpment rises (blocks). Along a river the original ground
     * falls slowly (0.03-0.2 blocks per block), and a 1.2-block band spread the rise over 6-40 blocks of channel: a
     * ramp of riffles, not a fall (seed 31337, 12 x 12 km: 105 streams crossed escarpments, 12 falls of 12+ blocks).
     * A 0.5-block band keeps the face within the steep-face rule of the drainage model (HydroReach.FACE_*). */
    public static final double ESCARP_HALF_BAND = 0.25;
    /** Share of the drop below the cliff that the talus apron carries (the cliff is the rest). */
    public static final double TALUS = 0.35;
    /** Half of the regional height band of the smooth rise in humid country, and its share of the drop. */
    public static final double SMOOTH_HALF = 20, HUMID_DROP = 0.6;
    /** Aridity: zone temperature ARID_T0 -> ARID_T1 times dryness of the biome moisture ARID_M0 -> ARID_M1. */
    public static final double ARID_T0 = 0.15, ARID_T1 = 0.35, ARID_M0 = -0.25, ARID_M1 = 0.05;
    private static final Gson G = new Gson();
    private static JsonElement el(Object o) { return o instanceof JsonElement j ? j.deepCopy() : G.toJsonTree(o); }
    private static JsonObject o(Object... p) { var r = new JsonObject(); for (int i = 0; i < p.length; i += 2) r.add((String) p[i], el(p[i + 1])); return r; }
    private static JsonObject add(Object a, Object b) { return o("type", "minecraft:add", "left", a, "right", b); }
    private static JsonObject sub(Object a, Object b) { return o("type", "minecraft:sub", "left", a, "right", b); }
    private static JsonObject mul(Object a, Object b) { return o("type", "minecraft:mul", "left", a, "right", b); }
    private static JsonObject cache(Object a) { return o("type", "minecraft:cache", "input", a); }
    private static JsonObject obj(Object... p) { return o(p); }
    private static JsonObject noiseRef(String id) { return o("type", "minecraft:noise", "noise", id, "xz_scale", 1.0, "y_scale", 0.0); }
    private static JsonObject range(Object input, double min, double max, Object in, Object out) {
        return o("type", "minecraft:range_choice", "input", input, "min_inclusive", min, "max_exclusive", max, "when_in_range", in, "when_out_of_range", out);
    }
    private static JsonObject spline(Object coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(o("location", p[0], "value", p[1], "derivative", p[2]));
        return o("type", "minecraft:spline", "spline", o("coordinate", coordinate, "points", list));
    }
    private static JsonObject rule(boolean top) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "freshgenerator:landform_surface");
        o.addProperty("height", L + "dry_floor");
        o.addProperty("top", top);
        return o;
    }
    private static JsonObject condition(String test, JsonObject then) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:condition");
        o.addProperty("if_true", test);
        o.add("then_run", then);
        return o;
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
}
