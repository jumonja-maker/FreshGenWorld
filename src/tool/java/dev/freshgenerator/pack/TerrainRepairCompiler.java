package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.TerrainRepair;
import java.util.*;

/** 0.9.1 audit repairs on the current world only (see {@link TerrainRepair}).
 * <ul>
 * <li>landscape/giant: broad giant-mountain flanks (A02).</li>
 * <li>landscape/coast_height: the original profile moves to coast_profile and is
 *     limited by the new coast_cap ceiling (A01).</li>
 * </ul>
 * A11 (biome depth reversal under uplifted massifs, inside rock) is deferred: every
 * tested monotone form of ecotone/biome_depth cost 7-13% generation time in 26.3.
 * Historical layers and every other resource stay byte-identical. */
public final class TerrainRepairCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/";
    public static final Set<String> REPLACED = Set.of(path(L + "giant"), path(L + "coast_height"));
    public static final Set<String> ADDED = Set.of(path(L + "coast_profile"), path(L + "coast_cap"));
    private static final Gson G = new Gson();
    private TerrainRepairCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        for (String p : ADDED) if (staged.containsKey(p)) throw new IllegalStateException("Terrain repair layer already present");
        for (String p : REPLACED) Objects.requireNonNull(staged.get(p), "Missing repair input " + p);

        // Skip the giant expression where its province noise is below the province
        // threshold: there the uplift is exactly zero (nearly the whole map).
        staged.put(path(L + "giant"), cache(obj("type", "minecraft:range_choice", "input", L + "giant_noise",
                "min_inclusive", -1.0e6, "max_exclusive", TerrainRepair.GIANT_NOISE_LOW,
                "when_in_range", 0.0, "when_out_of_range", json(TerrainRepair.giant(P)))));
        JsonObject coast = staged.get(path(L + "coast_height")).getAsJsonObject();
        if (!"minecraft:cache".equals(coast.get("type").getAsString())) throw new IllegalStateException("Unexpected coastal profile");
        staged.put(path(L + "coast_profile"), coast.deepCopy());
        // Over the open ocean shelf the ceiling is the constant base + 1000 (inactive).
        staged.put(path(L + "coast_cap"), cache(obj("type", "minecraft:range_choice", "input", P + "continents",
                "min_inclusive", -1.0e6, "max_exclusive", TerrainRepair.COAST_CAP_FADE_LOW,
                "when_in_range", TerrainRepair.COAST_CAP_BASE + 1000, "when_out_of_range", json(TerrainRepair.coastCap(P)))));
        staged.put(path(L + "coast_height"), cache(json(TerrainRepair.coastHeight(P))));

        output.putAll(staged);
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement json(dev.freshgenerator.core.DensityExpr e) { return G.toJsonTree(e.json()); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
    private static JsonObject obj(Object... p) {
        var o = new JsonObject();
        for (int i = 0; i < p.length; i += 2) o.add((String) p[i], p[i + 1] instanceof JsonElement j ? j : G.toJsonTree(p[i + 1]));
        return o;
    }
}
