package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceOnlyCompilerTest {
    private static final String P = CoherentSurfaceCompiler.PREFIX;

    private static Map<String, JsonElement> compiled() throws Exception {
        var result = new TreeMap<>(WorldgenCatalog.compileTerrain(new VanillaAssets()));
        SurfaceOnlyCompiler.apply(result);
        return result;
    }

    @Test void currentLayerChangesOnlyFourResourcesAndPreservesNativeStructureFit() throws Exception {
        var before = WorldgenCatalog.compileTerrain(new VanillaAssets());
        var after = compiled();
        Set<String> changed = new TreeSet<>();
        for (var entry : before.entrySet()) if (!entry.getValue().equals(after.get(entry.getKey()))) changed.add(entry.getKey());
        assertEquals(SurfaceOnlyCompiler.REPLACED, changed);
        assertEquals(before.size() + 1, after.size());
        var finalDensity = after.get(SurfaceOnlyCompiler.FINAL_DENSITY).getAsJsonObject();
        assertEquals("minecraft:add", finalDensity.get("type").getAsString());
        assertEquals(P + "coherent/surface_density", finalDensity.get("left").getAsString());
        assertEquals(before.get(SurfaceOnlyCompiler.FINAL_DENSITY).getAsJsonObject().get("right"), finalDensity.get("right"));
        assertEquals(0, after.get(SurfaceOnlyCompiler.CAVE_WEIGHT).getAsDouble(), 0);
    }

    @Test void completeBuildHeightContainsSolidGroundAndNoSecondSurfaceAtAnyAltitude() throws Exception {
        var map = compiled();
        for (double height : new double[]{-47.75, -20, 40, 63.125, 64, 111.5, 190, 303.75}) {
            int ground = (int) Math.ceil(height) - 1;
            for (int y = -64; y <= 319; y++) {
                double density = density(map, height, y, 0);
                assertTrue(Double.isFinite(density));
                if (y <= ground) assertTrue(density > 0, "Cavity at h=" + height + " y=" + y);
                else assertTrue(density <= 0, "Floating terrain at h=" + height + " y=" + y);
            }
        }
    }

    @Test void steepCliffsCannotExposeTheFormerDeepCaveBranchLaterally() throws Exception {
        var map = compiled();
        // A vertical-only 40-block roof could expose a cave in the tall column
        // beside this lower ground. Removing the entire cave branch seals it too.
        double[] heights = {64.125, 64.125, 151.75, 220.5, 151.75, 64.125};
        for (double height : heights) for (int y = -64; y < Math.ceil(height); y++)
            assertTrue(density(map, height, y, 0) > 0);
    }

    @Test void exteriorAndAquiferOracleFollowTheFinalHeightRatherThanAFrozenCopy() throws Exception {
        var map = compiled();
        map.put(path(SurfaceOnlyCompiler.HEIGHT), new JsonPrimitive(72.125));
        assertTrue(evaluate(map.get(SurfaceOnlyCompiler.FINAL_DENSITY), map,
                new HashMap<>(Map.of("minecraft:y", 72.0, "minecraft:beardifier", 0.0))) > 0);
        assertTrue(evaluate(map.get(SurfaceOnlyCompiler.FINAL_DENSITY), map,
                new HashMap<>(Map.of("minecraft:y", 73.0, "minecraft:beardifier", 0.0))) < 0);
        assertEquals(64.125, evaluate(map.get(SurfaceOnlyCompiler.PRELIMINARY), map, new HashMap<>()), 0);
        assertEquals("minecraft:squeeze", map.get(SurfaceOnlyCompiler.SURFACE).getAsJsonObject().get("type").getAsString());
    }

    @Test void nativeDensityAndEntranceGatesAreAbsentFromTheReachableTerrainBody() throws Exception {
        var map = compiled();
        // Stop at the preceding layer's final 2-D height. Everything below that
        // contract belongs to this compiler and must not reach a cave/detail graph.
        Set<String> visited = new HashSet<>();
        visit(map.get(SurfaceOnlyCompiler.FINAL_DENSITY), map, visited);
        assertEquals(Set.of(P + "coherent/surface_density", SurfaceOnlyCompiler.HEIGHT), visited);
        for (double beard : new double[]{-.2, 0, .2})
            assertEquals(density(map, 100.25, 80, 0) + beard, density(map, 100.25, 80, beard), 1e-12);
    }

    @Test void activeMarkerDisablesEveryNativeCarverBeforeTheHistoricalAllowlist() throws Exception {
        var map = compiled();
        var debug = map.get(SurfaceOnlyCompiler.SETTINGS).getAsJsonObject().getAsJsonArray("debug_functions");
        var markers = new ArrayList<JsonObject>();
        for (var item : debug) if (item.getAsJsonObject().get("label").getAsString().equals(SurfaceOnlyCompiler.DISABLED_LABEL))
            markers.add(item.getAsJsonObject());
        assertEquals(1, markers.size());
        assertEquals(SurfaceOnlyCompiler.DISABLED_FUNCTION, markers.getFirst().get("function").getAsString());
        assertEquals(1, map.get(path(SurfaceOnlyCompiler.DISABLED_FUNCTION)).getAsDouble(), 0);
        String mixin = Files.readString(Path.of("src/main/java/dev/freshgenerator/mixin/CaveCarverMixin.java"));
        assertEquals("FG caves disabled", SurfaceOnlyCompiler.DISABLED_LABEL, "Compiled pack keeps the historical marker");
        assertFalse(mixin.contains("\"FG caves disabled\""), "Runtime reads the shared contract constant, not a literal");
        // 0.9.2 (audit "build10" T02): the marker value decides through NativeCarverSwitch, not the label text.
        String check = "if(NativeCarverSwitch.carversOff(generator,region.registryAccess(),state))return false;";
        String carverSwitch = Files.readString(Path.of("src/main/java/dev/freshgenerator/cave/NativeCarverSwitch.java"));
        assertTrue(carverSwitch.contains("/coherent/surface_only/caves_disabled") && carverSwitch.contains(">= .5"), "Marker value, found by resource key");
        assertFalse(carverSwitch.contains("label()"), "No dependence on the debug label");
        int marker = mixin.indexOf(check);
        assertTrue(marker > mixin.indexOf(".equals(CaveAccessModel.SETTINGS)"), "Other worlds retain native carvers");
        assertTrue(marker < mixin.indexOf("String id="), "All current carvers must return before the historical ID filter");
        assertTrue(marker < mixin.indexOf("new CaveCarverGuard("));
        assertTrue(mixin.contains("original.call(carver,generation,random,target,source,output)"), "Historical settings retain original RNG calls");
    }

    @Test void repeatedInstallationCannotPartiallyMutateThePack() throws Exception {
        var map = compiled();
        var before = new TreeMap<>(map);
        assertThrows(IllegalStateException.class, () -> SurfaceOnlyCompiler.apply(map));
        assertEquals(before, map);
    }

    private static String path(String id) { return VanillaAssets.path("density_function", id); }
    private static double density(Map<String, JsonElement> map, double height, double y, double beard) {
        return evaluate(map.get(SurfaceOnlyCompiler.FINAL_DENSITY), map,
                new HashMap<>(Map.of(SurfaceOnlyCompiler.HEIGHT, height, "minecraft:y", y, "minecraft:beardifier", beard)));
    }

    private static void visit(JsonElement node, Map<String, JsonElement> map, Set<String> visited) {
        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
            String id = node.getAsString();
            if (!id.startsWith(P)) return;
            assertFalse(id.contains("native_cave") || id.contains("cave_access") || id.contains("/detail/"), id);
            if (visited.add(id) && !id.equals(SurfaceOnlyCompiler.HEIGHT)) visit(map.get(path(id)), map, visited);
        } else if (node.isJsonObject()) for (var value : node.getAsJsonObject().asMap().values()) visit(value, map, visited);
        else if (node.isJsonArray()) for (var value : node.getAsJsonArray()) visit(value, map, visited);
    }

    private static double evaluate(JsonElement node, Map<String, JsonElement> map, Map<String, Double> values) {
        assertNotNull(node, "Undefined density function");
        if (node.isJsonPrimitive()) {
            if (node.getAsJsonPrimitive().isNumber()) return node.getAsDouble();
            String id = node.getAsString();
            return values.containsKey(id) ? values.get(id) : evaluate(map.get(path(id)), map, values);
        }
        var object = node.getAsJsonObject();
        String type = object.get("type").getAsString();
        if (values.containsKey(type)) return values.get(type);
        if (type.equals("minecraft:cache")) return evaluate(object.get("input"), map, values);
        if (type.equals("minecraft:squeeze")) {
            double input = Math.clamp(evaluate(object.get("input"), map, values), -1, 1);
            return input / 2 - input * input * input / 24;
        }
        double left = evaluate(object.get("left"), map, values), right = evaluate(object.get("right"), map, values);
        return switch (type) {
            case "minecraft:add" -> left + right;
            case "minecraft:sub" -> left - right;
            case "minecraft:mul" -> left * right;
            default -> throw new AssertionError("Unexpected reachable density node: " + type);
        };
    }
}
