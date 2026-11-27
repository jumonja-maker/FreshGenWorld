package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Deserts of the current world (user request 26.11: "deserts are too few and too small; real deserts are wide and
 * large with constant changes of height"; this reverses the earlier "rarity is a plus" decision).
 * <ul>
 * <li><b>Climate ({@link #applyClimate}).</b> Measured on eight seeds (24 km windows): zone temperature at or above
 * the native desert column (0.55) covered 4.5% of the land, the frozen column 12.4%. The zone temperature is
 * stretched upward from HOT_IN: a zone of 0.475 now reaches the desert column, so the hot belt roughly doubles and its
 * patches grow into large contiguous deserts; savanna keeps the margin. Applied before the island climate, so every
 * consumer (biomes, dry land, water, snow line) reads the same climate.</li>
 * <li><b>Relief ({@link #applyRelief}).</b> Inside the hot lowland: sand seas (ergs) of transverse dunes
 * (freshgenerator:dunes, 3-12 blocks), elsewhere stepped rock tablelands (hamada) with scarps and rare inselbergs.
 * Mountains, ranges, coasts and the badlands rows (low erosion) keep their own relief.</li>
 * </ul> */
public final class DesertCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", CL = L + "climate/", D = L + "desert/";
    /** Climate stretch: T' = T + HOT_SHIFT x smoothstep(HOT_IN, HOT_FULL, T). */
    public static final double HOT_SHIFT = 0.08, HOT_IN = 0.28, HOT_FULL = 0.50;
    /** Relief weight: zone temperature band of the desert core. */
    public static final double CORE_IN = 0.52, CORE_FULL = 0.58;
    public static final double DUNE_SPACING = 96, DUNE_MIN = 3, DUNE_SPREAD = 9;
    public static final double TIER_1 = 14, TIER_2 = 10, INSELBERG = 34;
    private static final Gson G = new Gson();
    private DesertCompiler() { }

    public static void applyRelief(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        if (staged.containsKey(path(D + "relief"))) throw new IllegalStateException("Desert relief already present");
        JsonObject uplift = Objects.requireNonNull(staged.get(path(L + "uplift")), "Missing uplift").getAsJsonObject();
        if (!"minecraft:cache".equals(uplift.get("type").getAsString())) throw new IllegalStateException("Unexpected uplift");
        Objects.requireNonNull(staged.get(path(L + "range/uplift")), "Ranges must come first");
        noise(staged, "erg", -11, 2); noise(staged, "dune_size", -9, 1); noise(staged, "tableland", -10, 2); noise(staged, "inselberg", -9, 1);

        JsonObject hot = spline(CL + "zone_temperature", new double[][]{{CORE_IN, 0, 0}, {CORE_FULL, 1, 0}});
        JsonObject inland = spline(P + "continents", new double[][]{{-.12, 0, 0}, {0.0, 1, 0}});
        JsonObject lowland = spline(P + "erosion", new double[][]{{-.35, 0, 0}, {-.20, 1, 0}});
        JsonObject flat = mul(add(1.0, mul(-1.0, L + "mountain")), add(1.0, mul(-1.0, spline(L + "range/uplift", new double[][]{{8, 0, 0}, {35, 1, 0}}))));
        // The weight and the slow fields (wavelengths of 512 blocks and more) are read from a 32-block cubic lattice:
        // every height sample of the drainage model reads this layer (a direct evaluation cost a desert x1.5).
        JsonObject weightRaw = mul(mul(hot, inland), mul(lowland, flat));
        String weight = node(staged, "weight", cache(obj("type", "minecraft:clamp", "min", 0.0, "max", 1.0,
                "input", obj("type", "freshgenerator:lattice", "spacing", 32, "interpolation", "cubic", "input", weightRaw))));
        String erg = node(staged, "erg", cache(obj("type", "minecraft:clamp", "min", 0.0, "max", 1.0, "input", obj("type", "freshgenerator:lattice",
                "spacing", 32, "interpolation", "cubic", "input", spline(noiseRef("erg"), new double[][]{{-.10, 0, 0}, {.30, 1, 0}})))));
        String size = node(staged, "dune_amplitude", cache(obj("type", "freshgenerator:lattice", "spacing", 32, "interpolation", "cubic",
                "input", add(DUNE_MIN, mul(DUNE_SPREAD, spline(noiseRef("dune_size"), new double[][]{{-.5, 0, 0}, {.5, 1, 0}}))))));
        JsonObject amplitude = mul(mul(weight, erg), size);
        String dunes = node(staged, "dunes", cache(obj("type", "freshgenerator:dunes", "spacing", DUNE_SPACING, "amplitude", amplitude)));
        String table = node(staged, "tableland_noise", cache(noiseRef("tableland")));
        JsonObject tiers = add(mul(TIER_1, spline(table, new double[][]{{.26, 0, 0}, {.31, 1, 0}})), mul(TIER_2, spline(table, new double[][]{{.46, 0, 0}, {.50, 1, 0}})));
        String rock = node(staged, "rock", cache(mul(mul(weight, add(1.0, mul(-1.0, erg))), tiers)));
        String insel = node(staged, "inselbergs", cache(mul(mul(weight, add(1.0, mul(-0.6, erg))),
                mul(INSELBERG, spline(noiseRef("inselberg"), new double[][]{{.58, 0, 0}, {.70, .55, 3.0}, {.80, 1, 0}})))));
        // Outside the deserts (weight 0) none of the relief fields is evaluated.
        String relief = node(staged, "relief", cache(obj("type", "minecraft:range_choice", "input", weight, "min_inclusive", 1.0e-4, "max_exclusive", 2.0,
                "when_in_range", add(dunes, add(rock, insel)), "when_out_of_range", 0.0)));
        staged.put(path(L + "uplift"), cache(add(uplift.get("input").deepCopy(), relief)));
        output.putAll(staged);
    }

    public static void applyClimate(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        String local = path(CL + "zone_temperature_local");
        JsonElement zone = Objects.requireNonNull(staged.get(local), "Climate shape must come first").deepCopy();
        if (staged.containsKey(path(CL + "zone_temperature_unstretched"))) throw new IllegalStateException("Desert climate already present");
        staged.put(path(CL + "zone_temperature_unstretched"), cache(zone));
        String t = CL + "zone_temperature_unstretched";
        staged.put(local, cache(add(t, mul(HOT_SHIFT, spline(t, new double[][]{{HOT_IN, 0, 0}, {HOT_FULL, 1, 0}})))));
        output.putAll(staged);
    }

    private static void noise(Map<String, JsonElement> out, String name, int octave, int count) {
        if (out.putIfAbsent(VanillaAssets.path("noise", D + name), obj("base_octave", octave, "octave_count", count, "base_amplitude", 1.0)) != null)
            throw new IllegalStateException("Desert noise exists: " + name);
    }
    private static String node(Map<String, JsonElement> out, String name, JsonElement value) {
        if (out.putIfAbsent(path(D + name), value) != null) throw new IllegalStateException("Desert node exists: " + name);
        return D + name;
    }
    private static JsonObject noiseRef(String name) { return obj("type", "minecraft:noise", "noise", D + name, "xz_scale", 1.0, "y_scale", 0.0); }
    private static JsonObject spline(Object coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", e(coordinate), "points", list));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement e(Object o) { return o instanceof JsonElement j ? j.deepCopy() : G.toJsonTree(o); }
    private static JsonObject obj(Object... p) { var o = new JsonObject(); for (int i = 0; i < p.length; i += 2) o.add((String) p[i], e(p[i + 1])); return o; }
    private static JsonObject add(Object a, Object b) { return obj("type", "minecraft:add", "left", a, "right", b); }
    private static JsonObject mul(Object a, Object b) { return obj("type", "minecraft:mul", "left", a, "right", b); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
}
