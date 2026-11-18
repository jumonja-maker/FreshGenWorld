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
        assertEquals(C + "biome_erosion", router.get("erosion").getAsString());
        assertEquals(P + "regions/biome_temperature", router.get("temperature").getAsString());
        assertEquals(C + "lookup_moisture", pack.get(ClimateNicheCompiler.path(P + "regions/biome_vegetation")).getAsJsonObject().get("first").getAsString());
        assertFalse(pack.get(SurfaceOnlyCompiler.FINAL_DENSITY).toString().contains("biome_erosion"), "terrain does not read lookup axes");
        assertFalse(pack.containsKey(ClimateNicheCompiler.path(C + "desert_cap")), "the over-broad temperature cap is gone");
    }
}
