package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LandscapeBiomeCompilerTest {
    private static final String P = LandscapeBiomeCompiler.PREFIX, C = LandscapeBiomeCompiler.CLIMATE;
    private static Map<String, JsonElement> baseline() throws Exception {
        Map<String, JsonElement> out = new TreeMap<>(WorldgenCatalog.compileTerrain(new VanillaAssets()));
        SurfaceOnlyCompiler.apply(out);
        LandscapeTerrainCompiler.apply(out);
        return out;
    }
    private static Map<String, JsonElement> compiled() throws Exception {
        Map<String, JsonElement> out = baseline();
        LandscapeBiomeCompiler.apply(out);
        return out;
    }
    @Test void onlyClimateFieldsAndDebugMetadataChange() throws Exception {
        var before = baseline(); var after = new TreeMap<>(before);
        LandscapeBiomeCompiler.apply(after);
        Set<String> changed = new TreeSet<>();
        for (String path : before.keySet()) if (!before.get(path).equals(after.get(path))) changed.add(path);
        // Routing already points at the selected climate functions, so only their
        // definitions and debug entries change; biome tables are never rewritten.
        assertEquals(LandscapeBiomeCompiler.REPLACED, changed);
        JsonObject oldSettings = before.get(LandscapeBiomeCompiler.SETTINGS).deepCopy().getAsJsonObject();
        JsonObject newSettings = after.get(LandscapeBiomeCompiler.SETTINGS).deepCopy().getAsJsonObject();
        oldSettings.remove("debug_functions"); newSettings.remove("debug_functions");
        assertEquals(C+"biome_continents",newSettings.getAsJsonObject("noise_router").get("continents").getAsString());
        newSettings.getAsJsonObject("noise_router").addProperty("continents",P+"continents");
        assertEquals(oldSettings, newSettings);
        for (String path : after.keySet()) if (!before.containsKey(path)) {
            assertTrue(path.contains("/landscape/climate/"), path);
            assertTrue(path.contains("/density_function/") || path.contains("/noise/"), path);
            assertNativeTypes(after.get(path));
        }
    }
    @Test void emittedDensityRootsNeverUseBareRegistryReferences() throws Exception {
        var before = baseline(); var after = new TreeMap<>(before);
        LandscapeBiomeCompiler.apply(after);
        for (var entry : after.entrySet()) {
            if (!entry.getKey().contains("/density_function/") || Objects.equals(before.get(entry.getKey()), entry.getValue())) continue;
            JsonElement root = entry.getValue();
            assertTrue(root.isJsonObject() || (root.isJsonPrimitive() && root.getAsJsonPrimitive().isNumber()),
                    "Registry density roots must be native function objects or numeric constants: " + entry.getKey());
        }
    }
    @Test void localMoistureVariesWithinAStableRegionalClimate() throws Exception {
        var pack = compiled(); var input = inputs(0.45, 0.09, 80, -0.2, 0);
        input.put(C + "local_vegetation", -0.8);
        double dryPatch = sample(pack, P + "vegetation", input);
        input.put(C + "local_vegetation", 0.8);
        double wetPatch = sample(pack, P + "vegetation", input);
        assertTrue(dryPatch < 0.1 && wetPatch > 0.1, "Local variation crosses a native moisture boundary");
        assertTrue(wetPatch - dryPatch > 0.15);
        input.put(C + "regional_vegetation", -0.6);
        assertTrue(sample(pack, P + "vegetation", input) < -0.4, "A local wet patch cannot erase a dry region");
        input.put(C + "regional_temperature", 0.7); input.put(C + "local_temperature", -1.0);
        assertTrue(sample(pack, P + "temperature", input) > 0.55, "Warm regions remain available to native biome selection");
        input.put(C + "regional_temperature", -0.7); input.put(C + "local_temperature", 1.0);
        assertTrue(sample(pack, P + "temperature", input) < -0.55, "Cold regions remain available");
    }
    @Test void altitudeUsesTerrainHeightWithoutCoolingEcologyTwice() throws Exception {
        var pack = compiled(); var input = inputs(0.3, 0.2, 80, 0.2, 0);
        double reference = sample(pack, P + "temperature", input);
        assertEquals(reference, sample(pack, C + "surface_temperature", input), 1e-9);
        double previous = reference;
        for (int height = 80; height <= 304; height++) {
            input.put(P + "coherent/height", (double) height);
            assertEquals(reference, sample(pack, P + "temperature", input), 1e-9);
            double selected = sample(pack, C + "surface_temperature", input);
            assertTrue(selected <= previous + 1e-9, "Increasing elevation must not warm the climate");
            assertTrue(previous - selected < 0.006, "No abrupt altitude band");
            previous = selected;
        }
        assertEquals(LandscapeBiomeCompiler.MAX_COOLING, reference - previous, 1e-9);
    }
    @Test void originalDeepClimateRemainsExactAndTransitionIsBounded() throws Exception {
        var pack = compiled(); var input = inputs(0.45, -0.25, 230, 0.3, 0);
        input.put(P + "regions/original_temperature", -0.65);
        input.put(P + "regions/original_vegetation", 0.55);
        for (String axis : List.of("temperature", "vegetation")) {
            String selected = P + "regions/biome_" + axis;
            String surface = axis.equals("temperature") ? C + "surface_temperature" : C + "biome_moisture";
            double near = sample(pack, surface, input), deep = input.get(P + "regions/original_" + axis);
            for (int depth = -32; depth <= 96; depth++) {
                input.put(P + "coherent/depth_blocks", (double) depth);
                double actual = sample(pack, selected, input);
                if (depth <= 24) assertEquals(near, actual, 1e-9);
                else if (depth >= 64) assertEquals(deep, actual, 1e-9);
                else assertTrue(actual >= Math.min(near, deep) - 1e-9 && actual <= Math.max(near, deep) + 1e-9);
            }
        }
    }
    @Test void continentalBiasStaysSmallAndClimateStaysFinite() throws Exception {
        var pack = compiled(); var input = inputs(0.1, 0.2, 90, -0.2, 0);
        double coastal = sample(pack, P + "vegetation", input);
        input.put(P + "continents", 1.0);
        assertEquals(LandscapeBiomeCompiler.MAX_CONTINENTAL_DRYING,
                coastal - sample(pack, P + "vegetation", input), 1e-9);
        for (double t : new double[]{-1.5, -0.5, 0, 0.5, 1.5})
            for (double m : new double[]{-1.5, 0, 1.5})
                for (double h : new double[]{-48, 63, 144, 220, 304}) {
                    var extreme = inputs(t, m, h, 1, 0);
                    extreme.put(C + "local_temperature", t); extreme.put(C + "local_vegetation", m);
                    for (String field : List.of(P + "temperature", P + "vegetation", C + "surface_temperature")) {
                        double value = sample(pack, field, extreme);
                        assertTrue(Double.isFinite(value) && value >= -1 && value <= 1, field);
                    }
                }
    }
    @Test void terrainMoistureTracksShelteredLandformsWithoutChangingDeepClimateOrWater() throws Exception {
        var pack=compiled();var input=inputs(.25,-.20,90,.4,0);
        double ordinary=sample(pack,P+"vegetation",input);
        input.put(P+"landscape/valley_floor",1.0);
        assertEquals(LandscapeBiomeCompiler.MAX_TERRAIN_WETTING,
                sample(pack,P+"vegetation",input)-ordinary,1e-9);
        input.put(P+"landscape/valley_floor",0.0);input.put(P+"landscape/footslope",1.0);
        assertEquals(.65*LandscapeBiomeCompiler.MAX_TERRAIN_WETTING,
                sample(pack,P+"vegetation",input)-ordinary,1e-9);
        input.put(P+"landscape/footslope",0.0);input.put(P+"landscape/mountain",1.0);input.put(P+"landscape/crest",1.0);
        assertEquals(-LandscapeBiomeCompiler.MAX_RIDGE_DRYING,
                sample(pack,P+"vegetation",input)-ordinary,1e-9);
        input.put(P+"coherent/height",63.0);
        assertEquals(0,sample(pack,C+"terrain_moisture",input),0,"Submerged/coastal climate is not reclassified by this soil proxy");
        input.put(P+"coherent/height",200.0);input.put(P+"coherent/depth_blocks",64.0);
        input.put(P+"landscape/valley_floor",1.0);
        assertEquals(input.get(P+"regions/original_vegetation"),sample(pack,P+"regions/biome_vegetation",input),0);
    }
    @Test void deterministicAndFailedApplicationDoesNotPartiallyMutateCatalog() throws Exception {
        assertEquals(compiled(), compiled());
        var repeated = compiled(); var unchanged = new TreeMap<>(repeated);
        assertThrows(IllegalStateException.class, () -> LandscapeBiomeCompiler.apply(repeated));
        assertEquals(unchanged, repeated);
        var missing = baseline(); missing.remove(LandscapeBiomeCompiler.path(P + "regions/original_vegetation"));
        var original = new TreeMap<>(missing);
        assertThrows(NullPointerException.class, () -> LandscapeBiomeCompiler.apply(missing));
        assertEquals(original, missing);
    }
    @Test void rootingSiteNoiseCannotSwitchRegionalBiomeIdentity() throws Exception {
        var pack=compiled();var input=inputs(-.46,-.13,72,-.04,0);
        input.put(P+"regions/original_vegetation",-.13);
        double t=sample(pack,P+"regions/biome_temperature",input);
        double m=sample(pack,P+"regions/biome_vegetation",input);
        double minSite=Double.POSITIVE_INFINITY,maxSite=Double.NEGATIVE_INFINITY;
        for(double local:new double[]{-1,-.5,0,.5,1}) {
            input.put(C+"local_temperature",local);input.put(C+"local_vegetation",local);
            input.put(P+"landscape/valley_floor",(local+1)/2);
            assertEquals(t,sample(pack,P+"regions/biome_temperature",input),0);
            assertEquals(m,sample(pack,P+"regions/biome_vegetation",input),0);
            double site=sample(pack,P+"vegetation",input);
            minSite=Math.min(minSite,site);maxSite=Math.max(maxSite,site);
        }
        assertTrue(m<-.1,"The measured snowy-taiga island must not be caused by local site noise crossing −.1");
        assertTrue(maxSite-minSite>.20,"Local habitat diversity is retained within a registered ecosystem");
    }
    @Test void regionalWarmColdWetAndDryEcosystemsStayReachable() throws Exception {
        var pack=compiled();
        for(double t:new double[]{-.9,-.3,.1,.4,.9})for(double m:new double[]{-.9,-.3,0,.4,.9}) {
            var input=inputs(t,m,80,-.2,0);
            assertEquals(t,sample(pack,P+"regions/biome_temperature",input),1e-9);
            assertEquals(m,sample(pack,P+"regions/biome_vegetation",input),1e-9);
        }
    }
    @Test void submergedCoastalPeaksUseOceanClimateWithoutChangingInlandRiversOrPhysicalLand() throws Exception {
        var pack=compiled();
        for(double h:new double[]{45.685,53.195,58.743,61}) {
            var input=inputs(.3,0,h,-.185,0);
            assertEquals(-.20,sample(pack,C+"biome_continents",input),1e-9);
            assertEquals(-.185,input.get(P+"continents"),0);
        }
        for(double c:new double[]{-1.2,-.7,.03,.3,1})for(double h:new double[]{45,63,100}) {
            var input=inputs(.3,0,h,c,0);
            assertEquals(c,sample(pack,C+"biome_continents",input),1e-9);
        }
        var input=inputs(.3,0,100,-.185,0);
        assertEquals(-.185,sample(pack,C+"biome_continents",input),1e-9);
        input.put(P+"coherent/height",45.0);input.put(P+"coherent/depth_blocks",64.0);
        assertEquals(-.185,sample(pack,C+"biome_continents",input),1e-9,"Deep native climate is retained");
    }
    private static Map<String, Double> inputs(double t, double m, double height, double continents, double depth) {
        Map<String, Double> input = new HashMap<>();
        input.put(C + "regional_temperature", t); input.put(C + "regional_vegetation", m);
        input.put(C + "local_temperature", 0.0); input.put(C + "local_vegetation", 0.0);
        input.put(P + "regions/original_temperature", 0.0); input.put(P + "regions/original_vegetation", 0.0);
        input.put(P + "coherent/height", height); input.put(P + "coherent/depth_blocks", depth);
        input.put(P + "continents", continents);
        for(String name:List.of("valley_floor","footslope","mountain","crest"))input.put(P+"landscape/"+name,0.0);
        return input;
    }
    /** Evaluate emitted native arithmetic with controlled noise inputs. No reimplementation
     * of the climate formula: the test traverses the actual generated dependency graph.
     */
    private static double sample(Map<String, JsonElement> pack, String id, Map<String, Double> input) {
        Double value = input.get(id);
        return value == null ? eval(pack, Objects.requireNonNull(pack.get(LandscapeBiomeCompiler.path(id)), id), input) : value;
    }
    private static double eval(Map<String, JsonElement> pack, JsonElement node, Map<String, Double> input) {
        if (node.isJsonPrimitive()) return node.getAsJsonPrimitive().isNumber() ? node.getAsDouble() : sample(pack, node.getAsString(), input);
        JsonObject o = node.getAsJsonObject();
        return switch (o.get("type").getAsString()) {
            case "minecraft:cache" -> eval(pack, o.get("input"), input);
            case "minecraft:clamp" -> Math.clamp(eval(pack, o.get("input"), input), o.get("min").getAsDouble(), o.get("max").getAsDouble());
            case "minecraft:add" -> eval(pack, o.get("left"), input) + eval(pack, o.get("right"), input);
            case "minecraft:sub" -> eval(pack, o.get("left"), input) - eval(pack, o.get("right"), input);
            case "minecraft:mul" -> eval(pack, o.get("left"), input) * eval(pack, o.get("right"), input);
            case "minecraft:min" -> Math.min(eval(pack, o.get("left"), input), eval(pack, o.get("right"), input));
            case "minecraft:max" -> Math.max(eval(pack, o.get("left"), input), eval(pack, o.get("right"), input));
            case "minecraft:lerp" -> {
                double a = eval(pack, o.get("alpha"), input), first = eval(pack, o.get("first"), input);
                yield first + a * (eval(pack, o.get("second"), input) - first);
            }
            default -> throw new AssertionError("Unexpected unbound native function: " + o);
        };
    }
    private static void assertNativeTypes(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("type")) assertTrue(object.get("type").getAsString().startsWith("minecraft:"));
            object.asMap().values().forEach(LandscapeBiomeCompilerTest::assertNativeTypes);
        } else if (value.isJsonArray()) value.getAsJsonArray().forEach(LandscapeBiomeCompilerTest::assertNativeTypes);
    }
}
