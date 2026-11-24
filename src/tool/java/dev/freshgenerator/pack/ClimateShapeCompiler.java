package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Climate shape of the current world (user request 23.11, audits 22.11 B-2/B-4).
 * <ul>
 * <li><b>Underground follows the surface.</b> Biome temperature and moisture below ground are the surface climate
 * of the column (no fade to the unstretched native climate 24-64 blocks down: deserts and jungles under forests,
 * warm-sea water in caves under a temperate sea). Erosion, weirdness and continentalness keep their depth routing
 * (the aquifer's dry protection reads them).</li>
 * <li><b>Natural climate borders.</b> The stretched regional temperature and moisture get a large two-octave domain
 * warp in three layers (up to ~1500 blocks at 8 km, ~400 at 2 km, ~130 at 1 km) on top of the 0.8.2 bend: borders
 * meander instead of running as km-long straight lines, circles and ellipses. Zone sizes stay the same.</li>
 * <li><b>Island climate.</b> Zone temperature and biome moisture become freshgenerator:island_climate over their
 * former definitions: a small island takes the mean climate of its land (core/IslandClimate).</li>
 * </ul> */
public final class ClimateShapeCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, L = P + "landscape/", C = L + "climate/";
    /** Domain warp layers: name, noise octave (wavelength 2^-octave blocks), blocks per unit of noise. The noise
     * reaches about +-0.6, so the large layer moves borders up to ~1500 blocks over 8 km (circles and ellipses lose
     * their shape), the mid layer up to ~330 blocks over 2 km and the fine one ~110 over 1 km (meanders). Each stays below a displacement gradient
     * of 1: the warp folds nothing, so it creates no new climate islands. */
    static final Object[][] WARPS = {{"warp_large", -13, 2200.0}, {"warp_mid", -11, 550.0}, {"warp_fine", -10, 280.0}, {"warp_detail", -9, 110.0}};
    /** The summed warp (blocks) of an axis is read from a lattice (freshgenerator:lattice): the shortest layer
     * wavelength is 512 blocks, so a 32-block lattice moves borders by about a block, while evaluating the eight
     * layer noises in every column that reads the climate cost +5-10% of generation. */
    static final int WARP_LATTICE = 32;
    /** Ecotone dither of the zone temperature and moisture: name, octave, amplitude per unit of noise (+-0.6). A value
     * perturbation moves a border by amplitude / gradient - little where the climate changes fast, a lot where the
     * field runs almost flat along a threshold. That is exactly where the warp could not break the straight borders
     * and ovals left by audit 23.11 K-1 (taiga/snowy_taiga 1.2 km straight at seed 8675309 X6100: T flattens out at
     * -0.477 just under the -0.45 line); there the border becomes an irregular ecotone of lobes and outliers. */
    static final Object[][] DITHERS = {{"dither_coarse", -10, 0.035, 0.045}, {"dither_fine", -9, 0.02, 0.025}, {"dither_detail", -8, 0.014, 0.018}};
    public static final Set<String> ADDED;
    static {
        Set<String> added = new TreeSet<>(Set.of(path(C + "zone_temperature_local"), path(C + "biome_moisture_local")));
        for (Object[] warp : WARPS) for (String axis : List.of("x", "z")) {
            added.add(path(C + warp[0] + "_" + axis)); added.add(VanillaAssets.path("noise", C + warp[0] + "_" + axis));
        }
        for (String axis : List.of("x", "z")) added.add(path(C + "warp_field_" + axis));
        for (Object[] d : DITHERS) for (String f : List.of("t", "m")) added.add(VanillaAssets.path("noise", C + d[0] + "_" + f));
        added.add(path(C + "dither_temperature")); added.add(path(C + "dither_moisture"));
        ADDED = Collections.unmodifiableSet(added);
    }
    public static final Set<String> REPLACED = Set.of(path(P + "regions/biome_temperature"), path(P + "regions/biome_vegetation"),
            path(C + "regional_temperature"), path(C + "regional_vegetation"), path(C + "zone_temperature"), path(C + "biome_moisture"),
            path(C + "altitude_cooling"), path(C + "surface_continents"));
    /** Snow line of hot mountains (audit 23.11 K-3): the altitude cooling weakens in the hot zone, by HOT_RELIEF at
     * zone temperature HOT_T1 and above, ramped in from HOT_T0. A summit of the hot zone then stays above the frost
     * column (rock of stony_peaks, montane woodland below) up to the world ceiling instead of turning into snowy
     * jagged_peaks at Y237; temperate and warm mountains keep their belts. In nature the snow line climbs from
     * ~3 km in temperate ranges to 5-6 km in the subtropical dry belt (Ohmura et al. 1992) - twice the temperate
     * height, more than the world has; the hot zone therefore has no snow at all on its peaks. HydroClimate.cooled
     * mirrors this curve. */
    public static final double HOT_T0 = 0.45, HOT_T1 = 0.60, HOT_RELIEF = 0.25;
    private static final Gson G = new Gson();
    private ClimateShapeCompiler() { }

    public static void apply(Map<String, JsonElement> out) {
        Objects.requireNonNull(out);
        Map<String, JsonElement> staged = new TreeMap<>(out);
        for (String p : REPLACED) Objects.requireNonNull(staged.get(p), "Missing climate input " + p);
        for (String p : ADDED) if (staged.containsKey(p)) throw new IllegalStateException("Climate shape already present: " + p);

        // 1. Underground biome climate = the surface climate of the column.
        replaceLerp(staged, P + "regions/biome_temperature", C + "surface_temperature");
        replaceLerp(staged, P + "regions/biome_vegetation", C + "frozen_moisture");

        // 2. Domain warp of the regional climate: a large and a mid layer over the 0.8.2 bend.
        for (Object[] warp : WARPS) for (String axis : List.of("x", "z")) {
            String id = C + warp[0] + "_" + axis;
            staged.put(VanillaAssets.path("noise", id), object("base_octave", warp[1], "octave_count", 1, "base_amplitude", 1.0));
            staged.put(path(id), object("type", "minecraft:cache", "input", object("type", "minecraft:noise", "noise", id, "xz_scale", 1.0, "y_scale", 0.0)));
        }
        for (String axis : List.of("x", "z")) {
            JsonElement sum = null;
            for (Object[] warp : WARPS) {
                JsonElement layer = object("type", "minecraft:mul", "left", (double) warp[2], "right", C + warp[0] + "_" + axis);
                sum = sum == null ? layer : object("type", "minecraft:add", "left", sum, "right", layer);
            }
            staged.put(path(C + "warp_field_" + axis), object("type", "minecraft:cache", "input",
                    object("type", "freshgenerator:lattice", "spacing", WARP_LATTICE, "input", sum)));
        }
        for (String name : List.of("regional_temperature", "regional_vegetation")) {
            JsonObject noise = staged.get(path(C + name)).deepCopy().getAsJsonObject();
            if (!"minecraft:noise".equals(noise.get("type").getAsString()) || noise.get("y_scale").getAsDouble() != 0)
                throw new IllegalStateException("Unexpected regional climate noise: " + name);
            double scale = noise.get("xz_scale").getAsDouble();
            for (String axis : List.of("x", "z")) {
                String field = "shift_" + axis;
                JsonElement shift = Objects.requireNonNull(noise.get(field), "Missing climate shift " + field);
                shift = object("type", "minecraft:add", "left", shift,
                        "right", object("type", "minecraft:mul", "left", scale, "right", C + "warp_field_" + axis));
                noise.add(field, shift);
            }
            staged.put(path(C + name), noise);
        }

        // 3. Snow line of hot mountains: the cooling scaled down by the zone temperature (island climate included).
        JsonObject cooling = staged.get(path(C + "altitude_cooling")).getAsJsonObject();
        JsonElement curve = "minecraft:cache".equals(cooling.get("type").getAsString()) ? cooling.get("input") : cooling;
        JsonArray relief = new JsonArray();
        relief.add(object("location", HOT_T0, "value", 1.0, "derivative", 0.0));
        relief.add(object("location", HOT_T1, "value", 1.0 - HOT_RELIEF, "derivative", 0.0));
        staged.put(path(C + "altitude_cooling"), object("type", "minecraft:cache", "input", object("type", "minecraft:mul",
                "left", curve, "right", object("type", "minecraft:spline", "spline", object("coordinate", C + "zone_temperature", "points", relief)))));

        // 4. Ecotone dither, then the island climate over zone temperature and biome moisture (every consumer keeps
        // its reference).
        for (int f = 0; f < 2; f++) {
            String axis = f == 0 ? "t" : "m";
            JsonElement sum = null;
            for (Object[] d : DITHERS) {
                String id = C + d[0] + "_" + axis;
                staged.put(VanillaAssets.path("noise", id), object("base_octave", d[1], "octave_count", 1, "base_amplitude", 1.0));
                JsonElement layer = object("type", "minecraft:mul", "left", (double) d[2 + f],
                        "right", object("type", "minecraft:noise", "noise", id, "xz_scale", 1.0, "y_scale", 0.0));
                sum = sum == null ? layer : object("type", "minecraft:add", "left", sum, "right", layer);
            }
            staged.put(path(C + (f == 0 ? "dither_temperature" : "dither_moisture")), object("type", "minecraft:cache", "input",
                    object("type", "freshgenerator:lattice", "spacing", WARP_LATTICE, "input", sum)));
        }
        staged.put(path(C + "zone_temperature_local"), object("type", "minecraft:add",
                "left", staged.get(path(C + "zone_temperature")).deepCopy(), "right", C + "dither_temperature"));
        staged.put(path(C + "biome_moisture_local"), object("type", "minecraft:add",
                "left", staged.get(path(C + "biome_moisture")).deepCopy(), "right", C + "dither_moisture"));
        for (String[] o : new String[][]{{"zone_temperature", "temperature"}, {"biome_moisture", "moisture"}})
            staged.put(path(C + o[0]), object("type", "minecraft:cache", "input", object("type", "freshgenerator:island_climate",
                    "height", L + "native_height", "temperature", C + "zone_temperature_local", "moisture", C + "biome_moisture_local", "output", o[1])));

        // 5. Coastal biome continentalness for ground under the sea only (audit 24.11 B-1). The historical rule
        // (LandscapeBiomeCompiler, unchanged) lowered C toward the coast band up to Y75, fading from Y61: dry land at the
        // waterline counted as half submerged, and a flat at sea level 400 blocks across, 300-600 blocks from the open
        // sea, became beach (seed 42 X3035-3370 Z2657-2894: ground 62.6-63.4, C -0.07 lowered to -0.17). The fade now
        // ends at Y63, the sea surface: two blocks of water still take the ocean climate, the shore keeps the physical
        // continentalness (beaches where it lies in the coast band).
        JsonElement coastal = object("type", "minecraft:sub", "left", 1.0, "right", smooth(-0.11, 0.03, P + "continents"));
        JsonElement upper = object("type", "minecraft:add", "left", -0.20,
                "right", object("type", "minecraft:mul", "left", 1.30, "right", smooth(SUBMERGED_LOW, SUBMERGED_HIGH, P + "coherent/height")));
        staged.put(path(C + "surface_continents"), object("type", "minecraft:cache", "input", object("type", "minecraft:sub",
                "left", P + "continents", "right", object("type", "minecraft:mul", "left", coastal, "right", object("type", "minecraft:max",
                        "left", object("type", "minecraft:sub", "left", P + "continents", "right", upper), "right", 0.0)))));
        out.putAll(staged);
    }
    /** Height band (Y) over which the coastal reconciliation of the biome continentalness fades out. */
    public static final double SUBMERGED_LOW = 61, SUBMERGED_HIGH = 63;
    private static JsonElement smooth(double lo, double hi, String input) {
        return G.toJsonTree(dev.freshgenerator.core.NaturalTerrainProgram.smootherstep(lo, hi, dev.freshgenerator.core.DensityExpr.ref(input)).json());
    }

    /** A depth lerp (surface first, original second) becomes its surface branch. */
    private static void replaceLerp(Map<String, JsonElement> staged, String id, String surface) {
        JsonObject lerp = staged.get(path(id)).getAsJsonObject();
        if (!"minecraft:lerp".equals(lerp.get("type").getAsString()) || !surface.equals(lerp.get("first").getAsString()))
            throw new IllegalStateException("Unexpected depth lerp " + id);
        staged.put(path(id), object("type", "minecraft:cache", "input", surface));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonObject object(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) o.add((String) pairs[i], G.toJsonTree(pairs[i + 1]));
        return o;
    }
}
