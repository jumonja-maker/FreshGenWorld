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
        assertEquals(C + "frozen_moisture", pack.get(ClimateNicheCompiler.path(P + "regions/biome_vegetation")).getAsJsonObject().get("first").getAsString());
        assertTrue(pack.get(ClimateNicheCompiler.path(C + "highland_moisture")).toString().contains("\"" + C + "lookup_moisture\""));
        assertFalse(pack.get(SurfaceOnlyCompiler.FINAL_DENSITY).toString().contains("biome_erosion"), "terrain does not read lookup axes");
        assertFalse(pack.containsKey(ClimateNicheCompiler.path(C + "desert_cap")), "the over-broad temperature cap is gone");
    }
}
