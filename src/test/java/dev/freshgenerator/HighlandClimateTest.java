package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.9.1 (audit R4): montane forest, not savanna, on the cool side of the snow line. */
class HighlandClimateTest {
    static final String P = HighlandClimateCompiler.P, C = HighlandClimateCompiler.C;
    static Map<String, JsonElement> pack;
    @BeforeAll static void compile() throws Exception { pack = WorldgenCatalog.compile(new VanillaAssets()); KarstCaveTest.pack = pack; }

    static double moisture(double t, double m, double h) {
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "biome_moisture", m, P + "coherent/height", h));
        return KarstCaveTest.eval(new JsonPrimitive(C + "highland_moisture"), v);
    }
    @Test void coolHighPlateausLeaveTheSavannaBand() {
        // Native humidity band edges: savanna(_plateau) below -0.1, forest -0.1..0.1.
        for (double t = .2; t <= .3; t += .01) for (double m = -1; m <= 1; m += .1) {
            double v = moisture(t, m, 150);
            assertTrue(v > -.1 && v < .1, "cool plateau humidity stays in the forest band t=" + t + " m=" + m + ": " + v);
        }
        for (double m = -1; m <= 1; m += .1) {
            assertEquals(Math.min(m, 1), moisture(.25, m, 85), 1e-9, "lowland savanna and jungle stay");
            assertEquals(m, moisture(.10, m, 150), 1e-9, "the snowy side is untouched");
            assertEquals(m, moisture(.40, m, 150), 1e-9, "warmer plateaus stay");
            if (m <= .09) assertTrue(moisture(.25, m, 150) >= m - 1e-12, "dry side never drier");
        }
        assertTrue(moisture(.15, -.6, 150) < -.1, "well below the snow line the slope stays snowy_slopes");
    }
    static double boreal(double t, double m) {
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "highland_moisture", m));
        return KarstCaveTest.eval(new JsonPrimitive(C + "boreal_moisture"), v);
    }
    static double coastErosion(double t, double c, double e) {
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "surface_continents", c, C + "biome_erosion", e));
        return KarstCaveTest.eval(new JsonPrimitive(C + "coast_erosion"), v);
    }
    @Test void forestReachesTheSnowyPlainThroughTaiga() {
        // Audit R5: native humidity bands plains < -0.1 <= forest < 0.1 <= taiga < 0.3.
        for (double t = -.452; t <= -.33; t += .002) for (double m = -.1; m < .1; m += .01)
            assertTrue(boreal(t, m) >= .1 && boreal(t, m) < .3, "forest humidity next to the snow line t=" + t + " m=" + m);
        // The dry side passes through a taiga fringe too; plains further from the snow line stay open.
        for (double t = -.452; t <= -.40; t += .004) for (double m = -1; m <= -.15; m += .05)
            assertTrue(boreal(t, m) >= .1 && boreal(t, m) < .3, "taiga fringe t=" + t + " m=" + m);
        for (double m = -1; m <= -.15; m += .05) assertEquals(m, boreal(-.30, m), 1e-9, "dry plains away from the snow line stay open");
        for (double m = -1; m <= 1; m += .05) {
            assertEquals(m, boreal(-.2, m), 1e-9, "temperate forest untouched");
            assertEquals(m, boreal(-.6, m), 1e-9, "the snowy zone untouched");
            assertTrue(boreal(-.4, m) >= m - 1e-12, "never drier");
        }
    }
    @Test void coldCoastsPickTheSnowyBeachBand() {
        // Native erosion bins: stony_shore on coasts below -0.2225, beach bands above.
        for (double e = -1; e < -.2; e += .05) {
            assertTrue(coastErosion(-.82, -.12, e) > -.2225, "cold stony shore e=" + e);
            assertEquals(e, coastErosion(-.3, -.12, e), 1e-9, "temperate rocky coasts stay");
            assertEquals(e, coastErosion(-.82, .2, e), 1e-9, "inland cold erosion (peaks, slopes) untouched");
            assertEquals(e, coastErosion(-.82, -.4, e), 1e-9, "the ocean untouched");
        }
        for (double e = -.2; e <= 1; e += .1) assertEquals(e, coastErosion(-.82, -.12, e), 1e-9, "never lowered");
    }
    static double frozenM(double t, double m) {
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "boreal_moisture", m));
        return KarstCaveTest.eval(new JsonPrimitive(C + "frozen_moisture"), v);
    }
    @Test void frozenHumidPlacesAreSnowyTaigaNotRainyTaiga() {
        for (double m = -1; m <= 1; m += .05) {
            assertEquals(Math.min(m, .29), frozenM(-.66, m), 1e-9, "frozen band keeps the snowy_taiga column");
            assertEquals(m, frozenM(-.3, m), 1e-9, "cold and temperate humid forests untouched");
        }
    }
    @Test void onlyTheLookupHumidityChanges() {
        assertEquals(C + "frozen_moisture", pack.get(HighlandClimateCompiler.path(P + "regions/biome_vegetation")).getAsJsonObject().get("first").getAsString());
        assertTrue(pack.get(HighlandClimateCompiler.path(SnowMassifCompiler.M + "belt_erosion")).toString().contains("\"" + C + "coast_erosion\""));
        assertFalse(pack.get(SurfaceOnlyCompiler.FINAL_DENSITY).toString().contains("highland_moisture"), "terrain does not read lookup axes");
    }
}
