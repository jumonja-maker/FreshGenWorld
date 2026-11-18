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
        v.put(P + "coherent/height", h); v.put(P + "coherent/surface_density", 0.); v.put("minecraft:y", 0.);
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
        DefaultWorldCompiler.apply(before);
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
        var v = world(100); plan(v, 0); v.put("noise:" + K + "doline", 1.); v.put("noise:" + K + "cross", 0.);
        for (double y = KarstModel.UPPER_C; y < 100; y += .5) assertTrue(body(v, y) < 0, "doline gap " + y);
        plan(v, .5); assertTrue(body(v, 98) > 0, "doline away from a gallery");
        v = world(70); plan(v, 0); v.put("noise:" + K + "doline", 1.); v.put("noise:" + K + "cross", 0.);
        for (double y = 70 - KarstModel.ROOF_COVER + .5; y < 70; y += 1) assertTrue(body(v, y) > 0, "entrance near sea level " + y);
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
        var v = world(160); plan(v, 0); v.put("noise:" + K + "pothole", 1.); v.put("noise:" + K + "cross", 0.);
        for (double y = KarstModel.UPPER_C; y < 160; y += .5) assertTrue(body(v, y) < 0, "pothole gap " + y);
        v.put(P + "coherent/height", 110.);
        for (double y = 97; y < 110; y += 1) assertTrue(body(v, y) > 0, "no pothole below the plateau limit");
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
    static double eval(JsonElement n, Map<String, Double> v) {
        if (n.isJsonPrimitive()) {
            if (n.getAsJsonPrimitive().isNumber()) return n.getAsDouble();
            String id = n.getAsString(); if (v.containsKey(id)) return v.get(id);
            return eval(Objects.requireNonNull(pack.get(path(id)), id), v);
        }
        var o = n.getAsJsonObject(); String t = o.get("type").getAsString();
        switch (t) {
            case "minecraft:cache", "minecraft:interpolated": return eval(o.get("input"), v);
            case "minecraft:abs": return Math.abs(eval(o.get("input"), v));
            case "minecraft:square": { double x = eval(o.get("input"), v); return x * x; }
            case "minecraft:clamp": return Math.clamp(eval(o.get("input"), v), o.get("min").getAsDouble(), o.get("max").getAsDouble());
            case "minecraft:noise": return Objects.requireNonNull(v.get("noise:" + o.get("noise").getAsString()), o.get("noise").getAsString());
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
