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
        g.noise("doline", DOLINE_OCTAVE, 1); g.noise("ramp", -9, 1); g.noise("pothole", -7, 1); g.noise("cross", -6, 1); g.noise("ramp_cross", -9, 1);

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
        // 0.9.1: a short 5-block band. In a partly allowed band the near-surface penalty
        // pinched the shaft where it starts to wind (FULL check: closed 12 blocks down).
        String allowPot = g.node("pothole_allow", cache(spline(H, new double[][]{{POTHOLE_MIN_H, 0, 0}, {POTHOLE_MIN_H + 5, 1, 0}})));
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
        // 0.9.1: dolines and potholes sit on a junction of the two gallery families and are
        // sized in blocks: the signed distance to each upper gallery line is the plan noise
        // divided by its local gradient (central differences over 8 blocks, 1/sqrt by spline).
        // Sizes in noise units stretched into long rifts and wandering shafts wherever the
        // noise flattened (measured gradients down to 0.0014 per block). These nodes are only
        // referenced inside range_choice branches near entrance sites.
        String invA = inverseGradient(g, "plan_a"), invB = inverseGradient(g, "plan_b");
        String distA = g.node("plan_a_distance", cache(mul(mul(gaU, 8.0), invA)));
        String distB = g.node("plan_b_distance", cache(mul(mul(gbU, 8.0), invB)));
        // A junction only counts where the two gallery lines really cross. Where they run
        // nearly parallel the two distances stop being independent coordinates and a winding
        // shaft fell apart into closed pockets (external audit C1: cosine -0.999996, FULL
        // check closed 22 blocks below the opening). |sin| of the crossing angle closes those.
        String sinAB = g.node("junction_sin", cache(abs(mul(mul(sub(mul(K + "plan_a_gx", K + "plan_b_gz"), mul(K + "plan_a_gz", K + "plan_b_gx")), invA), invB))));
        JsonElement junction = mul(JUNCTION_K, max(0, sub(JUNCTION_SIN, sinAB)));
        // Update 19.11 (audit C1): the junction point. distA and distB grow by one block per
        // block along the unit normals nA, nB of the two gallery lines; solving the 2x2 system
        // gives the offset (dx, dz) to their crossing. 1/det is a spline that is exact for
        // |det| >= 0.45 (the junction penalty closes everything below 0.39) and bounded through 0.
        String nAx = g.node("plan_a_nx", cache(mul(K + "plan_a_gx", invA))), nAz = g.node("plan_a_nz", cache(mul(K + "plan_a_gz", invA)));
        String nBx = g.node("plan_b_nx", cache(mul(K + "plan_b_gx", invB))), nBz = g.node("plan_b_nz", cache(mul(K + "plan_b_gz", invB)));
        String det = g.node("junction_det", cache(sub(mul(nAx, nBz), mul(nAz, nBx))));
        String invDet = g.node("junction_inverse_det", cache(spline(det, new double[][]{{-1, -1, -1}, {-.75, -1 / .75, -1 / (.75 * .75)},
                {-.6, -1 / .6, -1 / (.6 * .6)}, {-.45, -1 / .45, -1 / (.45 * .45)}, {-.35, -1 / .35, 0}, {.35, 1 / .35, 0},
                {.45, 1 / .45, -1 / (.45 * .45)}, {.6, 1 / .6, -1 / (.6 * .6)}, {.75, 1 / .75, -1 / (.75 * .75)}, {1, 1, -1}})));
        String jdx = g.node("junction_dx", cache(mul(sub(mul(distB, nAz), mul(distA, nBz)), invDet)));
        String jdz = g.node("junction_dz", cache(mul(sub(mul(nBx, distA), mul(nAx, distB)), invDet)));
        // The crossing angle is judged at the junction point too (gradients of both plan noises
        // there): on curving, nearly tangent gallery lines the local angle 15 blocks away was
        // 0.95 while the lines met at 0.02, and the shaft was set on that false crossing
        // (seed 0, X3192 Z3932). One decision per junction keeps a shaft whole or absent.
        String invAJ = inverseGradientAt(g, "plan_a", jdx, jdz), invBJ = inverseGradientAt(g, "plan_b", jdx, jdz);
        String sinJ = g.node("junction_sin_at", cache(abs(mul(mul(sub(mul(K + "plan_a_jgx", K + "plan_b_jgz"), mul(K + "plan_a_jgz", K + "plan_b_jgx")), invAJ), invBJ))));
        JsonElement junctionJ = mul(JUNCTION_K, max(0, sub(JUNCTION_SIN_AT, sinJ)));
        String dolN = g.node("doline_noise", cache(noise("doline", 0)));
        String dolJ = g.node("doline_junction_noise", cache(atJunction("doline", jdx, jdz)));
        JsonElement dolGate = mul(5, sub(sub(DOLINE_TOP, mul(.15, depthT)), dolN));
        JsonElement dolGateJ = mul(5, sub(sub(DOLINE_TOP, mul(.15, depthT)), dolJ));
        // Below DOLINE_NEAR the gate alone keeps rock in either form (the junction gate as well:
        // see KarstModel.DOLINE_NEAR), so the unchanged 0.9.0 field is evaluated there.
        JsonElement dolPlain = max(dolGate, sub(add(square(mul(minAbs(gaU, gbU), 1 / UPPER_W)), square(mul(cross, 1 / (CROSS_W * 2.5)))), add(1, mul(2, flare))));
        JsonElement dolRound = add(max(dolGateJ, sub(add(square(mul(distA, 1 / DOLINE_R_BLOCKS)), square(mul(distB, 1 / DOLINE_R_BLOCKS))), add(1, mul(2, flare)))), junctionJ);
        String doline = g.node("doline", add(max(obj("type", "minecraft:range_choice", "input", dolN, "min_inclusive", -1.0e6,
                "max_exclusive", DOLINE_NEAR, "when_in_range", dolPlain, "when_out_of_range", dolRound),
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
        // Pothole: a plateau shaft on a gallery crossing, bell-shaped toward the upper level.
        // 0.9.1: it descends in pitches; each pitch is offset sideways from the one above,
        // leaving natural ledges instead of one straight fall of up to 150 blocks.
        String potDepth = g.node("pothole_depth", sub(H, Y));
        // Update 19.11 (audit C1): the helix phase follows absolute height, not the depth below
        // the local surface. On a steep plateau edge H changes by 20-30 blocks across one shaft,
        // so columns of the same section sat on different turns and the shaft fell apart into
        // closed stacked pockets (seed 0, X3192 Z3920). Near-surface terms keep potDepth.
        String potPhase = g.node("pothole_phase", sub(POTHOLE_PHASE_TOP, Y));
        String potShiftC = g.node("pothole_shift_cross", spline(potPhase, helix(POTHOLE_SWING * POTHOLE_R_BLOCKS, true)));
        String potShiftP = g.node("pothole_shift_plan", spline(potPhase, helix(POTHOLE_SWING * POTHOLE_R_BLOCKS, false)));
        // Junction of both gallery families, in blocks (see doline); the helix swings in blocks.
        // Update 19.11 (audit C1): the section is a circle in blocks around the helix centre,
        // which is placed relative to the junction point (offset jdx, jdz from this column).
        JsonElement potPlan = add(jdx, potShiftP);
        JsonElement potCross = add(jdz, potShiftC);
        String potN = g.node("pothole_noise", cache(noise("pothole", 0)));
        String potJ = g.node("pothole_junction_noise", cache(atJunction("pothole", jdx, jdz)));
        // Opening threshold POTHOLE in the top POTHOLE_TOP-2 blocks, POTHOLE_BODY from POTHOLE_TOP+2 down.
        JsonElement potThreshold = add(POTHOLE_BODY, mul(POTHOLE - POTHOLE_BODY, clamp(mul(sub(POTHOLE_TOP + 2, potDepth), .25), 0, 1)));
        JsonElement potGate = mul(8, sub(potThreshold, potJ));
        JsonElement potGatePlain = mul(8, sub(POTHOLE, potN));
        // Away from pothole sites (local noise below POTHOLE_NEAR: closed in either form) the
        // unchanged 0.9.0 shape is evaluated, so caves elsewhere keep their exact field
        // and the pitch splines cost nothing; range_choice evaluates one branch only.
        JsonElement plain = max(potGatePlain, sub(add(square(mul(minAbs(gaU, gbU), 1 / UPPER_W)), square(mul(cross, 1 / (CROSS_W * 1.5)))), add(1, flare)));
        JsonElement pitched = add(max(potGate, sub(add(square(mul(potPlan, 1 / POTHOLE_R_BLOCKS)), square(mul(potCross, 1 / POTHOLE_R_BLOCKS))), add(1, flare))), junctionJ);
        JsonElement shape = obj("type", "minecraft:range_choice", "input", potN, "min_inclusive", -1.0e6, "max_exclusive", POTHOLE_NEAR,
                "when_in_range", plain, "when_out_of_range", pitched);
        String pothole = g.node("pothole", add(max(sub(shape,
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
    /** One axis of the winding pothole centre by depth below the surface: a Hermite
     * approximation of cos (cross axis) or sin (plan axis) with irregular quarter
     * turns, by phase POTHOLE_PHASE_TOP - Y (absolute height, the same for every column). */
    static double[][] helix(double amplitude, boolean cosine) {
        var points = new ArrayList<double[]>();
        points.add(new double[]{0, 0, 0});
        double d = POTHOLE_TOP; int n = POTHOLE_QUARTERS.length;
        for (int k = 0; d < POTHOLE_PHASE_TOP + 80; k++) {
            double q = POTHOLE_QUARTERS[k % n], before = k == 0 ? q : POTHOLE_QUARTERS[(k - 1) % n];
            double rate = Math.PI / 2 / ((q + before) / 2), a = amplitude * POTHOLE_SWAY[k % POTHOLE_SWAY.length];
            double c = new double[]{1, 0, -1, 0}[k % 4], s = new double[]{0, 1, 0, -1}[k % 4];
            points.add(cosine ? new double[]{d, a * c, -a * rate * s} : new double[]{d, a * s, a * rate * c});
            d += q;
        }
        return points.toArray(double[][]::new);
    }
    /** 8 / |gradient| of an upper joint-plan noise, as a node: central differences over
     * 8 blocks (also kept as gx / gz nodes for the crossing angle), inverse square root by
     * a log-spaced Hermite spline. Below the smallest gradient it saturates, so a flat noise
     * gives large distances and no entrance. */
    private static String inverseGradient(Graph g, String name) {
        String gx = g.node(name + "_gx", cache(sub(shifted(name, 4, 0), shifted(name, -4, 0))));
        String gz = g.node(name + "_gz", cache(sub(shifted(name, 0, 4), shifted(name, 0, -4))));
        String grad2 = g.node(name + "_grad2", cache(add(square(gx), square(gz))));
        var points = new ArrayList<double[]>();
        for (int k = 0; k <= 20; k++) { double d = 1e-6 * Math.pow(10, k / 3.0); points.add(new double[]{d, Math.pow(d, -.5), -.5 * Math.pow(d, -1.5)}); }
        return g.node(name + "_inverse_gradient", cache(spline(grad2, points.toArray(double[][]::new))));
    }
    /** The same 8 / |gradient| as {@link #inverseGradient}, at the junction point (field shifts). */
    private static String inverseGradientAt(Graph g, String name, String dx, String dz) {
        String gx = g.node(name + "_jgx", cache(sub(shiftedBy(name, dx, 4, dz, 0), shiftedBy(name, dx, -4, dz, 0))));
        String gz = g.node(name + "_jgz", cache(sub(shiftedBy(name, dx, 0, dz, 4), shiftedBy(name, dx, 0, dz, -4))));
        String grad2 = g.node(name + "_jgrad2", cache(add(square(gx), square(gz))));
        var points = new ArrayList<double[]>();
        for (int k = 0; k <= 20; k++) { double d = 1e-6 * Math.pow(10, k / 3.0); points.add(new double[]{d, Math.pow(d, -.5), -.5 * Math.pow(d, -1.5)}); }
        return g.node(name + "_inverse_gradient_at", cache(spline(grad2, points.toArray(double[][]::new))));
    }
    private static JsonObject shiftedBy(String name, String dx, double ox, String dz, double oz) {
        var n = noise(name, 0); n.add("shift_x", add(dx, ox)); n.add("shift_z", add(dz, oz)); return n;
    }
    private static JsonObject shifted(String name, double dx, double dz) {
        // 26.3 merged shifted_noise into minecraft:noise (shift_x / shift_z, as in vanilla continents).
        var n = noise(name, 0); n.add("shift_x", e(dx)); n.add("shift_z", e(dz)); return n;
    }
    /** A 2-D gate noise read at the gallery junction (offset by field shifts in blocks). */
    private static JsonObject atJunction(String name, String dx, String dz) {
        var n = noise(name, 0); n.add("shift_x", e(dx)); n.add("shift_z", e(dz)); return n;
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
