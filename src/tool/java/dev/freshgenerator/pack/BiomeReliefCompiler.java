package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** 0.9.0 landform relief where the climate selects a landform-defined biome.
 * Terrain cannot read the biome; it reads the same climate fields the native table
 * uses for that biome, so the relief and the biome coincide:
 * <ul>
 * <li>Badlands (lookup T >= 0.55 with erosion -0.6..0.03): stepped mesas, three
 *     benches of +24/+50/+80 blocks with steep sides and canyons between. Native
 *     absolute-Y terracotta bands turn the cliffs into layered walls. Deserts
 *     (higher erosion) and mountain cores (lower erosion) are excluded.</li>
 * <li>Windswept slice (erosion 0.45..0.55): rounded hills up to +44 blocks; the
 *     landscape layer had flattened this high-erosion band to H~80.</li>
 * </ul>
 * Uplift only (never lowers ground): the coastal floor, caves and aquifers stay valid.
 * Every input is a cheap cached noise read once; the landscape root is not re-read. */
public final class BiomeReliefCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", R = P + "relief/";
    public static final double[][] MESA_STEPS = {{-1, 0, 0}, {-.15, 0, 0}, {-.05, 24, 0}, {.15, 24, 0}, {.25, 50, 0}, {.45, 50, 0}, {.55, 80, 0}, {1, 80, 0}};
    public static final double[][] HILLS = {{-.5, 0, 0}, {.5, 44, 0}};
    public static final Set<String> REPLACED = Set.of(path(L + "height_unguarded"));
    private static final Gson G = new Gson();
    private BiomeReliefCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        if (staged.keySet().stream().anyMatch(k -> k.contains("/highlands_v2/relief/"))) throw new IllegalStateException("Relief layer already present");
        JsonObject unguarded = Objects.requireNonNull(staged.get(path(L + "height_unguarded")), "Missing unguarded landscape").deepCopy().getAsJsonObject();
        Objects.requireNonNull(staged.get(path(L + "climate/zone_temperature")), "Missing zone climate");
        if (!"minecraft:cache".equals(unguarded.get("type").getAsString())) throw new IllegalStateException("Unexpected landscape root");
        for (String n : List.of("mesa", "hills")) staged.put(VanillaAssets.path("noise", R + n), obj("base_octave", n.equals("mesa") ? -8 : -7, "octave_count", 1, "base_amplitude", 1.0));
        put(staged, "mesa_noise", cache(obj("type", "minecraft:noise", "noise", R + "mesa", "xz_scale", 1.0, "y_scale", 0.0)));
        put(staged, "hills_noise", cache(obj("type", "minecraft:noise", "noise", R + "hills", "xz_scale", 1.0, "y_scale", 0.0)));
        put(staged, "hot", cache(spline(L + "climate/zone_temperature", new double[][]{{.50, 0, 0}, {.58, 1, 0}})));
        put(staged, "badlands_erosion", cache(spline(P + "erosion", new double[][]{{-.70, 0, 0}, {-.55, 1, 0}, {0, 1, 0}, {.08, 0, 0}})));
        put(staged, "windswept_erosion", cache(spline(P + "erosion", new double[][]{{.38, 0, 0}, {.45, 1, 0}, {.53, 1, 0}, {.58, 0, 0}})));
        put(staged, "inland", cache(spline(P + "continents", new double[][]{{0, 0, 0}, {.12, 1, 0}})));
        put(staged, "valley_clear", cache(spline(P + "ridges_folded", new double[][]{{-.85, 0, 0}, {-.55, 1, 0}})));
        String land = put(staged, "land_mask", cache(mul(R + "inland", R + "valley_clear")));
        String mesa = put(staged, "mesa", cache(mul(mul(mul(R + "hot", R + "badlands_erosion"), land), spline(R + "mesa_noise", MESA_STEPS))));
        String hills = put(staged, "hills", cache(mul(mul(R + "windswept_erosion", land), spline(R + "hills_noise", HILLS))));
        String relief = put(staged, "biome_relief", cache(add(mesa, hills)));
        staged.put(path(L + "height_unguarded"), cache(add(unguarded.get("input"), relief)));
        output.putAll(staged);
    }
    private static String put(Map<String, JsonElement> out, String name, JsonElement value) {
        if (out.putIfAbsent(path(R + name), value) != null) throw new IllegalStateException("Relief node exists: " + name);
        return R + name;
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
