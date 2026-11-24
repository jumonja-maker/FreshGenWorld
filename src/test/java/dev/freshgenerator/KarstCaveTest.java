package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.core.CoastalRelief;
import dev.freshgenerator.core.KarstModel;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contract of the 0.9.0 karst cave graph, evaluated on its emitted JSON with
 * controlled noise values (shift fields ignored: inputs are given per node). */
class KarstCaveTest {
    static final String P = KarstCaveCompiler.P, K = KarstCaveCompiler.K;
    static Map<String, JsonElement> pack;
    @BeforeAll static void compile() throws Exception { pack = WorldgenCatalog.compile(new VanillaAssets()); }

    static Map<String, Double> world(double h) {
        var v = new HashMap<String, Double>();
        for (String n : List.of("plan_a", "plan_b", "maze_plan")) v.put("noise:" + K + n, .5);
        for (String n : List.of("warp_mx", "warp_mz", "warp_dx", "warp_dz", "level_u", "level_m", "level_d", "rough")) v.put("noise:" + K + n, 0.);
        v.put("noise:" + K + "size", .5);
        for (String n : List.of("pillar", "pit_um", "pit_md", "pit_great", "doline", "ramp", "pothole", "keyhole", "maze")) v.put("noise:" + K + n, -1.);
        v.put("noise:" + K + "cross", .5); v.put("noise:" + K + "ramp_cross", .5);
        // Entrance gate noises are flat at the scale of one entrance.
        for (String n : List.of("pothole", "doline")) v.put("gradx:" + K + n, 0.);
        v.put(P + "coherent/height", h); v.put(P + "coherent/terrain", h); v.put(P + "hydrology/dry_banks", 1.0); v.put(P + "coherent/surface_density", 0.); v.put("minecraft:y", 0.);
        return v;
    }
    static double body(Map<String, Double> v, double y) {
        v.put("minecraft:y", y);
        double h = v.get(P + "coherent/height"), x = Math.clamp(.08 * (h - y), -1, 1);
        v.put(P + "coherent/surface_density", x / 2 - x * x * x / 24);
        return eval(new JsonPrimitive(K + "final_body"), v);
    }
    static void plan(Map<String, Double> v, double a) { v.put("noise:" + K + "plan_a", a); }

    @Test void layerReplacesOnlyTheCaveBodyAndAddsOnlyKarstResources() throws Exception {
        var before = new TreeMap<>(WorldgenCatalog.compileTerrain(new VanillaAssets()));
        SurfaceOnlyCompiler.apply(before); LandscapeTerrainCompiler.apply(before); LandscapeBiomeCompiler.apply(before);
        LandscapeSurfaceCompiler.apply(before); TerrainRealismCompiler.apply(before); BiomeReliefCompiler.apply(before); ClimateNicheCompiler.apply(before);
        TerrainRepairCompiler.apply(before); HighlandClimateCompiler.apply(before); SnowMassifCompiler.apply(before); OceanFloorCompiler.apply(before); OceanStructureCompiler.apply(before, new VanillaAssets()); DryLandCompiler.apply(before); ClimateShapeCompiler.apply(before); HydrologyCompiler.apply(before); DefaultWorldCompiler.apply(before);
        var changed = new TreeSet<String>();
        before.forEach((k, val) -> { if (!val.equals(pack.get(k))) changed.add(k); });
        assertEquals(KarstCaveCompiler.REPLACED, changed);
        for (String k : pack.keySet()) if (!before.containsKey(k)) assertTrue(k.contains("/highlands_v2/karst/"), k);
        assertFalse(pack.keySet().stream().anyMatch(k -> k.contains("/cave_network/")), "Old network is not installed");
        assertEquals("minecraft:beardifier", pack.get(SurfaceOnlyCompiler.FINAL_DENSITY).getAsJsonObject().getAsJsonObject("right").get("type").getAsString());
        assertEquals(1, pack.get(path(SurfaceOnlyCompiler.DISABLED_FUNCTION)).getAsDouble(), "Native carvers stay off");
    }
    @Test void ordinaryTerrainKeepsFourteenBlocksOfRockEvenOnEveryGallery() {
        for (double h : new double[]{30, 50, 63, 80, 120, 200, 280}) for (double a : new double[]{0, .02, .5}) {
            var v = world(h); plan(v, a); v.put("noise:" + K + "pit_great", 1.); v.put("noise:" + K + "pit_um", 1.);
            for (double y = h - KarstModel.ROOF_COVER + .5; y < h; y += 1) assertTrue(body(v, y) > 0, "roof h=" + h + " y=" + y);
            for (double y = Math.ceil(h) + 1; y < 320; y += 7) assertTrue(body(v, y) < 0, "sky above surface");
        }
    }
    @Test void bottomStaysSealedAboveBedrock() {
        var v = world(100); plan(v, 0); v.put("noise:" + K + "pit_great", 1.); v.put("noise:" + K + "size", -1.);
        for (double y = -64; y < KarstModel.FLOOR_Y - 1; y += 1) assertTrue(body(v, y) > 0, "floor " + y);
    }
    @Test void threeStackedLevelsAreSeparatedByRockWithoutPits() {
        var v = world(100); plan(v, 0);
        assertTrue(body(v, KarstModel.UPPER_C) < 0); assertTrue(body(v, KarstModel.MAIN_C) < 0); assertTrue(body(v, KarstModel.DEEP_C) < 0);
        assertTrue(body(v, -9) > 0, "rock between upper and main level"); assertTrue(body(v, -36) > 0, "rock between main and deep level");
        plan(v, .5); assertTrue(body(v, KarstModel.MAIN_C) > 0, "away from the joint plan there is rock");
    }
    @Test void pitsAndGreatShaftsConnectTheLevelsContinuously() {
        var v = world(100); plan(v, 0); v.put("noise:" + K + "pit_um", 1.);
        for (double y = KarstModel.MAIN_C; y <= KarstModel.UPPER_C; y += .5) assertTrue(body(v, y) < 0, "pit gap " + y);
        v = world(100); plan(v, 0); v.put("noise:" + K + "pit_great", 1.); // great shafts open along a gallery
        for (double y = KarstModel.DEEP_C; y <= KarstModel.UPPER_C; y += .5) assertTrue(body(v, y) < 0, "shaft gap " + y);
    }
    @Test void collapseDolineOpensOnlyOverAGalleryOnDryHighGround() {
        var v = world(100); plan(v, 0); v.put("noise:" + K + "doline", 1.); v.put("noise:" + K + "plan_b", 0.);
        for (double y = KarstModel.UPPER_C; y < 100; y += .5) assertTrue(body(v, y) < 0, "doline gap " + y);
        plan(v, .5); assertTrue(body(v, 98) > 0, "doline away from a gallery");
        v = world(70); plan(v, 0); v.put("noise:" + K + "doline", 1.); v.put("noise:" + K + "plan_b", 0.);
        for (double y = 70 - KarstModel.ROOF_COVER + .5; y < 70; y += 1) assertTrue(body(v, y) > 0, "entrance near sea level " + y);
    }
    @Test void dolineWidthIsSetInBlocksWhateverTheNoiseGradient() {
        // 0.9.1: the former width in noise units grew into long rifts where the plan noise flattened.
        for (double g : new double[]{.0015, .008, .03}) {
            var v = world(120); v.put("noise:" + K + "doline", 1.); v.put("noise:" + K + "plan_b", 0.);
            v.put("gradx:" + K + "plan_a", g); v.put("gradz:" + K + "plan_b", g);
            int open = 0;
            for (double blocks = -20; blocks <= 20; blocks += .25) { v.put("noise:" + K + "plan_a", blocks * g); if (body(v, 80) < 0) open++; }
            double width = open * .25;
            assertTrue(width > 2 * KarstModel.DOLINE_R_BLOCKS - 2 && width < 2 * KarstModel.DOLINE_R_BLOCKS + 2, "gradient " + g + ": width " + width);
        }
    }
    @Test void nearlyParallelGalleriesGiveNoJunctionEntrance() {
        // External audit C1 (0.9.1): a winding pothole fell apart where both gallery lines ran parallel.
        for (String kind : new String[]{"doline", "pothole"}) {
            var v = world(160); v.put("noise:" + K + kind, 1.); plan(v, 0); v.put("noise:" + K + "plan_b", 0.);
            assertTrue(body(v, 157) < 0, kind + " opens at a right-angle junction");
            v.put("gradx:" + K + "plan_b", .008); v.put("gradz:" + K + "plan_b", .0003);
            for (double y = KarstModel.UPPER_C + 4; y < 158; y += 2) assertTrue(eval(new JsonPrimitive(K + kind), v) > 0, kind + " closed at a parallel crossing, y=" + y);
        }
    }
    @Test void entranceGatesAreReadAtTheJunctionPoint() {
        // Update 19.11 (audit C1): the offset to the crossing of both gallery lines, in blocks,
        // within a fiftieth of a block (1/sqrt and 1/det are splines), at a right angle and at 60 degrees (sample point (3, -2) from the junction).
        var v = world(160); plan(v, .04); v.put("noise:" + K + "plan_b", -.016);
        assertEquals(-5, eval(new JsonPrimitive(K + "junction_dx"), v), .02);
        assertEquals(2, eval(new JsonPrimitive(K + "junction_dz"), v), .02);
        v.put("gradx:" + K + "plan_b", .004); v.put("gradz:" + K + "plan_b", .008 * Math.sqrt(.75));
        plan(v, 3 * .008); v.put("noise:" + K + "plan_b", (.5 * 3 - Math.sqrt(.75) * 2) * .008);
        assertEquals(-3, eval(new JsonPrimitive(K + "junction_dx"), v), .02);
        assertEquals(2, eval(new JsonPrimitive(K + "junction_dz"), v), .02);
        for (String gate : new String[]{"pothole_junction_noise", "doline_junction_noise"}) {
            var shifts = pack.get(path(K + gate)).getAsJsonObject().getAsJsonObject("input");
            assertEquals(K + "junction_dx", shifts.get("shift_x").getAsString(), gate);
            assertEquals(K + "junction_dz", shifts.get("shift_z").getAsString(), gate);
        }
        // The crossing angle is judged at the junction point as well (one decision per junction).
        for (String kind : new String[]{"pothole", "doline"}) {
            String s = pack.get(path(K + kind)).toString();
            assertTrue(s.contains("\"" + K + "junction_sin_at\"") && !s.contains("\"" + K + "junction_sin\""), kind);
        }
    }
    @Test void inclinedEntranceStartsAsAShallowGorgeAndReachesTheUpperLevel() {
        var v = world(110); plan(v, 0); v.put("noise:" + K + "ramp", 1.); v.put("noise:" + K + "ramp_cross", 0.);
        assertTrue(body(v, 109) < 0 && body(v, 107) < 0, "gorge mouth");
        assertTrue(body(v, 100) > 0, "the gorge is shallow");
        v.put("noise:" + K + "ramp_cross", KarstModel.RAMP_FLAT + (110 - 3 - (110 - 1 + KarstModel.UPPER_C) / 2) / KarstModel.RAMP_DROP);
        assertTrue(body(v, (110 - 1 + KarstModel.UPPER_C) / 2) < 0, "mid-depth passage");
        assertTrue(body(v, 108) > 0, "covered further from the mouth");
        v.put("noise:" + K + "ramp_cross", 1.); assertTrue(body(v, KarstModel.UPPER_C) < 0, "joins the upper level");
        v.put("noise:" + K + "ramp", -1.); v.put("noise:" + K + "ramp_cross", 0.); assertTrue(body(v, 108) > 0, "outside the entrance region");
    }
    @Test void potholesOnlyOnHighPlateaus() {
        var v = world(160); plan(v, 0); v.put("noise:" + K + "pothole", 1.); v.put("noise:" + K + "plan_b", 0.);
        // The helix phase follows absolute height, so the mouth lies within one swing of the junction.
        for (double y = 155; y < 160; y += .5) { boolean open = false;
            for (double blocks = -8; blocks <= 8 && !open; blocks += .5) { plan(v, blocks * .008); open = body(v, y) < 0; }
            assertTrue(open, "pothole mouth beside the gallery junction " + y); }
        plan(v, 0);
        v.put(P + "coherent/height", 110.);
        for (double y = 97; y < 110; y += 1) assertTrue(body(v, y) > 0, "no pothole below the plateau limit");
    }
    @Test void deepPotholeDescendsInPitchesWithLedgesAndStaysConnected() {
        // 0.9.1 audit A06: a plateau shaft from H200 to the upper level. Position along
        // the junction is represented by plan_a (one gallery family) and plan_b (the other).
        double h = 200; var v = world(h); v.put("noise:" + K + "pothole", 1.);
        int na = 67, nc = 67, top = (int) h - 1, bottom = (int) KarstModel.UPPER_C;
        int layers = top - bottom + 1; boolean[][][] open = new boolean[layers][na][nc];
        for (int l = 0; l < layers; l++) { double y = top - l + .5; v.put("minecraft:y", y);
            for (int i = 0; i < na; i++) { v.put("noise:" + K + "plan_a", -.198 + i * .006);
                for (int j = 0; j < nc; j++) { v.put("noise:" + K + "plan_b", -.198 + j * .006);
                    open[l][i][j] = eval(new JsonPrimitive(K + "pothole"), v) < 0; } } }
        // Flood fill from the mouth: the shaft reaches the upper level without a blind end.
        boolean[][][] seen = new boolean[layers][na][nc]; var queue = new ArrayDeque<int[]>();
        for (int i = 0; i < na; i++) for (int j = 0; j < nc; j++) if (open[0][i][j]) { seen[0][i][j] = true; queue.add(new int[]{0, i, j}); }
        assertFalse(queue.isEmpty(), "pothole mouth");
        int deepest = 0;
        while (!queue.isEmpty()) { int[] c = queue.poll(); deepest = Math.max(deepest, c[0]);
            for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                int l = c[0] + d[0], i = c[1] + d[1], j = c[2] + d[2];
                if (l < 0 || l >= layers || i < 0 || i >= na || j < 0 || j >= nc || seen[l][i][j] || !open[l][i][j]) continue;
                seen[l][i][j] = true; queue.add(new int[]{l, i, j}); } }
        assertTrue(deepest >= layers - 3, "shaft continues to the upper level, reached " + (top - deepest));
        // Ledges: no straight vertical line falls through the whole shaft.
        int longest = 0;
        for (int i = 0; i < na; i++) for (int j = 0; j < nc; j++) { int run = 0;
            for (int l = 0; l < layers - 12; l++) { run = open[l][i][j] ? run + 1 : 0; longest = Math.max(longest, run); } }
        // Worst single column (the 0.9.0 shaft was one straight fall to the upper level);
        // the helix floor gives a ledge about every 8-10 blocks along the best route.
        assertTrue(longest <= 30, "longest free fall " + longest + " blocks of a " + layers + "-block shaft");
        // Consecutive layers overlap broadly (robust to the native 4-block interpolation).
        for (int l = 8; l + 4 < layers - 12; l++) { int shared = 0, here = 0;
            for (int i = 0; i < na; i++) for (int j = 0; j < nc; j++) { if (open[l][i][j]) { here++; if (open[l + 4][i][j]) shared++; } }
            assertTrue(here > 0 && shared * 4 >= here, "layers four blocks apart share at least a quarter of the section at depth " + l); }
    }
    @Test void potholeHelixSharesOnePhaseAcrossASteepSurface() {
        // Update 19.11 (audit C1): on a plateau edge the surface height changes by tens of blocks
        // across one shaft; the winding section must not depend on it.
        var v = world(200); v.put("noise:" + K + "pothole", 1.);
        for (double y = 60; y < 150; y += 7) { v.put("minecraft:y", y);
            double c = eval(new JsonPrimitive(K + "pothole_shift_cross"), v), p = eval(new JsonPrimitive(K + "pothole_shift_plan"), v);
            v.put(P + "coherent/height", 170.);
            assertEquals(c, eval(new JsonPrimitive(K + "pothole_shift_cross"), v), 1e-9, "y=" + y);
            assertEquals(p, eval(new JsonPrimitive(K + "pothole_shift_plan"), v), 1e-9, "y=" + y);
            v.put(P + "coherent/height", 200.); }
    }
    @Test void hallPillarsAreFullHeightRock() {
        var v = world(120); plan(v, 0); v.put("noise:" + K + "size", -1.); v.put("noise:" + K + "pillar", 1.);
        for (double y = -64; y < 106; y += 1) assertTrue(body(v, y) > 0, "pillar broken at " + y);
        v.put("noise:" + K + "pillar", -1.); assertTrue(body(v, KarstModel.MAIN_C) < 0, "hall without a pillar is open");
        v.put("noise:" + K + "size", .5); v.put("noise:" + K + "pillar", 1.); assertTrue(body(v, KarstModel.MAIN_C) < 0, "no pillars outside halls");
    }
    @Test void keyholeCanyonCutsBelowTheMainFloor() {
        var v = world(100); plan(v, 0); v.put("noise:" + K + "keyhole", 1.);
        assertTrue(body(v, KarstModel.MAIN_C - 9) < 0);
        v.put("noise:" + K + "keyhole", -1.); assertTrue(body(v, KarstModel.MAIN_C - 9) > 0);
    }
    @Test void allShapesAreInterpolatedOnTheNativeFourBlockGrid() {
        var body = pack.get(path(K + "body")).getAsJsonObject();
        assertEquals("minecraft:interpolated", body.get("type").getAsString());
        assertEquals(4, body.get("cell_size_xz").getAsInt()); assertEquals(4, body.get("cell_size_y").getAsInt());
    }

    static String path(String id) { return VanillaAssets.path("density_function", id); }
    // Node values within one top-level evaluation are memoised: field-shifted noises read the
    // junction offset many times, which made the plain recursion exponential.
    private static final Map<String, Double> MEMO = new HashMap<>();
    private static int depth;
    static double eval(JsonElement n, Map<String, Double> v) {
        if (depth == 0) MEMO.clear();
        depth++;
        try { return evaluate(n, v); } finally { depth--; }
    }
    private static double evaluate(JsonElement n, Map<String, Double> v) {
        if (n.isJsonPrimitive()) {
            if (n.getAsJsonPrimitive().isNumber()) return n.getAsDouble();
            String id = n.getAsString(); if (v.containsKey(id)) return v.get(id);
            Double known = MEMO.get(id); if (known != null) return known;
            double value = eval(Objects.requireNonNull(pack.get(path(id)), id), v);
            MEMO.put(id, value); return value;
        }
        var o = n.getAsJsonObject(); String t = o.get("type").getAsString();
        switch (t) {
            case "minecraft:cache", "minecraft:interpolated": return eval(o.get("input"), v);
            case "minecraft:abs": return Math.abs(eval(o.get("input"), v));
            case "minecraft:lerp": { double a = eval(o.get("alpha"), v), f = eval(o.get("first"), v); return f + a * (eval(o.get("second"), v) - f); }
            case "minecraft:square": { double x = eval(o.get("input"), v); return x * x; }
            case "minecraft:clamp": return Math.clamp(eval(o.get("input"), v), o.get("min").getAsDouble(), o.get("max").getAsDouble());
            case "minecraft:noise": {
                // Locally linear noise: shifts (constant or field, e.g. the junction offset) use a
                // per-noise gradient (default 0.008 per block along x); level warps are 0 here.
                String id = o.get("noise").getAsString();
                double base = Objects.requireNonNull(v.get("noise:" + id), id);
                if (!o.has("shift_x")) return base;
                // Default: the two gallery families cross at right angles (plan_b varies along z).
                boolean b = id.endsWith("plan_b");
                return base + v.getOrDefault("gradx:" + id, b ? 0. : .008) * eval(o.get("shift_x"), v) + v.getOrDefault("gradz:" + id, b ? .008 : 0.) * eval(o.get("shift_z"), v);
            }
            case "minecraft:range_choice": {
                double x = eval(o.get("input"), v);
                boolean in = x >= o.get("min_inclusive").getAsDouble() && x < o.get("max_exclusive").getAsDouble();
                return eval(o.get(in ? "when_in_range" : "when_out_of_range"), v);
            }
            case "minecraft:spline": {
                var s = o.getAsJsonObject("spline"); var pts = s.getAsJsonArray("points"); double[][] p = new double[pts.size()][];
                for (int i = 0; i < p.length; i++) { var q = pts.get(i).getAsJsonObject(); p[i] = new double[]{q.get("location").getAsDouble(), q.get("value").getAsDouble(), q.get("derivative").getAsDouble()}; }
                return CoastalRelief.spline(p, eval(s.get("coordinate"), v));
            }
            default: {
                double a = eval(o.get("left"), v), b = eval(o.get("right"), v);
                return switch (t) { case "minecraft:add" -> a + b; case "minecraft:sub" -> a - b; case "minecraft:mul" -> a * b;
                    case "minecraft:min" -> Math.min(a, b); case "minecraft:max" -> Math.max(a, b); default -> throw new AssertionError(t); };
            }
        }
    }
}
