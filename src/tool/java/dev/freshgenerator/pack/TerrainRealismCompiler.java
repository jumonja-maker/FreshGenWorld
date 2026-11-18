package dev.freshgenerator.pack;

import com.google.gson.*;
import dev.freshgenerator.core.CoastalRelief;
import dev.freshgenerator.core.DensityExpr;
import java.util.*;

/** 0.8.2 realism layer over the current landscape. Earlier layers stay frozen.
 * <ul>
 * <li>Added relief cannot drown native dry land (inland "lakes" with land biomes).</li>
 * <li>Seabed detail no longer roughens dry lowland.</li>
 * <li>Regional climate borders gain a smooth mid-scale bend.</li>
 * </ul>
 * Caves, aquifers, water, biome tables, structures and settings are untouched.
 * The alpine rock-detail branch (MountainSurfaceDetailCompiler) is not part of the
 * active density since SurfaceOnlyCompiler; 0.8.2 edits to it were inert and removed.
 */
public final class TerrainRealismCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", C = L + "climate/";
    public static final double WARP_BLOCKS = 80;
    public static final int WARP_OCTAVE = -9;
    public static final Set<String> REPLACED = Set.of(path(L + "height_raw"), path(L + "seabed_detail"),
            path(C + "regional_temperature"), path(C + "regional_vegetation"));
    public static final Set<String> ADDED = Set.of(path(L + "height_unguarded"), path(L + "coastal_floor"), path(L + "guard_offset"),
            path(C + "warp_x"), path(C + "warp_z"),
            VanillaAssets.path("noise", C + "warp_x"), VanillaAssets.path("noise", C + "warp_z"));
    private static final Gson G = new Gson();
    private TerrainRealismCompiler() { }

    public static void apply(Map<String, JsonElement> out) {
        Objects.requireNonNull(out);
        Map<String, JsonElement> staged = new TreeMap<>(out);
        for (String p : REPLACED) Objects.requireNonNull(staged.get(p), "Missing realism input " + p);
        for (String p : ADDED) if (staged.containsKey(p)) throw new IllegalStateException("Realism layer already present: " + p);

        // Exact expected inputs: refuse to stack on an unknown landscape.
        var program = dev.freshgenerator.core.LandscapeTerrainProgram.compile(P);
        requireGraph(staged, L + "seabed_detail", program.nodes().get("seabed_detail"));
        requireGraph(staged, L + "height_raw", program.nodes().get("height_raw"));

        DensityExpr relief = DensityExpr.ref(L + "relief_noise"), nativeHeight = DensityExpr.ref(L + "native_height");
        staged.put(path(L + "seabed_detail"), graph(DensityExpr.mul(DensityExpr.scale(relief, 1.8), CoastalRelief.seabedMask(nativeHeight))));
        staged.put(path(L + "height_unguarded"), staged.get(path(L + "height_raw")).deepCopy());
        // Every heavy landscape node is read once: repeated references are re-evaluated.
        // The floor follows native height; coast_height costs 2.7x chunk time here.
        staged.put(path(L + "coastal_floor"), cache(spline(L + "native_height", CoastalRelief.FLOOR_POINTS)));
        staged.put(path(L + "guard_offset"), cache(object("type", "minecraft:sub", "left", L + "height_unguarded", "right", L + "coastal_floor")));
        staged.put(path(L + "height_raw"), cache(object("type", "minecraft:add", "left", L + "coastal_floor",
                "right", spline(L + "guard_offset", CoastalRelief.GUARD_POINTS))));

        // A diffeomorphic mid-scale bend: borders move, no new climate islands.
        for (String axis : List.of("x", "z")) {
            staged.put(VanillaAssets.path("noise", C + "warp_" + axis), object("base_octave", WARP_OCTAVE, "octave_count", 1, "base_amplitude", 1.0));
            staged.put(path(C + "warp_" + axis), object("type", "minecraft:cache", "input",
                    object("type", "minecraft:noise", "noise", C + "warp_" + axis, "xz_scale", 1.0, "y_scale", 0.0)));
        }
        for (String name : List.of("regional_temperature", "regional_vegetation")) {
            JsonObject noise = staged.get(path(C + name)).deepCopy().getAsJsonObject();
            if (!"minecraft:noise".equals(noise.get("type").getAsString()) || noise.get("y_scale").getAsDouble() != 0)
                throw new IllegalStateException("Unexpected regional climate noise: " + name);
            double scale = noise.get("xz_scale").getAsDouble();
            if (!(scale > 0)) throw new IllegalStateException("Invalid climate scale");
            for (String axis : List.of("x", "z")) {
                String field = "shift_" + axis;
                JsonElement previous = Objects.requireNonNull(noise.get(field), "Missing climate warp " + field);
                noise.add(field, object("type", "minecraft:add", "left", previous,
                        "right", object("type", "minecraft:mul", "left", WARP_BLOCKS * scale, "right", C + "warp_" + axis)));
            }
            staged.put(path(C + name), noise);
        }
        out.putAll(staged);
    }
    private static void requireGraph(Map<String, JsonElement> out, String id, DensityExpr expected) {
        if (!graph(expected).equals(out.get(path(id)))) throw new IllegalStateException("Unexpected landscape input " + id);
    }
    private static JsonObject cache(JsonElement input) { return object("type", "minecraft:cache", "input", input); }
    private static JsonObject spline(String coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(object("location", p[0], "value", p[1], "derivative", p[2]));
        return object("type", "minecraft:spline", "spline", object("coordinate", coordinate, "points", list));
    }
    private static JsonElement graph(DensityExpr e) { return object("type", "minecraft:cache", "input", G.toJsonTree(e.json())); }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonObject object(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) o.add((String) pairs[i], G.toJsonTree(pairs[i + 1]));
        return o;
    }
}
