package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClimateNicheTest {
    static final String P = ClimateNicheCompiler.P, C = ClimateNicheCompiler.C;
    static Map<String, JsonElement> pack;
    @BeforeAll static void compile() throws Exception { pack = WorldgenCatalog.compile(new VanillaAssets()); KarstCaveTest.pack = pack; }

    static double lookupMoisture(double t, double m) {
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "biome_moisture", m));
        return KarstCaveTest.eval(new JsonPrimitive(C + "lookup_moisture"), v);
    }
    static double erosion(double e, double h) {
        var v = new HashMap<String, Double>(Map.of(P + "erosion", e, P + "coherent/height", h));
        return KarstCaveTest.eval(new JsonPrimitive(C + "biome_erosion"), v);
    }
    @Test void rainforestMeetsTheDesertOnlyThroughADryBelt() {
        // Just below the native desert threshold no jungle humidity (>= 0.1) survives.
        for (double t = ClimateNicheCompiler.BELT_FULL; t < .55; t += .0001)
            for (double m = -.1; m <= 1; m += .05) assertTrue(lookupMoisture(t, m) < -.1, "jungle next to desert at t=" + t + " m=" + m);
        // The hot zone itself (desert, all badlands incl. wooded, warm ocean) keeps native humidity.
        for (double m = -1; m <= 1; m += .1) assertEquals(m, lookupMoisture(.56, m), 1e-6);
        for (double m = -1; m <= 1; m += .1) assertEquals(m, lookupMoisture(.9, m), 1e-6);
        // Cooler climates are untouched; dry regions are never made wetter.
        for (double m = -1; m <= 1; m += .1) assertEquals(m, lookupMoisture(.3, m), 1e-6);
        for (double t = -1; t <= 1; t += .01) assertTrue(lookupMoisture(t, -.5) <= -.5 + 1e-9);
    }
    @Test void wetlandBiomesNeedGroundNearTheWaterSurface() {
        assertEquals(.8, erosion(.8, 63), 1e-6); assertEquals(.8, erosion(.8, 66), 1e-6);
        assertTrue(erosion(.8, 72) < .55, "upland swamp trigger capped"); assertTrue(erosion(.8, 90) < .55);
        for (double e = -1; e <= .54; e += .1) assertEquals(e, erosion(e, 120), 1e-6, "other erosion bands untouched");
    }
    @Test void onlyLookupAxesChange() {
        var router = pack.get(ClimateNicheCompiler.SETTINGS).getAsJsonObject().getAsJsonObject("noise_router");
        // 0.9.1 (audit R5) wraps the 0.9.0 lookup erosion with a cold-coast floor; it still reads it.
        // ... and the snow massif altitude belts wrap that again (lookup only).
        assertEquals(SnowMassifCompiler.M + "routed_erosion", router.get("erosion").getAsString());
        assertTrue(pack.get(ClimateNicheCompiler.path(SnowMassifCompiler.M + "belt_erosion")).toString().contains("\"" + C + "coast_erosion\""));
        assertTrue(pack.get(ClimateNicheCompiler.path(C + "coast_erosion")).toString().contains("\"" + C + "frozen_windswept\""));
        assertTrue(pack.get(ClimateNicheCompiler.path(C + "frozen_erosion")).toString().contains("\"" + C + "biome_erosion\""));
        assertEquals(P + "regions/biome_temperature", router.get("temperature").getAsString());
        // 0.9.1 (audit R4) wraps the 0.9.0 lookup humidity with a highland floor; it still reads it.
        // 0.10 (ClimateShapeCompiler): the lookup humidity is the surface humidity at every depth (no depth lerp).
        assertEquals(C + "frozen_moisture", pack.get(ClimateNicheCompiler.path(P + "regions/biome_vegetation")).getAsJsonObject().get("input").getAsString());
        assertTrue(pack.get(ClimateNicheCompiler.path(C + "highland_moisture")).toString().contains("\"" + C + "lookup_moisture\""));
        assertFalse(pack.get(SurfaceOnlyCompiler.FINAL_DENSITY).toString().contains("biome_erosion"), "terrain does not read lookup axes");
        assertFalse(pack.containsKey(ClimateNicheCompiler.path(C + "desert_cap")), "the over-broad temperature cap is gone");
    }

    @org.junit.jupiter.api.Test void waterUsesTheSameDryBelt() {
        // The drainage model applies the biome table's dry belt itself (audit 23.11): same constants, same curve.
        org.junit.jupiter.api.Assertions.assertEquals(ClimateNicheCompiler.BELT_START, dev.freshgenerator.core.hydro.HydroClimate.BELT_START);
        org.junit.jupiter.api.Assertions.assertEquals(ClimateNicheCompiler.BELT_FULL, dev.freshgenerator.core.hydro.HydroClimate.BELT_FULL);
        org.junit.jupiter.api.Assertions.assertEquals(ClimateNicheCompiler.BELT_END, dev.freshgenerator.core.hydro.HydroClimate.BELT_END);
        org.junit.jupiter.api.Assertions.assertEquals(ClimateNicheCompiler.DESERT_T, dev.freshgenerator.core.hydro.HydroClimate.DESERT_T);
        org.junit.jupiter.api.Assertions.assertEquals(ClimateNicheCompiler.BELT_MOISTURE, dev.freshgenerator.core.hydro.HydroClimate.BELT_MOISTURE);
        org.junit.jupiter.api.Assertions.assertEquals(dev.freshgenerator.core.hydro.HydroClimate.Setting.SAVANNA,
                dev.freshgenerator.core.hydro.HydroClimate.setting(0.50, 0.8, 0.0, 68), "the belt drains like a savanna");
    }

    @org.junit.jupiter.api.Test void waterCoolingMatchesTheBiomeClimate() {
        // Audit 23.11 K-3: the hot zone cools less with height; the drainage model must see the same curve.
        var pack = HighlandClimateTest.pack != null ? HighlandClimateTest.pack : compiledPack();
        KarstCaveTest.pack = pack;
        String C = HighlandClimateCompiler.C, P = HighlandClimateCompiler.P;
        assertEquals(ClimateShapeCompiler.HOT_T0, dev.freshgenerator.core.hydro.HydroClimate.HOT_T0);
        assertEquals(ClimateShapeCompiler.HOT_T1, dev.freshgenerator.core.hydro.HydroClimate.HOT_T1);
        assertEquals(ClimateShapeCompiler.HOT_RELIEF, dev.freshgenerator.core.hydro.HydroClimate.HOT_RELIEF);
        for (double t : new double[]{-0.5, 0.0, 0.3, 0.45, 0.5, 0.55, 0.6, 0.8}) for (double h : new double[]{70, 120, 180, 237, 300}) {
            var v = new HashMap<String, Double>(Map.of(C + "zone_temperature", t, P + "coherent/height", h));
            double cooling = KarstCaveTest.eval(new JsonPrimitive(C + "altitude_cooling"), v);
            assertEquals(dev.freshgenerator.core.hydro.HydroClimate.cooled(t, h), t - cooling, 2e-3, "t=" + t + " h=" + h);
        }
        // A hot summit stays out of the frost column of the biome table (temperature >= 0.2) at Y260.
        assertTrue(dev.freshgenerator.core.hydro.HydroClimate.cooled(0.6, 260) >= 0.2);
        // Temperate mountains keep their snow line.
        assertEquals(0.3 - 0.42 * 0.9415, dev.freshgenerator.core.hydro.HydroClimate.cooled(0.3, 237), 0.01);
    }
    private static Map<String, JsonElement> compiledPack() {
        try { return WorldgenCatalog.compile(new VanillaAssets()); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
