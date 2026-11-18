package dev.freshgenerator.pack;

import com.google.gson.*;
import java.util.*;
import static dev.freshgenerator.core.KarstModel.*;

/** 0.9.0 cave system (replaces the 0.6-0.8 cave network in the current world).
 * All shapes are world-coordinate density fields interpolated on the native
 * four-block grid: no carvers, no chunk-local stamps, no post-load edits and no
 * custom fluids. Native aquifers still decide water and lava inside caves.
 * See {@link dev.freshgenerator.core.KarstModel} for the natural analogue.
 */
public final class KarstCaveCompiler {
    public static final String P = CoherentSurfaceCompiler.PREFIX, K = P + "karst/";
    public static final String H = P + "coherent/height", Y = "minecraft:y";
    public static final Set<String> REPLACED = Set.of(SurfaceOnlyCompiler.FINAL_DENSITY, SurfaceOnlyCompiler.CAVE_WEIGHT);
    private static final Gson G = new Gson();
    private KarstCaveCompiler() { }

    private static final class Graph {
        final Map<String, JsonElement> out; Graph(Map<String, JsonElement> out) { this.out = out; }
        void noise(String name, int octave, int count) {
            if (out.putIfAbsent(VanillaAssets.path("noise", K + name), obj("base_octave", octave, "octave_count", count, "base_amplitude", 1.0)) != null)
                throw new IllegalStateException("Karst noise exists: " + name);
        }
        String node(String name, JsonElement value) {
            if (out.putIfAbsent(path(K + name), value) != null) throw new IllegalStateException("Karst node exists: " + name);
            return K + name;
        }
    }

    public static void apply(Map<String, JsonElement> output) {
        var staged = new TreeMap<>(Objects.requireNonNull(output));
        for (String p : REPLACED) Objects.requireNonNull(staged.get(p), "Missing cave input " + p);
        if (!new JsonPrimitive(1).equals(staged.get(path(SurfaceOnlyCompiler.DISABLED_FUNCTION))))
            throw new IllegalStateException("Native carvers must remain disabled");
        JsonObject old = staged.get(SurfaceOnlyCompiler.FINAL_DENSITY).getAsJsonObject();
        if (!old.get("left").getAsString().equals(P + "coherent/surface_density")
                || !old.getAsJsonObject("right").get("type").getAsString().equals("minecraft:beardifier"))
            throw new IllegalStateException("Expected finished solid landscape and native structure fit");
        Graph g = new Graph(staged);

        // Joint plan shared by all levels; each level is displaced by its own warp.
        g.noise("plan_a", -8, 2); g.noise("plan_b", -8, 2); g.noise("maze_plan", -6, 2);
        for (String w : List.of("warp_mx", "warp_mz", "warp_dx", "warp_dz")) g.noise(w, -7, 1);
        for (String l : List.of("level_u", "level_m", "level_d")) g.noise(l, -9, 1);
        for (String s : List.of("size", "keyhole", "maze", "pit_great")) g.noise(s, -8, 1);
        g.noise("pillar", -4, 1); g.noise("rough", -4, 2); g.noise("pit_um", -6, 1); g.noise("pit_md", -6, 1);
        g.noise("doline", -9, 1); g.noise("ramp", -9, 1); g.noise("pothole", -7, 1); g.noise("cross", -6, 1); g.noise("ramp_cross", -9, 1);

        String wmx = g.node("warp_mx", cache(noise("warp_mx", 0))), wmz = g.node("warp_mz", cache(noise("warp_mz", 0)));
        String wdx = g.node("warp_dx", cache(noise("warp_dx", 0))), wdz = g.node("warp_dz", cache(noise("warp_dz", 0)));
        String gaU = g.node("plan_a_upper", cache(noise("plan_a", 0))), gbU = g.node("plan_b_upper", cache(noise("plan_b", 0)));
        String gaM = g.node("plan_a_main", cache(warped("plan_a", wmx, wmz, 9))), gbM = g.node("plan_b_main", cache(warped("plan_b", wmx, wmz, 9)));
        String gaD = g.node("plan_a_deep", cache(warped("plan_a", wdx, wdz, 12))), gbD = g.node("plan_b_deep", cache(warped("plan_b", wdx, wdz, 12)));
        String maze = g.node("maze_plan_deep", cache(warped("maze_plan", wdx, wdz, 12)));
        String lU = g.node("level_upper", cache(add(UPPER_C, mul(UPPER_A, noise("level_u", 0)))));
        String lM = g.node("level_main", cache(add(MAIN_C, mul(MAIN_A, noise("level_m", 0)))));
        String lD = g.node("level_deep", cache(add(DEEP_C, mul(DEEP_A, noise("level_d", 0)))));
        String sizeN = g.node("size_noise", cache(noise("size", 0)));
        // Halls: passages swell (both width and height) where the size field is low.
        String swell = g.node("swell", cache(spline(sizeN, new double[][]{{-.75, HALL_SWELL, 0}, {-.45, 1, 0}})));
        String hall = g.node("hall", cache(spline(sizeN, new double[][]{{-.65, 1, 0}, {-.5, 0, 0}})));
        String rough = g.node("rough", mul(ROUGHNESS, noise("rough", 1)));

        String tU = g.node("level_upper_tube", tube(gaU, gbU, lU, swell, hall, rough, UPPER_W, UPPER_HALF, .3, 1.6));
        String tM = g.node("level_main_tube", tube(gaM, gbM, lM, swell, hall, rough, MAIN_W, MAIN_HALF, .3, 2.0));
        String tD = g.node("level_deep_tube", tube(gaD, gbD, lD, swell, hall, rough, DEEP_W, DEEP_HALF, 0, 1.6));

        // Vadose keyhole: a narrow canyon cut below the main gallery floor.
        String keyN = g.node("keyhole_noise", cache(noise("keyhole", 0)));
        String keyMask = g.node("keyhole_mask", cache(spline(keyN, new double[][]{{KEYHOLE - .08, 0, 0}, {KEYHOLE + .08, 1, 0}})));
        String canyon = g.node("keyhole_canyon", add(max(sub(mul(minAbs(gaM, gbM), 1 / (MAIN_W * .4)), 1),
                mul(.3, max(sub(Y, lM), sub(sub(lM, 11), Y)))), mul(3, sub(1, keyMask))));
        // Anastomotic maze: dense small passages in parts of the deep level.
        String mazeN = g.node("maze_noise", cache(noise("maze", 0)));
        String mazeMask = g.node("maze_mask", cache(spline(mazeN, new double[][]{{MAZE - .08, 0, 0}, {MAZE + .08, 1, 0}})));
        String tMaze = g.node("maze_tube", add(add(square(mul(maze, 1 / MAZE_W)), square(mul(sub(Y, lD), 1 / 3.5))),
                add(sub(rough, 1), mul(3, sub(1, mazeMask)))));

        // Pits: bell-shaped (wider at the bottom), sitting on upper-level passages.
        String bandU = g.node("upper_band", cache(sub(mul(minAbs(gaU, gbU), 1 / (UPPER_W * 1.3)), 1)));
        String bandM = g.node("main_band", cache(sub(mul(minAbs(gaM, gbM), 1 / (MAIN_W * 1.1)), 1)));
        String pitUM = g.node("pit_upper_main", max(sub(max(mul(6, sub(PIT_SMALL, noise("pit_um", 0))), bandU),
                mul(.6, clamp(mul(sub(lU, Y), 1 / 30.0), 0, 1))), mul(.25, max(sub(Y, add(lU, 1)), sub(sub(lM, 3), Y)))));
        String pitMD = g.node("pit_main_deep", max(sub(max(mul(6, sub(PIT_SMALL_DEEP, noise("pit_md", 0))), bandM),
                mul(.6, clamp(mul(sub(lM, Y), 1 / 23.0), 0, 1))), mul(.25, max(sub(Y, add(lM, 1)), sub(sub(lD, 3), Y)))));
        // Great shafts open as chasms along a gallery (up to 3x its width), through all levels.
        String great = g.node("great_shaft", max(sub(max(mul(5, sub(PIT_GREAT, noise("pit_great", 0))), sub(mul(minAbs(gaU, gbU), 1 / UPPER_W), 3)),
                mul(.5, clamp(mul(sub(lU, Y), 1 / 53.0), 0, 1))),
                mul(.25, max(sub(Y, add(lU, 2)), sub(sub(lD, 4), Y)))));
        // Pillars: full-height rock columns inside halls only (supported at both ends).
        String pillar = g.node("hall_pillars", sub(mul(8, sub(noise("pillar", 0), PILLAR)), mul(100, sub(1, hall))));

        String network = g.node("network", min(min(min(tU, tM), min(tD, canyon)), min(min(tMaze, pitUM), min(pitMD, great))));
        String floor = g.node("floor_guard", mul(.2, sub(FLOOR_Y, Y)));
        String roof = g.node("roof_guard", mul(.2, sub(Y, sub(H, ROOF_COVER))));
        String guarded = g.node("network_guarded", max(max(network, pillar), max(roof, floor)));

        // Rare entrances, only on dry ground well above the sea surface.
        String allow = g.node("entrance_allow", cache(spline(H, new double[][]{{ENTRANCE_MIN_H, 0, 0}, {ENTRANCE_MIN_H + 7, 1, 0}, {185, 1, 0}, {205, 0, 0}})));
        String allowPot = g.node("pothole_allow", cache(spline(H, new double[][]{{POTHOLE_MIN_H, 0, 0}, {POTHOLE_MIN_H + 15, 1, 0}})));
        // Entrance points sit exactly on upper galleries: where a gallery line crosses a
        // zero line of the cross noise. A funnel/shaft centred there always meets the
        // passage below, at every depth (no blind bowls beside a gallery).
        String cross = g.node("cross", cache(abs(noise("cross", 0))));
        String depthT = g.node("entrance_depth", clamp(mul(sub(H, Y), 1 / DOLINE_FUNNEL), 0, 1));
        // The dry-ground rule protects the near-surface opening only: below the common
        // 14-block roof a passage may continue beneath a lower valley, while a suppressed
        // doline ends as a blind dome shaft under that roof.
        String deepFade = g.node("entrance_deep", clamp(mul(sub(sub(H, Y), ROOF_COVER), 1 / 6.0), 0, 1));
        String penalty = g.node("entrance_penalty", mul(3, mul(sub(1, allow), sub(1, deepFade))));
        String penaltyPot = g.node("pothole_penalty", mul(3, mul(sub(1, allowPot), sub(1, deepFade))));
        // Collapse doline: an elliptical shaft of constant radius around the crossing point,
        // flaring only in its top 6 blocks into a bowl. A surface opening deeper than the
        // bowl therefore always continues down onto the gallery (no blind funnels).
        String flare = g.node("entrance_flare", clamp(sub(1, mul(sub(H, Y), 1 / 6.0)), 0, 1));
        String doline = g.node("doline", add(max(max(mul(5, sub(sub(DOLINE_TOP, mul(.15, depthT)), noise("doline", 0))),
                sub(add(square(mul(minAbs(gaU, gbU), 1 / UPPER_W)), square(mul(cross, 1 / (CROSS_W * 2.5)))), add(1, mul(2, flare)))),
                mul(.25, sub(sub(lU, 3), Y))), penalty));
        // Inclined entrance: where an upper gallery line crosses a zero line of a slow cross
        // noise, the passage opens as a short roofless gorge. Away from that point the
        // depth grows with |cross|, i.e. with distance along the gallery, until it joins
        // the upper level in both directions.
        String rampN = g.node("ramp_noise", cache(noise("ramp", 0)));
        // Depth in blocks grows with |cross| (distance along the gallery) and stops at the
        // upper level: reaching it does not depend on the local noise amplitude.
        String rampD = g.node("ramp_depth", cache(clamp(mul(sub(abs(noise("ramp_cross", 0)), RAMP_FLAT), RAMP_DROP), 0, 400)));
        String rampY = g.node("ramp_centre", max(lU, sub(sub(H, 3), rampD)));
        String ramp = g.node("ramp", add(max(add(add(square(mul(gaU, 1 / (UPPER_W * 1.5))), square(mul(sub(Y, rampY), 1 / RAMP_HALF))), sub(rough, 1)),
                // The gate relaxes as the passage deepens, so a descent that started inside
                // the entrance field is not cut off before it reaches the upper level.
                mul(5, sub(sub(RAMP_GATE, clamp(mul(rampD, 1 / 250.0), 0, .35)), rampN))), penalty));
        // Pothole: a narrow plateau shaft on a gallery crossing, bell-shaped toward the upper level.
        String pothole = g.node("pothole", add(max(sub(max(mul(8, sub(POTHOLE, noise("pothole", 0))),
                sub(add(square(mul(minAbs(gaU, gbU), 1 / UPPER_W)), square(mul(cross, 1 / (CROSS_W * 1.5)))), add(1, flare))),
                mul(.3, clamp(mul(sub(sub(H, 20), Y), 1 / 60.0), 0, 1))), mul(.25, sub(sub(lU, 2), Y))), penaltyPot));
        String entrances = g.node("entrances", max(min(min(doline, ramp), pothole), floor));

        String body = g.node("body", obj("type", "minecraft:interpolated", "cell_size_xz", 4, "cell_size_y", 4, "input", min(guarded, entrances)));
        String finalBody = g.node("final_body", min(P + "coherent/surface_density", body));
        g.node("enabled", new JsonPrimitive(1));
        staged.put(SurfaceOnlyCompiler.FINAL_DENSITY, add(finalBody, old.get("right").deepCopy()));
        staged.put(SurfaceOnlyCompiler.CAVE_WEIGHT, new JsonPrimitive(1));
        output.putAll(staged);
    }

    private static JsonObject tube(String a, String b, String level, String swell, String hall, String rough, double w, double half, double dome, double floor) {
        JsonElement inv = mul(swell, 1 / w);
        JsonElement plan = min(square(mul(a, inv)), square(mul(b, inv)));
        // Domed ceiling (taller in halls), flatter floor.
        JsonElement up = mul(sub(Y, level), mul(mul(swell, 1 / half), sub(1, mul(dome, hall))));
        JsonElement down = mul(sub(level, Y), mul(swell, floor / half));
        return add(add(plan, square(max(up, down))), sub(rough, 1));
    }
    private static JsonElement minAbs(String a, String b) { return min(abs(a), abs(b)); }
    private static JsonObject noise(String name, double yScale) {
        return obj("type", "minecraft:noise", "noise", K + name, "xz_scale", 1.0, "y_scale", yScale);
    }
    private static JsonObject warped(String name, String wx, String wz, double blocks) {
        var n = noise(name, 0); n.add("shift_x", mul(blocks, wx)); n.add("shift_z", mul(blocks, wz)); return n;
    }
    private static JsonObject spline(String coordinate, double[][] points) {
        JsonArray list = new JsonArray();
        for (double[] p : points) list.add(obj("location", p[0], "value", p[1], "derivative", p[2]));
        return obj("type", "minecraft:spline", "spline", obj("coordinate", coordinate, "points", list));
    }
    public static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static JsonElement e(Object o) { return o instanceof JsonElement j ? j : G.toJsonTree(o); }
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
