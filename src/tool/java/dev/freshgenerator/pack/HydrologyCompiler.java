package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Water on land 1.0 (docs/hydrology-1.0.md).
 * <ul>
 * <li>Removes the native river trench from the terrain spline: base_offset reads a copy of the peaks-and-valleys
 * axis whose valley part (|weirdness| near zero, the native "river" contour loops at sea level) is lifted to the
 * level of the valley flanks. The landscape program itself is unchanged.</li>
 * <li>Removes the native river slice from the biome table (|weirdness| below 0.05 is read as the neighbouring
 * slice); river biomes come from real channels.</li>
 * <li>Adds {@code coherent/terrain}: the drainage-network terrain ({@code freshgenerator:hydrology}) over the same
 * ground. The terrain density, the aquifer surface oracle and the karst caves (roof, entrances) follow it; climate,
 * biome depth and material slopes keep the base height, so remote biome lookups never run the drainage model.</li>
 * </ul> */
public final class HydrologyCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", CL = L + "climate/", H = P + "hydrology/";
    public static final String BASE_HEIGHT = P + "coherent/height", TERRAIN = P + "coherent/terrain";
    public static final String LABEL = "FreshGenerator hydrology";
    /** Valley trench removal on the peaks-and-valleys axis (monotone; flanks from -0.30 up unchanged). */
    public static final double[][] TRENCH = {{-1.0, -0.42, 0}, {-0.55, -0.415, 0.03}, {-0.30, -0.30, 1.0}, {1.0, 1.0, 1.0}};
    public static final double RIVER_SLICE = 0.0501;
    /** The only feature definitions of the pack: a new feature in the mod's namespace (lily pads); no native
     * feature, biome or structure is overridden. */
    public static final Set<String> DECOR = Set.of(VanillaAssets.path("feature", "freshgenerator:hydro_decor"),
            VanillaAssets.path("placed_feature", "freshgenerator:hydro_decor"));
    /** Material rules of the current world that read the ground height. */
    static final List<String> SURFACE_RULES = List.of("surface", "biome_surface", "under_biome_surface");
    private static final Gson G = new Gson();
    private HydrologyCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        if (staged.containsKey(path(TERRAIN))) throw new IllegalStateException("Hydrology already present");
        // 1. terrain spline without the native river trench
        staged.put(path(H + "ridges_folded_terrain"), cache(spline(P + "ridges_folded", TRENCH)));
        String offsetPath = path(P + "base_offset");
        JsonElement offset = Objects.requireNonNull(staged.get(offsetPath), "Missing base offset").deepCopy();
        if (replaceRefs(offset, P + "ridges_folded", H + "ridges_folded_terrain") == 0) throw new IllegalStateException("base_offset without ridges");
        staged.put(offsetPath, offset);
        // 2. biome table without the native river slice
        JsonObject settings = staged.get(SurfaceOnlyCompiler.SETTINGS).deepCopy().getAsJsonObject();
        JsonObject router = settings.getAsJsonObject("noise_router");
        String ridges = router.get("ridges").getAsString();
        JsonObject slice = obj("type", "minecraft:range_choice", "input", ridges, "min_inclusive", 0.0, "max_exclusive", RIVER_SLICE,
                "when_in_range", RIVER_SLICE, "when_out_of_range", -RIVER_SLICE);
        // Weirdness of the biome lookup stays within [-1, 1] (every surface biome's range): beyond it the cave biome
        // sulfur_caves (W -1.1..-0.85, E 0.45..1) beat the surface biomes on valley floors whose erosion the belt
        // rules raised to 0.45 - a sulfur lake at the surface (audit 22.11 B-1). Cave biomes below keep their range.
        staged.put(path(H + "biome_ridges"), cache(obj("type", "minecraft:clamp", "min", -1.0, "max", 1.0,
                "input", obj("type", "minecraft:range_choice", "input", ridges, "min_inclusive", -RIVER_SLICE,
                "max_exclusive", RIVER_SLICE, "when_in_range", slice, "when_out_of_range", ridges))));
        router.addProperty("ridges", H + "biome_ridges");
        // 3. drainage-network terrain and its consumers. The water reads the regional humidity and applies the biome
        // table's dry belt itself (HydroClimate.dryBelt): with the frozen_moisture chain as input every lattice node
        // evaluated the landscape height again (+35% on a plain).
        staged.put(path(TERRAIN), obj("type", "freshgenerator:hydrology", "base", L + "dry_floor", "continents", P + "continents",
                "temperature", CL + "zone_temperature", "moisture", CL + "biome_moisture", "erosion", P + "erosion", "sea_level", 63));
        staged.put(path(H + "dry_banks"), obj("type", "freshgenerator:hydrology", "base", L + "dry_floor", "continents", P + "continents",
                "temperature", CL + "zone_temperature", "moisture", CL + "biome_moisture", "erosion", P + "erosion", "sea_level", 63, "output", "dry_banks"));
        // Cave entrances and shafts keep clear of rivers and lakes (no cave mouth in a river bank, no leak).
        for (String mask : List.of("karst/entrance_allow", "karst/pothole_allow")) {
            String file = path(P + mask);
            if (!staged.containsKey(file)) continue; // compiler stages without the karst layer (tests)
            JsonObject original = staged.get(file).getAsJsonObject();
            staged.put(file, cache(obj("type", "minecraft:mul", "left", original.get("input").deepCopy(), "right", H + "dry_banks")));
        }
        int rewired = 0;
        for (String file : new ArrayList<>(staged.keySet())) {
            if (!consumes(file)) continue;
            JsonElement copy = staged.get(file).deepCopy();
            int n = replaceRefs(copy, BASE_HEIGHT, TERRAIN);
            if (n > 0) { staged.put(file, copy); rewired += n; }
        }
        if (rewired < 3) throw new IllegalStateException("Too few terrain consumers rewired: " + rewired);
        // Surface materials (cliffs, scree, forest floors, shores) read the carved ground: a valley deeper than the
        // material window kept the pre-river height and lost these rules on its floor and walls (audit 22.11 FG-01).
        int materials = 0;
        for (String rule : SURFACE_RULES) {
            String file = VanillaAssets.path("material_rule", P + rule);
            if (!staged.containsKey(file)) continue; // compiler stages without the surface layer (tests)
            JsonElement copy = staged.get(file).deepCopy();
            materials += replaceRefs(copy, BASE_HEIGHT, TERRAIN);
            staged.put(file, copy);
        }
        if (materials > 0 && materials < SURFACE_RULES.size()) throw new IllegalStateException("Surface rules partly rewired: " + materials);
        // Life on calm water (lily pads); the feature is added to overworld biomes by the mod and is a no-op elsewhere.
        if (!DECOR.contains(VanillaAssets.path("feature", "freshgenerator:hydro_decor"))) throw new IllegalStateException("decor paths");
        staged.put(VanillaAssets.path("feature", "freshgenerator:hydro_decor"), obj("type", "freshgenerator:hydro_decor"));
        staged.put(VanillaAssets.path("placed_feature", "freshgenerator:hydro_decor"), obj("feature", "freshgenerator:hydro_decor", "placement", new JsonArray()));
        JsonArray debug = settings.getAsJsonArray("debug_functions");
        // The shared ground oracle (glacier bed search, village grading, carver guard) is the carved ground too.
        int oracle = 0;
        for (JsonElement e : debug) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("label").getAsString().equals(dev.freshgenerator.core.CaveAccessModel.HEIGHT_LABEL)
                    && o.get("function").getAsString().equals(BASE_HEIGHT)) { o.addProperty("function", TERRAIN); oracle++; }
        }
        if (oracle > 1) throw new IllegalStateException("Ground oracle declared twice: " + oracle);
        debug.add(obj("label", LABEL, "function", TERRAIN));
        // F3 labels show the inputs the biome lookup really reads (code audit 24.11 F-8/P3: C, E, D and W showed the raw
        // fields, "FG lookup W" an intermediate axis - 474 of 4096 points differed from the lookup's weirdness).
        Map<String, String> routed = Map.of("C", "continents", "E", "erosion", "D", "depth", "W", "ridges", "T", "temperature",
                "V", "vegetation", "FG lookup E", "erosion", "FG lookup W", "ridges");
        for (JsonElement e : debug) {
            JsonObject o = e.getAsJsonObject();
            String field = routed.get(o.get("label").getAsString());
            if (field != null && router.get(field).isJsonPrimitive()) o.addProperty("function", router.get(field).getAsString());
        }
        staged.put(SurfaceOnlyCompiler.SETTINGS, settings);
        output.putAll(staged);
    }

    /** Terrain-shape consumers that follow the carved ground (density, aquifer oracle, caves). */
    static boolean consumes(String file) {
        String probe = VanillaAssets.path("density_function", P + "x");
        String d = probe.substring(0, probe.length() - "x.json".length());
        if (!file.startsWith(d)) return false;
        String rel = file.substring(d.length());
        return rel.equals("coherent/surface_density.json") || rel.equals("preliminary_surface_level.json")
                || rel.startsWith("karst/") || rel.equals("cave_access/land.json");
    }

    static int replaceRefs(JsonElement e, String from, String to) {
        int n = 0;
        if (e.isJsonObject()) {
            for (var entry : e.getAsJsonObject().entrySet()) {
                JsonElement v = entry.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().equals(from)) { entry.setValue(new JsonPrimitive(to)); n++; }
                else n += replaceRefs(v, from, to);
            }
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement v = a.get(i);
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().equals(from)) { a.set(i, new JsonPrimitive(to)); n++; }
                else n += replaceRefs(v, from, to);
            }
        }
        return n;
    }
    private static JsonObject spline(String coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", coordinate, "points", list));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
    private static JsonObject obj(Object... p) {
        var o = new JsonObject();
        for (int i = 0; i < p.length; i += 2) o.add((String) p[i], p[i + 1] instanceof JsonElement j ? j : G.toJsonTree(p[i + 1]));
        return o;
    }
}
