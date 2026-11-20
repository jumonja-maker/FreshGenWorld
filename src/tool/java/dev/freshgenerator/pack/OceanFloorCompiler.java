package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;
import static dev.freshgenerator.core.OceanFloor.*;

/** 0.9.1 ocean floor provinces on the current world (see {@link dev.freshgenerator.core.OceanFloor}).
 * Adds landscape/ocean_relief to landscape/height_unguarded (read once) and bends the
 * open ocean softly toward CEILING. The relief branch is evaluated only for open-ocean
 * continentalness (range_choice), so land and coasts cost nothing extra. */
public final class OceanFloorCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", O = P + "ocean/";
    public static final String C = P + "continents", H = L + "native_height";
    public static final Set<String> REPLACED = Set.of(path(L + "height_unguarded"));
    private static final Gson G = new Gson();
    private OceanFloorCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        if (staged.containsKey(path(L + "ocean_relief"))) throw new IllegalStateException("Ocean floor layer already present");
        JsonObject unguarded = Objects.requireNonNull(staged.get(path(L + "height_unguarded")), "Missing height_unguarded").getAsJsonObject();
        if (!"minecraft:cache".equals(unguarded.get("type").getAsString())) throw new IllegalStateException("Unexpected height_unguarded");

        noise(staged, "province", PROVINCE_OCTAVE, 1); noise(staged, "ridge", RIDGE_OCTAVE, 1);
        noise(staged, "mount", MOUNT_OCTAVE, 1); noise(staged, "swell", SWELL_OCTAVE, 2); noise(staged, "trench", TRENCH_OCTAVE, 1);
        noise(staged, "size", SIZE_OCTAVE, 1);
        node(staged, "province", cache(noiseRef("province")));
        // Deep open floor: away from coasts and off the native shelf plateau.
        // Fades out toward mushroom-island continentalness so the range_choice edge is continuous.
        node(staged, "deep", cache(mul(mul(sub(1, smooth(C_FADE, C_OPEN, C)), smooth(C_MUSHROOM, C_MUSHROOM + .1, C)),
                sub(1, smooth(DEEP_LOW, DEEP_HIGH, H)))));
        node(staged, "basin_mask", cache(smooth(-.25, .45, O + "province")));
        node(staged, "ridge_mask", cache(smooth(-.15, .35, O + "province_neg")));
        node(staged, "province_neg", cache(mul(-1, O + "province")));
        node(staged, "ridge_line", cache(abs(noiseRef("ridge"))));
        node(staged, "trench_line", cache(abs(noiseRef("trench"))));
        node(staged, "mount_noise", cache(noiseRef("mount")));
        // Slow size field: each seamount and each stretch of a trench gets its own scale.
        node(staged, "size_noise", cache(noiseRef("size")));
        node(staged, "size", cache(smooth(-.6, .6, O + "size_noise")));
        JsonElement basin = mul(-BASIN, O + "basin_mask");
        JsonElement swell = mul(SWELL, noiseRef("swell"));
        JsonElement ridge = mul(O + "ridge_mask", add(mul(RIDGE, sub(1, smooth(0, RIDGE_WIDTH, O + "ridge_line"))),
                mul(-RIFT, sub(1, smooth(0, RIFT_WIDTH, O + "ridge_line")))));
        // Rounded cone: the noise peak never reaches MOUNT_HIGH, so summits stay domed.
        JsonElement mount = mul(mul(MOUNT, add(MOUNT_MIN_SHARE, mul(1 - MOUNT_MIN_SHARE, O + "size"))), smooth(MOUNT_LOW, MOUNT_HIGH, O + "mount_noise"));
        JsonElement trench = mul(mul(-TRENCH, add(TRENCH_MIN_SHARE, mul(1 - TRENCH_MIN_SHARE, O + "size"))),
                mul(smooth(.5, .8, O + "province"), sub(1, smooth(0, TRENCH_WIDTH, O + "trench_line"))));
        node(staged, "relief", cache(mul(O + "deep", add(add(basin, swell), add(add(ridge, mount), trench)))));
        // Only for open-ocean continentalness; land, coasts and mushroom islands get 0.
        staged.put(path(L + "ocean_relief"), cache(choice(C, C_MUSHROOM, C_OPEN, O + "relief", 0.0)));
        // The previous root is read once; the soft ceiling is evaluated only on the open ocean.
        node(staged, "raw", cache(add(unguarded.get("input").deepCopy(), L + "ocean_relief")));
        staged.put(path(L + "height_unguarded"), cache(choice(C, C_MUSHROOM, C_OPEN, spline(O + "raw", CAP), O + "raw")));
        seabedRule(staged);
        output.putAll(staged);
    }

    public static final String SURFACE = "data/freshgenerator/worldgen/material_rule/highlands_v2/surface.json";
    /** The deep-water floor is chosen by the last on_floor branch of the native surface
     * rule (warm seas sand, else gravel_or_stone_if_ceiling); the ecotone rule only ran in
     * the dry and shallow branches. Put the same ecotone rule first in that branch: it acts
     * only on ocean IDs at least 6 blocks under water and otherwise returns no block. */
    static void seabedRule(Map<String, JsonElement> staged) {
        // Update 19.11 (audit O2, M1): the seabed copies read the province relief, and a
        // sediment body of native surface depth (several blocks, varying) lies under the
        // floor of deep water, where the native rule left bare stone below one block.
        JsonObject surface = Objects.requireNonNull(staged.get(SURFACE), "Missing surface rule").deepCopy().getAsJsonObject();
        JsonArray top = surface.getAsJsonArray("sequence");
        JsonObject last = top.get(top.size() - 1).getAsJsonObject();
        if (!"minecraft:on_floor".equals(last.get("if_true").getAsString()) || !last.get("then_run").toString().contains("gravel_or_stone_if_ceiling"))
            throw new IllegalStateException("Unexpected deep-floor branch");
        JsonObject ecotone = findEcotone(surface);
        if (ecotone == null || ecotone.get("subsurface").getAsBoolean()) throw new IllegalStateException("Ecotone surface rule not found");
        JsonArray branch = last.getAsJsonObject("then_run").getAsJsonArray("sequence");
        JsonObject floor = ecotone.deepCopy(); floor.addProperty("ocean_relief", L + "ocean_relief");
        JsonArray seeded = new JsonArray(); seeded.add(floor); branch.forEach(seeded::add);
        last.getAsJsonObject("then_run").add("sequence", seeded);
        JsonObject under = floor.deepCopy(); under.addProperty("subsurface", true);
        // Under an overhang the native rule keeps stone; loose sediment never hangs over air.
        // Only under deep water: shallower and dry columns keep their earlier native branches.
        JsonObject supported = obj("type", "minecraft:condition", "if_true", obj("type", "minecraft:not", "invert", "minecraft:not_under_deep_water"),
                "then_run", obj("type", "minecraft:condition", "if_true", obj("type", "minecraft:not", "invert", "minecraft:on_ceiling"), "then_run", under));
        top.add(obj("type", "minecraft:condition", "if_true", "minecraft:under_floor", "then_run", supported));
        JsonElement deep = top.remove(top.size() - 2); top.add(deep);
        staged.put(SURFACE, surface);
    }
    private static JsonObject findEcotone(JsonElement n) {
        if (n.isJsonObject()) {
            var o = n.getAsJsonObject();
            if (o.has("type") && "freshgenerator:ecotone_surface".equals(o.get("type").getAsString())) return o;
            for (var e : o.entrySet()) { var r = findEcotone(e.getValue()); if (r != null) return r; }
        } else if (n.isJsonArray()) for (var e : n.getAsJsonArray()) { var r = findEcotone(e); if (r != null) return r; }
        return null;
    }
    private static void noise(Map<String, JsonElement> out, String name, int octave, int count) {
        if (out.putIfAbsent(VanillaAssets.path("noise", O + name), obj("base_octave", octave, "octave_count", count, "base_amplitude", 1.0)) != null)
            throw new IllegalStateException("Ocean noise exists: " + name);
    }
    private static void node(Map<String, JsonElement> out, String name, JsonElement value) {
        if (out.putIfAbsent(path(O + name), value) != null) throw new IllegalStateException("Ocean node exists: " + name);
    }
    private static JsonObject noiseRef(String name) { return obj("type", "minecraft:noise", "noise", O + name, "xz_scale", 1.0, "y_scale", 0.0); }
    /** Cubic smoothstep of a referenced value between lo and hi (the value is read via references only). */
    static JsonObject smooth(double lo, double hi, String value) {
        JsonObject t = obj("type", "minecraft:clamp", "input", mul(sub(value, lo), 1 / (hi - lo)), "min", 0.0, "max", 1.0);
        return mul(mul(t, t.deepCopy()), sub(3.0, mul(2.0, t.deepCopy())));
    }
    private static JsonObject spline(String coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", coordinate, "points", list));
    }
    private static JsonObject choice(String input, double lo, double hi, Object inside, Object outside) {
        return obj("type", "minecraft:range_choice", "input", input, "min_inclusive", lo, "max_exclusive", hi, "when_in_range", inside, "when_out_of_range", outside);
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement e(Object o) { return o instanceof JsonElement j ? j : G.toJsonTree(o); }
    private static JsonObject obj(Object... p) { var o = new JsonObject(); for (int i = 0; i < p.length; i += 2) o.add((String) p[i], e(p[i + 1])); return o; }
    private static JsonObject bin(String op, Object a, Object b) { return obj("type", "minecraft:" + op, "left", a, "right", b); }
    private static JsonObject add(Object a, Object b) { return bin("add", a, b); }
    private static JsonObject sub(Object a, Object b) { return bin("sub", a, b); }
    private static JsonObject mul(Object a, Object b) { return bin("mul", a, b); }
    private static JsonObject abs(Object a) { return obj("type", "minecraft:abs", "input", a); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
}
