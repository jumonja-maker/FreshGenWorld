package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Natural snow and forest borders (user request 26.11: "transitions of winter biomes look drawn").
 * <p>Seen on seed 31337 (X0 Z-1250): the snowy_taiga / taiga border ran as one diagonal for 300-400 blocks and the snow
 * cover ended on it as a ruled line. The regional climate changes by about 2e-4 per block there; its warp and dither
 * (512-8192 block wavelengths) bend a border only on the kilometre scale, so at the scale a player sees the isotherm is
 * straight.
 * <p>Near the two thresholds that switch snow and conifers - the frozen column (-0.45) and the cold column (-0.15) - the
 * surface temperature gets (only inside a narrow band around each threshold, so no other border moves):
 * <ul>
 * <li>a fine three-octave noise (32-128 blocks): the border breaks into lobes, bays and outliers of either side, tens of
 * blocks deep - an ecotone instead of a line;</li>
 * <li>a relief term: ground above the regional surface is colder, ground below it warmer (up to +-12 blocks of local
 * relief), so snow and taiga take the hills first and the open hollows last, as a patchy snow line does.</li>
 * </ul>
 * The surface temperature is the one field every biome-table rule reads (the frozen-column humidity cap, the
 * highland rules), so the rules move with the border. */
public final class EcotoneBorderCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", CL = L + "climate/";
    /** Thresholds, band half-widths (temperature) and dither amplitudes (temperature per unit of noise). */
    public static final double FROZEN = -0.45, FROZEN_BAND = 0.06, FROZEN_DITHER = 0.024;
    public static final double COLD = -0.15, COLD_BAND = 0.04, COLD_DITHER = 0.014;
    /** Relief term: temperature per block of local relief, relief clamp (blocks). */
    public static final double RELIEF_K = 0.0022, RELIEF_MAX = 12;
    public static final int NOISE_OCTAVE = -7;
    private static final Gson G = new Gson();
    private EcotoneBorderCompiler() { }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        String surface = path(CL + "surface_temperature");
        JsonObject old = Objects.requireNonNull(staged.get(surface), "Missing surface temperature").getAsJsonObject();
        if (staged.containsKey(path(CL + "surface_temperature_base"))) throw new IllegalStateException("Ecotone borders already present");
        staged.put(path(CL + "surface_temperature_base"), old.deepCopy());
        String t = CL + "surface_temperature_base";
        if (staged.putIfAbsent(VanillaAssets.path("noise", CL + "ecotone_noise"), obj("base_octave", NOISE_OCTAVE, "octave_count", 3, "base_amplitude", 1.0)) != null)
            throw new IllegalStateException("Ecotone noise exists");
        staged.put(path(CL + "ecotone_noise"), cache(obj("type", "minecraft:noise", "noise", CL + "ecotone_noise", "xz_scale", 1.0, "y_scale", 0.0)));
        staged.put(path(CL + "local_relief"), cache(obj("type", "minecraft:clamp", "min", -RELIEF_MAX, "max", RELIEF_MAX,
                "input", obj("type", "minecraft:sub", "left", P + "coherent/height", "right", L + "native_height"))));
        JsonObject frozen = shift(t, FROZEN, FROZEN_BAND, FROZEN_DITHER);
        JsonObject cold = shift(t, COLD, COLD_BAND, COLD_DITHER);
        staged.put(surface, cache(range(t, FROZEN - FROZEN_BAND, FROZEN + FROZEN_BAND, frozen,
                range(t, COLD - COLD_BAND, COLD + COLD_BAND, cold, t))));
        output.putAll(staged);
    }

    /** T + bump(T) x (dither x noise - k x relief), bump = 1 at the threshold, 0 at the band edges (zero slopes). */
    private static JsonObject shift(String t, double threshold, double band, double dither) {
        JsonArray points = new JsonArray();
        points.add(obj("location", threshold - band, "value", 0.0, "derivative", 0.0));
        points.add(obj("location", threshold, "value", 1.0, "derivative", 0.0));
        points.add(obj("location", threshold + band, "value", 0.0, "derivative", 0.0));
        JsonObject bump = obj("type", "minecraft:spline", "spline", obj("coordinate", t, "points", points));
        JsonObject delta = obj("type", "minecraft:add",
                "left", obj("type", "minecraft:mul", "left", dither, "right", CL + "ecotone_noise"),
                "right", obj("type", "minecraft:mul", "left", -RELIEF_K, "right", CL + "local_relief"));
        return obj("type", "minecraft:clamp", "min", -1.0, "max", 1.0,
                "input", obj("type", "minecraft:add", "left", t, "right", obj("type", "minecraft:mul", "left", bump, "right", delta)));
    }
    private static JsonObject range(String input, double min, double max, Object in, Object out) {
        return obj("type", "minecraft:range_choice", "input", input, "min_inclusive", min, "max_exclusive", max, "when_in_range", in, "when_out_of_range", out);
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement e(Object o) { return o instanceof JsonElement j ? j.deepCopy() : G.toJsonTree(o); }
    private static JsonObject obj(Object... p) { var o = new JsonObject(); for (int i = 0; i < p.length; i += 2) o.add((String) p[i], e(p[i + 1])); return o; }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
}
