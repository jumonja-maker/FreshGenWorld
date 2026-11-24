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
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "surface_continents", c, C + "biome_erosion", e, P + "erosion", e));
        return KarstCaveTest.eval(new JsonPrimitive(C + "coast_erosion"), v);
    }
    /** Lookup erosion from the raw erosion at height h (the 0.9.0 wetland cap applied first). */
    static double lookupErosion(double t, double raw, double h) {
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "surface_continents", .4, P + "erosion", raw, P + "coherent/height", h));
        return KarstCaveTest.eval(new JsonPrimitive(C + "coast_erosion"), v);
    }
    @Test void frozenHighlandsAreNotHeldInTheWindsweptRow() {
        // Audit R7 BIO-01: seed 1, X-5984 Z-7872 - T -0.87, raw erosion 0.83, H116 gave windswept (RAIN).
        assertTrue(lookupErosion(-.8703, .8283, 116) > .55, "frozen high ground reaches the snowy_plains row");
        for (double raw = .6; raw <= 1; raw += .05) {
            assertEquals(raw, lookupErosion(-.6, raw, 120), 1e-9, "frozen: native erosion");
            assertEquals(ClimateNicheCompiler.CAPPED_E, lookupErosion(-.40, raw, 120), 1e-9, "swamp rows (T >= -0.45) stay capped");
            assertEquals(ClimateNicheCompiler.CAPPED_E, lookupErosion(.1, raw, 120), 1e-9, "temperate highland swamps stay capped");
            for (double t = -.47; t <= -.452; t += .002) {
                double e = lookupErosion(t, raw, 120);
                assertTrue(e >= .55 - 1e-9 && e <= Math.max(raw, .67) + 1e-9, "frozen: released, never in the windswept row t=" + t + ": " + e);
            }
        }
        for (double raw = -1; raw < .45; raw += .05) assertEquals(raw, lookupErosion(-.8, raw, 120), 1e-9, "uncapped erosion untouched");
    }
    @Test void frozenLandIsNeverInTheWindsweptRow() {
        // World 0911 (seed -8283299496428459970, X-57 Z127): T -0.538, E 0.531, H116 gave windswept_forest (grass, rain).
        for (double t : new double[]{-1, -.8, -.538, -.46, -.4502}) for (double raw = .45; raw <= .55001; raw += .005)
            for (double h : new double[]{65, 116, 160}) {
                double e = lookupErosion(t, raw, h);
                assertTrue(e > .5502, "frozen erosion leaves the windswept row (0.55 inclusive) t=" + t + " raw=" + raw + " h=" + h + ": " + e);
            }
        for (double raw = .45; raw < .55; raw += .01) assertEquals(raw, lookupErosion(-.3, raw, 65), 1e-9, "cold (taiga) band keeps windswept forest");
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
        for (double e = -.2; e <= 1; e += .1) {
            if (e >= .45 - 1e-9 && e < .55) assertTrue(coastErosion(-.82, -.12, e) >= .55, "frozen windswept row moved up");
            else assertEquals(e, coastErosion(-.82, -.12, e), 1e-9, "never lowered");
        }
    }
    static double frozenM(double t, double m) {
        var v = new HashMap<String, Double>(Map.of(C + "surface_temperature", t, C + "boreal_moisture", m));
        return KarstCaveTest.eval(new JsonPrimitive(C + "frozen_moisture"), v);
    }
    @Test void frozenColumnRulesReachTheColumnEdge() {
        // Own audit 21.11: rainy taiga at T -0.4525 (seed 20261119, X-7808 Z-5264) and stony shore at T -0.5225 (seed 1, X320 Z-6976).
        for (double t = -.47; t < -.45; t += .0005) {
            for (double m = .3; m <= 1; m += .05) assertTrue(frozenM(t, m) <= .3, "frozen humidity stays below the rainy-taiga column t=" + t);
            for (double e = -1; e < -.2; e += .05) assertTrue(coastErosion(t, -.12, e) > -.2225, "no stony shore in the frozen column t=" + t + " e=" + e);
        }
    }
    @Test void frozenHumidPlacesAreSnowyTaigaNotRainyTaiga() {
        for (double m = -1; m <= 1; m += .05) {
            assertEquals(Math.min(m, .29), frozenM(-.66, m), 1e-9, "frozen band keeps the snowy_taiga column");
            assertEquals(m, frozenM(-.3, m), 1e-9, "cold and temperate humid forests untouched");
        }
    }
    @Test void onlyTheLookupHumidityChanges() {
        // 0.10 (ClimateShapeCompiler): the lookup humidity is the surface humidity at every depth (no depth lerp).
        assertEquals(C + "frozen_moisture", pack.get(HighlandClimateCompiler.path(P + "regions/biome_vegetation")).getAsJsonObject().get("input").getAsString());
        assertTrue(pack.get(HighlandClimateCompiler.path(SnowMassifCompiler.M + "belt_erosion")).toString().contains("\"" + C + "coast_erosion\""));
        assertFalse(pack.get(SurfaceOnlyCompiler.FINAL_DENSITY).toString().contains("highland_moisture"), "terrain does not read lookup axes");
    }
}
