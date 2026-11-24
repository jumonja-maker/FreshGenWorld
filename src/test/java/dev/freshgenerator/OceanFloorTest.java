package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.9.1 ocean floor provinces, seabed palette and underwater communities. */
class OceanFloorTest {
    static final String P = OceanFloorCompiler.P, L = OceanFloorCompiler.L, O = OceanFloorCompiler.O;
    static Map<String, JsonElement> pack;
    @BeforeAll static void compile() throws Exception { pack = WorldgenCatalog.compile(new VanillaAssets()); }

    static Map<String, Double> site(double c, double h) {
        var v = new HashMap<String, Double>();
        v.put(P + "continents", c); v.put(L + "native_height", h);
        for (String n : List.of("province", "ridge", "mount", "swell", "trench", "size")) v.put("noise:" + O + n, .5);
        return v;
    }
    static double relief(Map<String, Double> v) { return eval(new JsonPrimitive(L + "ocean_relief"), v); }

    @Test void landCoastAndMushroomIslandsGetNoOceanRelief() {
        for (double c : new double[]{-.19, -.1, 0, .5, 1, -1.0, -1.05, -1.2}) assertEquals(0, relief(site(c, 70)), 1e-9, "c=" + c);
    }
    static double height(double c, double raw) {
        var v = site(c, 35); v.put(O + "raw", raw);
        return eval(new JsonPrimitive(L + "height_unguarded"), v);
    }
    @Test void openOceanCeilingIsSoftMonotoneAndBelowTheSurface() {
        assertEquals(70, height(.2, 70), 0, "no ceiling on land");
        assertEquals(70, height(-1.2, 70), 0, "no ceiling on mushroom islands");
        double previous = -1e9;
        for (double raw = 20; raw <= 140; raw += .25) {
            double h = height(-.5, raw);
            if (raw <= OceanFloor.CAP[0][0]) assertEquals(raw, h, 1e-9, "ordinary floor untouched");
            assertTrue(h >= previous - 1e-9, "monotone at " + raw); previous = h;
            assertTrue(h <= OceanFloor.CEILING + 1e-6 && h < 63 - 3, "submerged at " + raw + ": " + h);
        }
        // Different uplifts keep different summits instead of one cut plane.
        assertTrue(height(-.5, 60) - height(-.5, 56) > 1.2, "summits stay distinct below the cap");
    }
    @Test void reliefIsContinuousAtTheBranchEdges() {
        for (double edge : new double[]{OceanFloor.C_OPEN, OceanFloor.C_MUSHROOM}) {
            var v = site(edge - 1e-6, 35); v.put("noise:" + O + "mount", 1.); v.put("noise:" + O + "province", 1.);
            var w = site(edge + 1e-6, 35); w.put("noise:" + O + "mount", 1.); w.put("noise:" + O + "province", 1.);
            assertEquals(relief(v), relief(w), 1e-3, "edge " + edge);
        }
    }
    @Test void provincesShapeOnlyTheDeepFloorAndTheShelfStaysQuiet() {
        var basin = site(-.6, 35); basin.put("noise:" + O + "province", 1.); basin.put("noise:" + O + "swell", 0.);
        basin.put("noise:" + O + "mount", 0.); basin.put("noise:" + O + "trench", 1.); basin.put("noise:" + O + "size", 1.);
        assertEquals(-OceanFloor.BASIN, relief(basin), 1e-6, "abyssal basin");
        basin.put("noise:" + O + "trench", 0.);
        assertEquals(-OceanFloor.BASIN - OceanFloor.TRENCH, relief(basin), 1e-6, "trench line in a basin");
        var ridge = site(-.6, 35); ridge.put("noise:" + O + "province", -1.); ridge.put("noise:" + O + "swell", 0.);
        ridge.put("noise:" + O + "mount", 0.); ridge.put("noise:" + O + "ridge", .1);
        assertTrue(relief(ridge) > 3, "ridge flank rises");
        ridge.put("noise:" + O + "ridge", 0.);
        assertEquals(OceanFloor.RIDGE - OceanFloor.RIFT, relief(ridge), 1e-6, "central rift");
        var mount = site(-.6, 35); mount.put("noise:" + O + "mount", 1.); mount.put("noise:" + O + "swell", 0.); mount.put("noise:" + O + "province", 0.);
        double r = relief(mount); assertTrue(r > 15 && 35 + r < OceanFloor.CEILING, "seamount stays submerged: " + r);
        mount.put("noise:" + O + "size", -1.);
        double small = relief(mount); assertTrue(small < r - 8 && small > 6, "seamounts differ in height: " + small + " / " + r);
        mount.put("noise:" + O + "size", .5); mount.put("noise:" + O + "mount", .9);
        double shoulder = relief(mount); mount.put("noise:" + O + "mount", .95);
        assertTrue(relief(mount) - shoulder > .5, "summit is domed, not a flat top hat");
        var shelf = site(-.3, 48); shelf.put("noise:" + O + "mount", 1.); shelf.put("noise:" + O + "province", 1.);
        assertEquals(0, relief(shelf), 1e-9, "sedimentary shelf unchanged");
    }
    @Test void seabedPaletteFollowsSlopeDepthAndClimate() {
        assertEquals(LandscapeMaterials.Cover.NATIVE, LandscapeMaterials.seabed(60, 63, 2, 0, 0, 0), "shoreline reach stays coastal");
        for (double p = -1; p <= .9; p += .1) {
            assertEquals(LandscapeMaterials.Cover.STONE, LandscapeMaterials.seabed(30, 63, 3, 0, 0, p), "steep walls expose bedrock");
            assertNotEquals(LandscapeMaterials.Cover.STONE, LandscapeMaterials.seabed(30, 63, .1, 0, 0, p), "flats hold sediment");
        }
        int warmSand = 0, coldSand = 0, deepClay = 0, shallowClay = 0;
        for (double p = -1; p <= 1; p += .01) {
            if (LandscapeMaterials.seabed(45, 63, .1, .8, p, 0) == LandscapeMaterials.Cover.SAND) warmSand++;
            if (LandscapeMaterials.seabed(45, 63, .1, -.8, p, 0) == LandscapeMaterials.Cover.SAND) coldSand++;
            if (LandscapeMaterials.seabed(28, 63, .1, 0, p, 0) == LandscapeMaterials.Cover.CLAY) deepClay++;
            if (LandscapeMaterials.seabed(52, 63, .1, 0, p, 0) == LandscapeMaterials.Cover.CLAY) shallowClay++;
        }
        assertTrue(warmSand > 2 * coldSand, "warm seas sandy, cold seas gravelly: " + warmSand + " / " + coldSand);
        assertTrue(deepClay > shallowClay, "fine sediment collects on deep quiet floors");
    }
    @Test void seabedGroundFollowsTheProvince() {
        int plainRock = 0, ridgeRock = 0, plainClay = 0, basinClay = 0, ridgeBasalt = 0, plainBasalt = 0;
        for (double p = -1; p <= 1; p += .02) for (double rp = -1; rp <= 1; rp += .1) {
            var plain = LandscapeMaterials.seabed(30, 63, .6, 0, p, rp, 0);
            var ridge = LandscapeMaterials.seabed(30, 63, .6, 0, p, rp, 14);
            var basin = LandscapeMaterials.seabed(30, 63, .1, 0, p, rp, -7);
            if (plain == LandscapeMaterials.Cover.STONE || plain == LandscapeMaterials.Cover.GRAVEL) plainRock++;
            if (ridge == LandscapeMaterials.Cover.BASALT || ridge == LandscapeMaterials.Cover.GRAVEL) ridgeRock++;
            if (ridge == LandscapeMaterials.Cover.BASALT) ridgeBasalt++;
            if (plain == LandscapeMaterials.Cover.BASALT) plainBasalt++;
            if (LandscapeMaterials.seabed(30, 63, .1, 0, p, rp, 0) == LandscapeMaterials.Cover.CLAY) plainClay++;
            if (basin == LandscapeMaterials.Cover.CLAY) basinClay++;
        }
        assertTrue(ridgeRock > plainRock, "uplifts are rockier: " + ridgeRock + " / " + plainRock);
        assertTrue(ridgeBasalt > 0 && plainBasalt == 0, "basalt only on volcanic uplifts");
        assertTrue(basinClay > plainClay, "basins collect clay: " + basinClay + " / " + plainClay);
        for (double p = -1; p <= 1; p += .1) assertEquals(LandscapeMaterials.seabed(30, 63, .3, .2, p, .1), LandscapeMaterials.seabed(30, 63, .3, .2, p, .1, 0), "no relief keeps the previous palette");
    }
    @Test void deepWaterGetsASupportedSedimentBody() {
        var top = pack.get(OceanFloorCompiler.SURFACE).getAsJsonObject().getAsJsonArray("sequence");
        var under = top.get(top.size() - 2).getAsJsonObject();
        assertEquals("minecraft:under_floor", under.get("if_true").getAsString());
        String s = under.toString();
        assertTrue(s.contains("minecraft:not_under_deep_water") && s.contains("minecraft:on_ceiling"), "only under deep water and never over air");
        assertTrue(s.contains("\"subsurface\":true") && s.contains("\"ocean_relief\":\"" + L + "ocean_relief\""), s);
        assertTrue(top.get(top.size() - 1).toString().contains("gravel_or_stone_if_ceiling"), "native deep branch stays last");
    }
    @Test void underwaterCommunitiesFollowLightGroundAndPatches() {
        for (var c : List.of(OceanEcology.Community.SEAGRASS, OceanEcology.Community.KELP, OceanEcology.Community.REEF)) {
            double previous = 2;
            for (double d = 1; d <= 60; d += .5) { double s = OceanEcology.suitability(c, d, OceanEcology.Ground.ROCK, 1); assertTrue(s <= previous + 1e-12, c + " darker water never helps"); previous = s; }
            assertEquals(0, OceanEcology.suitability(c, 36, OceanEcology.Ground.SEDIMENT, 1), 0, c + " absent in the deep basin");
        }
        assertTrue(OceanEcology.suitability(OceanEcology.Community.KELP, 8, OceanEcology.Ground.ROCK, 1) > OceanEcology.suitability(OceanEcology.Community.KELP, 8, OceanEcology.Ground.SEDIMENT, 1), "kelp holds on rock");
        assertTrue(OceanEcology.suitability(OceanEcology.Community.SEAGRASS, 8, OceanEcology.Ground.SEDIMENT, 1) > OceanEcology.suitability(OceanEcology.Community.SEAGRASS, 8, OceanEcology.Ground.ROCK, 1), "seagrass roots in sediment");
        assertEquals(0, OceanEcology.suitability(OceanEcology.Community.KELP, 8, OceanEcology.Ground.ROCK, -1), 0, "kelp forests come in patches");
        assertEquals(OceanEcology.Community.KELP, OceanEcology.community("minecraft:kelp"));
        assertEquals(OceanEcology.Community.SEAGRASS, OceanEcology.community("minecraft:seagrass_tall"));
        assertEquals(OceanEcology.Community.NONE, OceanEcology.community("minecraft:oak"));
        assertEquals(OceanEcology.Community.NONE, OceanEcology.community("seagrass_addon:oak"));
        assertEquals(OceanEcology.Community.NONE, OceanEcology.community("forestry:dry_seagrass_marker"));
        assertEquals(OceanEcology.Community.SEAGRASS, OceanEcology.community("minecraft:seagrass_short"));
    }

    static double eval(JsonElement n, Map<String, Double> v) {
        if (n.isJsonPrimitive()) {
            if (n.getAsJsonPrimitive().isNumber()) return n.getAsDouble();
            String id = n.getAsString(); if (v.containsKey(id)) return v.get(id);
            return eval(Objects.requireNonNull(pack.get(VanillaAssets.path("density_function", id)), id), v);
        }
        var o = n.getAsJsonObject(); String t = o.get("type").getAsString();
        switch (t) {
            case "minecraft:cache": return eval(o.get("input"), v);
            case "minecraft:abs": return Math.abs(eval(o.get("input"), v));
            case "minecraft:clamp": return Math.clamp(eval(o.get("input"), v), o.get("min").getAsDouble(), o.get("max").getAsDouble());
            case "minecraft:noise": return Objects.requireNonNull(v.get("noise:" + o.get("noise").getAsString()), o.get("noise").getAsString());
            case "minecraft:spline": {
                var s = o.getAsJsonObject("spline"); double x = eval(s.get("coordinate"), v);
                var pts = s.getAsJsonArray("points"); int m = pts.size();
                double[] l = new double[m], val = new double[m], d = new double[m];
                for (int i = 0; i < m; i++) { var q = pts.get(i).getAsJsonObject(); l[i] = q.get("location").getAsDouble(); val[i] = q.get("value").getAsDouble(); d[i] = q.get("derivative").getAsDouble(); }
                if (x <= l[0]) return val[0] + d[0] * (x - l[0]);
                if (x >= l[m - 1]) return val[m - 1] + d[m - 1] * (x - l[m - 1]);
                int i = 0; while (x >= l[i + 1]) i++;
                double h = l[i + 1] - l[i], u = (x - l[i]) / h, a = d[i] * h - (val[i + 1] - val[i]), b = -d[i + 1] * h + (val[i + 1] - val[i]);
                return val[i] + u * (val[i + 1] - val[i]) + u * (1 - u) * (a + u * (b - a));
            }
            case "minecraft:range_choice": {
                double x = eval(o.get("input"), v);
                boolean in = x >= o.get("min_inclusive").getAsDouble() && x < o.get("max_exclusive").getAsDouble();
                return eval(o.get(in ? "when_in_range" : "when_out_of_range"), v);
            }
            default: {
                double a = eval(o.get("left"), v), b = eval(o.get("right"), v);
                return switch (t) { case "minecraft:add" -> a + b; case "minecraft:sub" -> a - b; case "minecraft:mul" -> a * b;
                    case "minecraft:min" -> Math.min(a, b); case "minecraft:max" -> Math.max(a, b); default -> throw new AssertionError(t); };
            }
        }
    }
}
