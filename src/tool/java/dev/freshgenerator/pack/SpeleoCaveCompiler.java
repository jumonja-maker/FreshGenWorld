package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;

/** Caves of the deeper world (user request 26.11: "the caves start very low; give the generator tools for unusual caves
 * that still follow logic; no level that tells the player what comes next; a huge room with a hundred openings").
 * <p>Built over the 0.9.0 karst (its entrances, upper level, 14-block roof rule and surface protection are kept):
 * <ul>
 * <li><b>Former water tables that come and go.</b> The karst main and deep levels exist only where their own regional
 * presence field says so; two new levels (lower ~Y-70, abyssal ~Y-94) follow the same joint plan with their own warps
 * and tilt over kilometres (passages climb and descend). Locally two to five levels exist, at different heights.</li>
 * <li><b>Spongework</b> (hypogenic caves of rising water, Klimchouk 2007): zones of irregular interconnected voids.</li>
 * <li><b>Vadose canyons</b> cutting down from one level to the next along the joints, and pits between the new levels.</li>
 * <li><b>Great breakdown chambers</b> (freshgenerator:cave_rooms): domes tens of blocks across over rubble floors, their
 * walls honeycombed by holes that run into the rock; the levels cut through them.</li>
 * <li><b>Mazes</b> in parts of the abyssal level (network mazes of dense joints).</li>
 * </ul>
 * Everything stays below the karst roof rule (14 blocks of rock under the ground) and above the new floor. */
public final class SpeleoCaveCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, K = P + "karst/", S = K + "speleo/", Y = "minecraft:y";
    /** New cave floor (the deeper world reaches Y-112). */
    public static final double FLOOR_Y = -104;
    public static final double LOWER_C = -70, LOWER_A = 9, LOWER_HALF = 6, LOWER_W = .036;
    public static final double ABYSS_C = -93, ABYSS_A = 5, ABYSS_HALF = 7.5, ABYSS_W = .040;
    private static final Gson G = new Gson();
    private SpeleoCaveCompiler() { }

    /** Debug label of the chamber density (CaveRoomDensity.model finds the chamber model through it). */
    public static final String ROOM_LABEL = "FG cave rooms";

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        for (String n : List.of("network", "floor_guard", "level_main_tube", "level_deep_tube", "rough", "plan_a_main", "level_deep", "level_main"))
            Objects.requireNonNull(staged.get(path(K + n)), "Karst must come first: " + n);
        if (staged.containsKey(path(S + "network"))) throw new IllegalStateException("Speleo layer already present");

        for (String n : List.of("presence_main", "presence_deep", "presence_lower", "presence_abyss")) noise(staged, n, -10, 1);
        noise(staged, "level_lower", -9, 1); noise(staged, "level_abyss", -9, 1); noise(staged, "tilt", -11, 1);
        for (String n : List.of("warp_lx", "warp_lz", "warp_ax", "warp_az")) noise(staged, n, -7, 1);
        noise(staged, "sponge", -5, 2); noise(staged, "sponge_zone", -9, 1); noise(staged, "canyon_zone", -9, 1);
        noise(staged, "maze_zone", -9, 1); noise(staged, "abyss_maze", -6, 2); noise(staged, "pit", -6, 1); noise(staged, "room_holes", -4, 2);

        String rough = K + "rough";
        // 1. Regional presence of the karst main and deep levels (the upper level carries the entrances and stays).
        // Each part below is evaluated only where its zone is open (range_choice): outside, rock (4) costs nothing.
        for (String[] l : new String[][]{{"level_main_tube", "presence_main"}, {"level_deep_tube", "presence_deep"}}) {
            JsonElement tube = staged.get(path(K + l[0])).deepCopy();
            String p = node(staged, l[1], cache(presence(l[1])));
            staged.put(path(K + l[0]), range(p, 1.0e-4, 2.0, add(tube, mul(3.0, sub(1.0, p))), 4.0));
        }
        // 2. Two new levels on the same joint plan, with their own warps, height wander and regional tilt.
        String tilt = node(staged, "tilt", cache(noiseRef("tilt", 0)));
        String wlx = node(staged, "warp_lx", cache(noiseRef("warp_lx", 0))), wlz = node(staged, "warp_lz", cache(noiseRef("warp_lz", 0)));
        String wax = node(staged, "warp_ax", cache(noiseRef("warp_ax", 0))), waz = node(staged, "warp_az", cache(noiseRef("warp_az", 0)));
        String gaL = node(staged, "plan_a_lower", cache(warped(K + "plan_a", wlx, wlz, 14))), gbL = node(staged, "plan_b_lower", cache(warped(K + "plan_b", wlx, wlz, 14)));
        String gaA = node(staged, "plan_a_abyss", cache(warped(K + "plan_a", wax, waz, 16))), gbA = node(staged, "plan_b_abyss", cache(warped(K + "plan_b", wax, waz, 16)));
        String lL = node(staged, "level_lower", cache(add(LOWER_C, add(mul(LOWER_A, noiseRef("level_lower", 0)), mul(14, tilt)))));
        String lA = node(staged, "level_abyss", cache(add(ABYSS_C, add(mul(ABYSS_A, noiseRef("level_abyss", 0)), mul(6, tilt)))));
        String swell = K + "swell", hall = K + "hall";
        String pL = node(staged, "presence_lower", cache(presence("presence_lower"))), pA = node(staged, "presence_abyss", cache(presence("presence_abyss")));
        String tL = node(staged, "lower_tube", range(pL, 1.0e-4, 2.0, add(tube(gaL, gbL, lL, swell, hall, rough, LOWER_W, LOWER_HALF, .3, 1.8), mul(3.0, sub(1.0, pL))), 4.0));
        String tA = node(staged, "abyss_tube", range(pA, 1.0e-4, 2.0, add(tube(gaA, gbA, lA, swell, hall, rough, ABYSS_W, ABYSS_HALF, .35, 1.4), mul(3.0, sub(1.0, pA))), 4.0));
        // 3. Network maze patches in the abyssal level.
        String mazeMask = node(staged, "maze_mask", cache(spline(noiseRef("maze_zone", 0), new double[][]{{.30, 0, 0}, {.45, 1, 0}})));
        String maze = node(staged, "abyss_maze_tube", range(mazeMask, 1.0e-4, 2.0, add(add(square(mul(warped(S + "abyss_maze", wax, waz, 10), 1 / .045)), square(mul(sub(Y, lA), 1 / 3.5))),
                add(sub(rough, 1), mul(3.0, sub(1.0, mazeMask)))), 4.0));
        // 4. Vadose canyons from the karst main level down to the lower level, along the lower joint lines.
        String canyonMask = node(staged, "canyon_mask", cache(spline(noiseRef("canyon_zone", 0), new double[][]{{.20, 0, 0}, {.38, 1, 0}})));
        String canyon = node(staged, "canyon", range(canyonMask, 1.0e-4, 2.0, add(max(sub(mul(minAbs(gaL, gbL), 1 / (LOWER_W * .35)), 1),
                mul(.25, max(sub(Y, sub(K + "level_main", 2)), sub(sub(lL, 1), Y)))), mul(3.0, sub(1.0, canyonMask))), 4.0));
        // 5. Pits from the lower level down to the abyssal one (bell-shaped, on lower passages).
        String bandL = node(staged, "lower_band", cache(sub(mul(minAbs(gaL, gbL), 1 / (LOWER_W * 1.2)), 1)));
        String pit = node(staged, "lower_pit", max(sub(max(mul(6, sub(.78, noiseRef("pit", 0))), bandL),
                mul(.6, clamp(mul(sub(lL, Y), 1 / 25.0), 0, 1))), mul(.25, max(sub(Y, add(lL, 1)), sub(sub(lA, 3), Y)))));
        // 6. Spongework zones (irregular interconnected voids) between Y-100 and Y-20.
        String spongeMask = node(staged, "sponge_mask", cache(spline(noiseRef("sponge_zone", 0), new double[][]{{.18, 0, 0}, {.36, 1, 0}})));
        String sponge = node(staged, "sponge", range(spongeMask, 1.0e-4, 2.0, add(mul(6, sub(.50, noise3("sponge", 1.3))), mul(4.0, sub(1.0, spongeMask))), 4.0));
        // 7. Great breakdown chambers and their honeycombed walls.
        String room = node(staged, "room", cache(obj("type", "freshgenerator:cave_rooms", "output", "room")));
        String shell = node(staged, "room_shell", cache(obj("type", "freshgenerator:cave_rooms", "output", "shell")));
        String holes = node(staged, "room_holes", range(shell, 1.0e-4, 2.0,
                add(mul(6, sub(.34, noise3("room_holes", 1.0))), mul(4.0, sub(1.0, shell))), 4.0));

        // Height bands: each part is evaluated only where it can open.
        JsonObject deep = range(Y, FLOOR_Y - 8, LOWER_C + LOWER_A + 14 + LOWER_HALF * 2 + 6,
                min(min(tL, tA), min(maze, pit)), 4.0);
        JsonObject canyons = range(Y, LOWER_C - LOWER_A - 14 - 4, -8.0, canyon, 4.0);
        JsonObject sponges = range(Y, -104.0, -18.0, sponge, 4.0);
        JsonObject rooms = range(Y, -116.0, 44.0, min(room, holes), 4.0);
        String network = node(staged, "network", min(min(deep, canyons), min(sponges, rooms)));

        JsonElement oldNetwork = staged.get(path(K + "network")).deepCopy();
        staged.put(path(K + "network"), min(oldNetwork, network));
        staged.put(path(K + "floor_guard"), mul(.2, sub(FLOOR_Y, Y)));
        // The chamber model, for the aquifer of the current world (HydroAquifer keeps the domes dry).
        JsonObject settings = staged.get(SurfaceOnlyCompiler.SETTINGS).deepCopy().getAsJsonObject();
        JsonObject debug = new JsonObject();
        debug.addProperty("label", ROOM_LABEL);
        debug.addProperty("function", room);
        settings.getAsJsonArray("debug_functions").add(debug);
        staged.put(SurfaceOnlyCompiler.SETTINGS, settings);
        output.putAll(staged);
    }

    private static JsonElement presence(String noise) {
        return spline(noiseRef(noise, 0), new double[][]{{-.30, 0, 0}, {-.05, 1, 0}});
    }
    private static JsonObject tube(String a, String b, String level, String swell, String hall, String rough, double w, double half, double dome, double floor) {
        JsonElement inv = mul(swell, 1 / w);
        JsonElement plan = min(square(mul(a, inv)), square(mul(b, inv)));
        JsonElement up = mul(sub(Y, level), mul(mul(swell, 1 / half), sub(1.0, mul(dome, hall))));
        JsonElement down = mul(sub(level, Y), mul(swell, floor / half));
        return add(add(plan, square(max(up, down))), sub(rough, 1));
    }
    private static void noise(Map<String, JsonElement> out, String name, int octave, int count) {
        if (out.putIfAbsent(VanillaAssets.path("noise", S + name), obj("base_octave", octave, "octave_count", count, "base_amplitude", 1.0)) != null)
            throw new IllegalStateException("Speleo noise exists: " + name);
    }
    private static String node(Map<String, JsonElement> out, String name, JsonElement value) {
        if (out.putIfAbsent(path(S + name), value) != null) throw new IllegalStateException("Speleo node exists: " + name);
        return S + name;
    }
    private static JsonObject noiseRef(String name, double yScale) { return obj("type", "minecraft:noise", "noise", S + name, "xz_scale", 1.0, "y_scale", yScale); }
    private static JsonObject noise3(String name, double yScale) { return noiseRef(name, yScale); }
    private static JsonObject warped(String noiseId, String wx, String wz, double blocks) {
        var n = obj("type", "minecraft:noise", "noise", noiseId, "xz_scale", 1.0, "y_scale", 0.0);
        n.add("shift_x", mul(blocks, wx)); n.add("shift_z", mul(blocks, wz)); return n;
    }
    private static JsonObject range(Object input, double min, double max, Object in, Object out) {
        return obj("type", "minecraft:range_choice", "input", input, "min_inclusive", min, "max_exclusive", max, "when_in_range", in, "when_out_of_range", out);
    }
    private static JsonObject spline(Object coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", e(coordinate), "points", list));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement minAbs(String a, String b) { return min(abs(a), abs(b)); }
    private static JsonElement e(Object o) { return o instanceof JsonElement j ? j.deepCopy() : G.toJsonTree(o); }
    private static JsonObject obj(Object... p) { var o = new JsonObject(); for (int i = 0; i < p.length; i += 2) o.add((String) p[i], e(p[i + 1])); return o; }
    private static JsonObject bin(String op, Object a, Object b) { return obj("type", "minecraft:" + op, "left", a, "right", b); }
    private static JsonObject add(Object a, Object b) { return bin("add", a, b); }
    private static JsonObject sub(Object a, Object b) { return bin("sub", a, b); }
    private static JsonObject mul(Object a, Object b) { return bin("mul", a, b); }
    private static JsonObject min(Object a, Object b) { return bin("min", a, b); }
    private static JsonObject max(Object a, Object b) { return bin("max", a, b); }
    private static JsonObject abs(Object a) { return obj("type", "minecraft:abs", "input", a); }
    private static JsonObject square(Object a) { return obj("type", "minecraft:square", "input", a); }
    private static JsonObject cache(Object a) { return obj("type", "minecraft:cache", "input", a); }
    private static JsonObject clamp(Object a, double lo, double hi) { return obj("type", "minecraft:clamp", "input", a, "min", lo, "max", hi); }
}
