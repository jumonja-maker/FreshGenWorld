package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** 0.9.2 (audit "build10", own check 21.11): hot dry lowland is not a sheet of puddles.
 * <p>The coastal floor (0.8.2) lowers ground whose native height is below 63.5 into proper water
 * bodies, but land with a native height just below that threshold ended on the steep part of the
 * spline at H 56..63: desert, badlands and savanna covered by 1-2 blocks of sea-level water (FULL,
 * seed 8675309 X-1472 Z-6304: water over sand in 77% of a 48x48 window). In hot and dry climates
 * such flats and the 2-10 block lagoons next to them are lifted to just above sea level (dry pans keep
 * a compressed micro-relief).
 * <p>Untouched: ocean and the coastal band (continentalness), river valleys (|weirdness| below the
 * native valley slice and a little beyond), the swamp and mangrove erosion row, temperate and cold
 * lowland (marshy flats and lakes there stay vanilla-like), real basins (below H50), and all
 * ground already above sea level. The weight reads only height-free fields (no feedback through
 * the coherent height); the height itself is read once, behind a range_choice. */
public final class DryLandCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", CL = L + "climate/";
    /** Desert band (any humidity) and the warm band where only dry humidity counts. */
    public static final double DESERT_IN = .45, DESERT_FULL = .55, WARM_IN = .18, WARM_FULL = .21, M_DRY_IN = -.05, M_DRY_FULL = -.15;
    /** Land beyond the coastal band, outside river valleys, outside the swamp/mangrove row. */
    public static final double C_IN = -.16, C_FULL = -.10, W_IN = .05, W_FULL = .06, E_WET_FULL = .40, E_WET_OUT = .50;
    /** Basins deeper than H_DEEP keep their water; the lift is complete from H_FLAT (the flooded flats
     * measured at native height 60.4..61.4, H 58..62, and lagoons down to about H54). */
    public static final double H_DEEP = 50, H_FLAT = 55;
    /** Lifted height = H + w * SLOPE * (TOP - H) below TOP: H55 -> 63.6, H60 -> 65.0, H64 -> 66.2, continuous at
     * TOP. Realism audit 0.9.2 (A092-02): the first version (TOP 64.64) squeezed H55..64.6 sixteen times into one
     * level at Y64; this keeps the low relief at about a third of its height.
     * <p>Updated realism audit (A092-05): with the same weight the flatter curve alone lifted less than the first one
     * below H 56.98 (up to -0.6 blocks near H53.6), so low lagoon edges in deserts came back under water. The lift is
     * the larger of both curves: never below the first version, the relief curve above H57 unchanged; the lagoon
     * floors H50..57 end as a dry pan at Y63.7..64.2. */
    public static final double TOP = 67, SLOPE = .7167, LOW_TOP = 64.64, LOW_SLOPE = .9375;
    public static final Set<String> REPLACED = Set.of(path(P + "coherent/height"));
    // (also rewrites the three current material rules without the dead "continentality" setting)
    public static final Set<String> ADDED = Set.of(path(L + "dry_weight"), path(L + "dry_floor"));
    private static final Gson G = new Gson();
    private DryLandCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        for (String p : ADDED) if (staged.containsKey(p)) throw new IllegalStateException("Dry land layer already present");
        JsonObject coherent = Objects.requireNonNull(staged.get(path(P + "coherent/height")), "Missing coherent height").deepCopy().getAsJsonObject();
        JsonObject interpolated = coherent.getAsJsonObject("input");
        if (!"minecraft:cache".equals(coherent.get("type").getAsString()) || !"minecraft:interpolated".equals(interpolated.get("type").getAsString())
                || !interpolated.get("input").getAsString().equals(L + "height_limited"))
            throw new IllegalStateException("Unexpected coherent height");
        String h = L + "height_limited";
        JsonObject climate = obj("type", "minecraft:max",
                "left", spline(CL + "zone_temperature", new double[][]{{DESERT_IN, 0, 0}, {DESERT_FULL, 1, 0}}),
                "right", mul(spline(CL + "zone_temperature", new double[][]{{WARM_IN, 0, 0}, {WARM_FULL, 1, 0}}),
                        spline(CL + "biome_moisture", new double[][]{{M_DRY_FULL, 1, 0}, {M_DRY_IN, 0, 0}})));
        JsonObject land = spline(P + "continents", new double[][]{{C_IN, 0, 0}, {C_FULL, 1, 0}});
        JsonObject outsideValley = spline(obj("type", "minecraft:abs", "input", P + "ridges"), new double[][]{{W_IN, 0, 0}, {W_FULL, 1, 0}});
        JsonObject outsideWetland = spline(P + "erosion", new double[][]{{E_WET_FULL, 1, 0}, {E_WET_OUT, 0, 0}});
        JsonObject depth = spline(h, new double[][]{{H_DEEP, 0, 0}, {H_FLAT, 1, 0}});
        staged.put(path(L + "dry_weight"), cache(mul(climate, mul(land, mul(outsideValley, mul(outsideWetland, depth))))));
        staged.put(path(L + "dry_floor"), cache(obj("type", "minecraft:range_choice", "input", h, "min_inclusive", -1.0e6, "max_exclusive", TOP,
                "when_in_range", add(h, mul(L + "dry_weight", obj("type", "minecraft:max",
                        "left", mul(LOW_SLOPE, add(LOW_TOP, mul(-1, h))), "right", mul(SLOPE, add(TOP, mul(-1, h)))))),
                "when_out_of_range", h)));
        interpolated.addProperty("input", L + "dry_floor");
        staged.put(path(P + "coherent/height"), coherent);
        // Audit "build10" T01: the ecotone surface rule never read "continentality"; the current world
        // stops writing the dead setting (older presets keep theirs, the codec accepts and drops it).
        // The same rules read the forest humidity (realism audit A092-03/04).
        for (String rule : CURRENT_RULES) {
            String file = VanillaAssets.path("material_rule", rule);
            JsonElement copy = Objects.requireNonNull(staged.get(file), "Missing material rule " + rule).deepCopy();
            if (strip(copy) == 0) throw new IllegalStateException("No ecotone rule in " + rule);
            staged.put(file, copy);
        }
        output.putAll(staged);
    }
    /** Current-world material rules that carry the ecotone surface rule. */
    public static final List<String> CURRENT_RULES = List.of(P + "surface", P + "biome_surface", P + "under_biome_surface");
    private static int strip(JsonElement e) {
        int n = 0;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").isJsonPrimitive() && "freshgenerator:ecotone_surface".equals(o.get("type").getAsString())) {
                o.remove("continentality"); n++;
                // Realism audit 0.9.2 (A092-03/04): soil (podzol, coarse dirt, grass) reads the same humidity as
                // the trees on it - the local field with the biome-lookup correction.
                if (o.has("moisture") && o.get("moisture").isJsonPrimitive() && o.get("moisture").getAsString().equals(P + "vegetation"))
                    o.addProperty("moisture", CL + "forest_moisture");
            }
            for (var entry : o.entrySet()) n += strip(entry.getValue());
        } else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) n += strip(x);
        return n;
    }
    private static JsonObject spline(Object coordinate, double[][] points) {
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
