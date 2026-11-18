package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.DensityExpr;
import dev.freshgenerator.core.NaturalTerrainProgram;
import java.util.*;

/** Hierarchical climate fields over the final landscape. Native biome entries and
 * features remain authoritative; this layer introduces no biome IDs or codecs.
 */
public final class LandscapeBiomeCompiler {
    public static final String PREFIX = "freshgenerator:highlands_v2/";
    public static final String CLIMATE = PREFIX + "landscape/climate/";
    public static final String SETTINGS = "data/freshgenerator/worldgen/noise_settings/highlands_v2.json";
    public static final String ENABLED_LABEL = "FG landscape climate";
    public static final String ENABLED_FUNCTION = CLIMATE + "enabled";
    public static final double COOLING_START = 96, COOLING_END = 272, MAX_COOLING = 0.42;
    public static final double MAX_CONTINENTAL_DRYING = 0.08;
    public static final double MAX_TERRAIN_WETTING = 0.08, MAX_RIDGE_DRYING = 0.02;
    public static final Set<String> REPLACED = Set.of(SETTINGS, path(PREFIX + "temperature"),
            path(PREFIX + "vegetation"), path(PREFIX + "regions/biome_temperature"),
            path(PREFIX + "regions/biome_vegetation"));
    private static final Gson G = new Gson();

    private LandscapeBiomeCompiler() { }

    public static void apply(Map<String, JsonElement> out) {
        Objects.requireNonNull(out);
        // Stage all changes, so missing inputs or a repeated application cannot
        // leave half of a climate layer in the caller's catalog.
        Map<String, JsonElement> staged = new TreeMap<>(out);
        for (String name : List.of("temperature", "vegetation", "continents", "coherent/height",
                "coherent/depth_blocks", "regions/original_temperature", "regions/original_vegetation",
                "regions/biome_temperature", "regions/biome_vegetation", "landscape/valley_floor",
                "landscape/footslope", "landscape/mountain", "landscape/crest"))
            Objects.requireNonNull(staged.get(path(PREFIX + name)), "Missing landscape climate input: " + name);
        JsonObject settings = Objects.requireNonNull(staged.get(SETTINGS)).deepCopy().getAsJsonObject();
        JsonObject router = settings.getAsJsonObject("noise_router");
        for (String axis : List.of("temperature", "vegetation")) {
            if (!router.get(axis).getAsString().equals(PREFIX + "regions/biome_" + axis))
                throw new IllegalStateException("Unexpected regional climate routing: " + axis);
            put(staged, "regional_" + axis, staged.get(path(PREFIX + axis)).deepCopy());
        }

        // Separate noise seeds and scales bend ecological boundaries locally,
        // while the existing broad climate controls the region's identity.
        localNoise(staged, "temperature", -8, 0.75);
        localNoise(staged, "vegetation", -7, 0.75);
        put(staged, "reference_temperature", cache(clamp(add(ref("regional_temperature"),
                add(mul(0.08, PREFIX + "regions/original_temperature"), mul(0.05, ref("local_temperature")))))));
        // Registered ecosystem identity belongs to regional/subregional climate.
        // Fine rooting-site variation must not create a 40-block forest island
        // with a hundredfold jump in native tree attempts. Keep that variation
        // below in the ecological site fields, not in the biome lookup axes.
        put(staged, "zone_temperature", cache(clamp(add(ref("regional_temperature"),
                mul(0.08, PREFIX + "regions/original_temperature")))));
        put(staged, "continental_drying", cache(mul(MAX_CONTINENTAL_DRYING,
                smooth(-0.10, 0.65, PREFIX + "continents"))));
        // Broad landform suitability, not rainfall, runoff or a water-table model.
        // Local rooting-site curvature remains a separate, much smaller-scale
        // input to forest recruitment. Reference climate extremes stay dominant.
        put(staged, "terrain_moisture", cache(mul(smooth(63,79,PREFIX+"coherent/height"),
                sub(mul(MAX_TERRAIN_WETTING,max(PREFIX+"landscape/valley_floor",mul(.65,PREFIX+"landscape/footslope"))),
                        mul(MAX_RIDGE_DRYING,mul(PREFIX+"landscape/mountain",PREFIX+"landscape/crest"))))));
        put(staged, "moisture", cache(clamp(sub(add(ref("regional_vegetation"),
                add(add(mul(0.22, PREFIX + "regions/original_vegetation"), mul(0.12, ref("local_vegetation"))),ref("terrain_moisture"))),
                ref("continental_drying")))));
        put(staged, "biome_moisture", cache(clamp(sub(add(ref("regional_vegetation"),
                mul(0.08, PREFIX + "regions/original_vegetation")), ref("continental_drying")))));
        // The native coastal parameter boxes can select peaks even where our
        // final bed is below the sea. Reconcile only submerged coastal climate;
        // keep the physical C field, inland rivers and mushroom tail untouched.
        JsonElement coastal = sub(1, smooth(-0.11, 0.03, PREFIX + "continents"));
        JsonElement upper = add(-0.20, mul(1.30, smooth(61, 75, PREFIX + "coherent/height")));
        put(staged, "surface_continents", cache(sub(PREFIX + "continents",
                mul(coastal, max(sub(PREFIX + "continents", upper), 0)))));
        put(staged, "altitude_cooling", cache(mul(MAX_COOLING,
                smooth(COOLING_START, COOLING_END, PREFIX + "coherent/height"))));
        put(staged, "surface_temperature", cache(clamp(sub(ref("zone_temperature"), ref("altitude_cooling")))));

        // Existing snowline and forest ecology already account for elevation.
        // They must keep the reference climate, not receive altitude twice.
        staged.put(path(PREFIX + "temperature"), cache(ref("reference_temperature")));
        staged.put(path(PREFIX + "vegetation"), cache(ref("moisture")));
        // The surface climate uses column height, never the sampled absolute Y.
        // Below 64 blocks of cover restore the exact original cave climate.
        JsonElement deep = smooth(24, 64, PREFIX + "coherent/depth_blocks");
        for (String axis : List.of("temperature", "vegetation"))
            staged.put(path(PREFIX + "regions/biome_" + axis), object("type", "minecraft:lerp",
                    "alpha", deep.deepCopy(), "first", axis.equals("temperature") ? ref("surface_temperature") : ref("biome_moisture"),
                    "second", PREFIX + "regions/original_" + axis));
        put(staged, "biome_continents", object("type", "minecraft:lerp", "alpha", deep.deepCopy(),
                "first", ref("surface_continents"), "second", PREFIX + "continents"));
        if (!router.get("continents").getAsString().equals(PREFIX + "continents"))
            throw new IllegalStateException("Unexpected continental climate routing");
        router.addProperty("continents", ref("biome_continents"));
        put(staged, "enabled", new JsonPrimitive(1.0));
        settings.getAsJsonArray("debug_functions").add(object("label", ENABLED_LABEL, "function", ENABLED_FUNCTION));
        settings.getAsJsonArray("debug_functions").add(object("label", "FG landscape surface temperature", "function", ref("surface_temperature")));
        settings.getAsJsonArray("debug_functions").add(object("label", "FG regional biome moisture", "function", ref("biome_moisture")));
        settings.getAsJsonArray("debug_functions").add(object("label", "FG coastal biome continentalness", "function", ref("biome_continents")));
        staged.put(SETTINGS, settings);
        out.putAll(staged);
    }

    private static void localNoise(Map<String, JsonElement> out, String axis, int octave, double scale) {
        String id = CLIMATE + "local_" + axis;
        String noisePath = VanillaAssets.path("noise", id);
        if (out.putIfAbsent(noisePath, object("base_octave", octave, "octave_count", 2, "base_amplitude", 1.0)) != null)
            throw new IllegalStateException("Landscape climate noise already exists: " + id);
        put(out, "local_" + axis, cache(object("type", "minecraft:noise", "noise", id,
                "xz_scale", scale, "y_scale", 0.0, "shift_x", mul(0.20, "minecraft:shift_x"),
                "shift_z", mul(0.20, "minecraft:shift_z"))));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static String ref(String local) { return CLIMATE + local; }
    private static void put(Map<String, JsonElement> out, String name, JsonElement value) {
        if (out.putIfAbsent(path(ref(name)), value) != null)
            throw new IllegalStateException("Landscape climate output already exists: " + name);
    }
    private static JsonElement smooth(double lo, double hi, String input) {
        return G.toJsonTree(NaturalTerrainProgram.smootherstep(lo, hi, DensityExpr.ref(input)).json());
    }
    private static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add((String) pairs[i], G.toJsonTree(pairs[i + 1]));
        return result;
    }
    private static JsonObject binary(String operation, Object a, Object b) { return object("type", "minecraft:" + operation, "left", a, "right", b); }
    private static JsonObject add(Object a, Object b) { return binary("add", a, b); }
    private static JsonObject sub(Object a, Object b) { return binary("sub", a, b); }
    private static JsonObject mul(Object a, Object b) { return binary("mul", a, b); }
    private static JsonObject max(Object a, Object b) { return binary("max", a, b); }
    private static JsonObject cache(Object input) { return object("type", "minecraft:cache", "input", input); }
    private static JsonObject clamp(Object input) { return object("type", "minecraft:clamp", "input", input, "min", -1.0, "max", 1.0); }
}
