package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** 0.9.0 ecological niches on the biome-lookup axes only (terrain untouched).
 * <ul>
 * <li>Dry belt before the desert. The native table switches to desert at T>=0.55 for
 *     every humidity, so a jungle could border a desert at equal moisture. Just below
 *     that threshold (T 0.46..0.55, roughly 200 blocks) the lookup humidity is capped to the savanna band:
 *     rainforest gives way to dry forest and savanna, then desert. T>=0.55 itself is
 *     untouched, so deserts, all badlands variants and warm oceans keep their native
 *     extent (an earlier temperature cap removed wooded badlands and most warm ocean).</li>
 * <li>Swamp and mangrove need ground near the water surface. Their native trigger is
 *     erosion >= 0.55 regardless of elevation; above H72 lookup erosion is capped.</li>
 * </ul>
 * Native biome entries, features and the physical climate fields remain unchanged. */
public final class ClimateNicheCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, C = P + "landscape/climate/";
    public static final String SETTINGS = SurfaceOnlyCompiler.SETTINGS;
    /** Dry belt: lookup humidity cap (savanna band) between these lookup temperatures. The
     * cap is released only at T >= 0.55, where the native table picks desert for any
     * humidity; releasing it earlier let a sub-block jungle sliver touch the desert. */
    public static final double BELT_START = .46, BELT_FULL = .49, BELT_END = .5500, DESERT_T = .5510, BELT_MOISTURE = -.15;
    /** Surface height at which swamp erosion is still native / fully capped. */
    public static final double WETLAND_H = 66, UPLAND_H = 72, CAPPED_E = .54;
    public static final Set<String> REPLACED = Set.of(path(P + "regions/biome_vegetation"), SETTINGS);
    public static final Set<String> ADDED = Set.of(path(C + "dry_belt"), path(C + "lookup_moisture"), path(C + "wetland_cap"), path(C + "biome_erosion"));
    private static final Gson G = new Gson();
    private ClimateNicheCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        for (String p : ADDED) if (staged.containsKey(p)) throw new IllegalStateException("Climate niche layer already present");
        JsonObject vegetation = Objects.requireNonNull(staged.get(path(P + "regions/biome_vegetation")), "Missing lookup vegetation").deepCopy().getAsJsonObject();
        if (!"minecraft:lerp".equals(vegetation.get("type").getAsString()) || !vegetation.get("first").getAsString().equals(C + "biome_moisture"))
            throw new IllegalStateException("Unexpected lookup vegetation");
        staged.put(path(C + "dry_belt"), cache(spline(C + "surface_temperature", new double[][]{
                {BELT_START, 1, 0}, {BELT_FULL, BELT_MOISTURE, 0}, {BELT_END, BELT_MOISTURE, 0}, {DESERT_T, 1, 0}})));
        staged.put(path(C + "lookup_moisture"), cache(obj("type", "minecraft:min", "left", C + "biome_moisture", "right", C + "dry_belt")));
        vegetation.addProperty("first", C + "lookup_moisture");
        staged.put(path(P + "regions/biome_vegetation"), vegetation);
        staged.put(path(C + "wetland_cap"), cache(spline(P + "coherent/height", new double[][]{{WETLAND_H, 1, 0}, {UPLAND_H, CAPPED_E, 0}})));
        staged.put(path(C + "biome_erosion"), cache(obj("type", "minecraft:min", "left", P + "erosion", "right", C + "wetland_cap")));
        JsonObject settings = staged.get(SETTINGS).deepCopy().getAsJsonObject();
        JsonObject router = settings.getAsJsonObject("noise_router");
        if (!router.get("erosion").getAsString().equals(P + "erosion")) throw new IllegalStateException("Unexpected erosion routing");
        router.addProperty("erosion", C + "biome_erosion");
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
    private static JsonObject obj(Object... p) {
        var o = new JsonObject();
        for (int i = 0; i < p.length; i += 2) o.add((String) p[i], p[i + 1] instanceof JsonElement j ? j : G.toJsonTree(p[i + 1]));
        return o;
    }
}
