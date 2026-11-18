package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Current world's continuous solid terrain. Caves are intentionally absent: a
 * vertical roof alone cannot prevent side entrances on steep slopes. The exterior,
 * native aquifer surface oracle and native carver opt-out share the final height.
 * Structure beardifier and subsequent structure/feature placement remain native.
 */
public final class SurfaceOnlyCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX;
    public static final String ROOT = CoherentSurfaceCompiler.ROOT;
    public static final String HEIGHT = P + "coherent/height";
    public static final String SURFACE = ROOT + "coherent/surface_density.json";
    public static final String CAVE_WEIGHT = ROOT + "coherent/cave_weight.json";
    public static final String FINAL_DENSITY = ROOT + "final_density.json";
    public static final String PRELIMINARY = ROOT + "preliminary_surface_level.json";
    public static final String SETTINGS = "data/freshgenerator/worldgen/noise_settings/highlands_v2.json";
    public static final String DISABLED_LABEL = "FG caves disabled";
    public static final String DISABLED_FUNCTION = P + "coherent/surface_only/caves_disabled";
    public static final Set<String> REPLACED = Set.of(SURFACE, CAVE_WEIGHT, FINAL_DENSITY, SETTINGS);
    private static final Gson G = new Gson();

    private SurfaceOnlyCompiler() { }

    public static void apply(Map<String, JsonElement> out) {
        Objects.requireNonNull(out);
        for (String path : REPLACED) Objects.requireNonNull(out.get(path), "Missing surface input: " + path);
        Objects.requireNonNull(out.get(PRELIMINARY), "Missing aquifer surface input");
        Objects.requireNonNull(out.get(VanillaAssets.path("density_function", HEIGHT)), "Missing final terrain height");
        JsonObject original = out.get(FINAL_DENSITY).getAsJsonObject();
        if (!"minecraft:add".equals(original.get("type").getAsString())
                || !original.has("right") || !original.get("right").isJsonObject()
                || !"minecraft:beardifier".equals(original.getAsJsonObject("right").get("type").getAsString()))
            throw new IllegalStateException("Expected native structure beardifier outside terrain body");

        JsonObject settings = out.get(SETTINGS).deepCopy().getAsJsonObject();
        JsonArray debug = Objects.requireNonNull(settings.getAsJsonArray("debug_functions"), "Missing debug samplers");
        for (JsonElement item : debug) if (item.getAsJsonObject().get("label").getAsString().equals(DISABLED_LABEL))
            throw new IllegalStateException("Cave opt-out already installed");
        String marker = VanillaAssets.path("density_function", DISABLED_FUNCTION);
        if (out.containsKey(marker)) throw new IllegalStateException("Duplicate cave opt-out resource");
        debug.add(object("label", DISABLED_LABEL, "function", DISABLED_FUNCTION));

        out.put(marker, new JsonPrimitive(1.0));
        out.put(SURFACE, object("type", "minecraft:squeeze", "input", mul(sub(HEIGHT, "minecraft:y"), 0.08)));
        out.put(CAVE_WEIGHT, new JsonPrimitive(0.0));
        out.put(FINAL_DENSITY, add(P + "coherent/surface_density", original.get("right").deepCopy()));
        out.put(PRELIMINARY, object("type", "minecraft:cache", "input", sub(HEIGHT, 8.0)));
        out.put(SETTINGS, settings);
    }

    private static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add((String) pairs[i], G.toJsonTree(pairs[i + 1]));
        return result;
    }
    private static JsonObject binary(String operation, Object left, Object right) {
        return object("type", "minecraft:" + operation, "left", left, "right", right);
    }
    private static JsonObject add(Object left, Object right) { return binary("add", left, right); }
    private static JsonObject sub(Object left, Object right) { return binary("sub", left, right); }
    private static JsonObject mul(Object left, Object right) { return binary("mul", left, right); }
}
