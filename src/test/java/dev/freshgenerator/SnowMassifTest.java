package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.9.1 (user request): great massifs in the cores of large cold regions. */
class SnowMassifTest {
    static final String P = SnowMassifCompiler.P, L = SnowMassifCompiler.L, M = SnowMassifCompiler.M;
    static Map<String, JsonElement> pack;
    @BeforeAll static void compile() throws Exception { pack = WorldgenCatalog.compile(new VanillaAssets()); KarstCaveTest.pack = pack; }

    static double massif(double zoneT, double ridges, double land, double peaks, double size) {
        var v = new HashMap<String, Double>();
        v.put(L + "climate/zone_temperature", zoneT); v.put(P + "ridges_folded", ridges); v.put(L + "land", land);
        v.put("noise:" + L + "massif_peaks", peaks); v.put("noise:" + L + "massif_size", size);
        return KarstCaveTest.eval(new JsonPrimitive(M + "massif"), v);
    }
    @Test void massifGrowsWithTheDepthOfTheColdRegion() {
        assertEquals(0, massif(-.45, .95, 1, 1, 1), 0, "nothing at the snow line");
        assertEquals(0, massif(.3, .95, 1, 1, 1), 0, "nothing in warm land");
        double previous = 0;
        for (double t = -.50; t >= -1; t -= .02) { double m = massif(t, .95, 1, 1, 1); assertTrue(m >= previous - 1e-12, "deeper is never lower t=" + t); previous = m; }
        assertTrue(massif(-.55, .95, 1, 1, 1) * SnowMassifCompiler.HEIGHT < 15, "a small cold dip gets hills only");
        assertTrue(massif(-.9, .95, 1, 1, 1) * SnowMassifCompiler.HEIGHT > 140, "a large cold core gets a great massif");
    }
    @Test void massifKeepsRiversCoastsAndVariety() {
        assertEquals(0, massif(-.9, -.97, 1, 1, 1), 1e-12, "native river corridors stay free");
        assertEquals(0, massif(-.9, .95, 0, 1, 1), 1e-12, "only land rises");
        assertTrue(massif(-.9, .95, 1, 1, 1) > 1.5 * massif(-.9, .95, 1, -1, 1), "summits differ");
        assertTrue(massif(-.9, .95, 1, 1, 1) > 1.4 * massif(-.9, .95, 1, 1, -1), "whole massifs differ");
        assertTrue(massif(-.9, .95, 1, 1, 1) > 2 * massif(-.9, -.6, 1, 1, 1), "crests stand above the slopes");
    }
    static double[] belt(double zoneT, double h, double erosion, double ridges) {
        var v = new HashMap<String, Double>();
        v.put(L + "climate/zone_temperature", zoneT); v.put(P + "coherent/height", h);
        v.put(L + "climate/coast_erosion", erosion); v.put(P + "ridges", ridges);
        return new double[]{KarstCaveTest.eval(new JsonPrimitive(M + "belt_erosion"), v), KarstCaveTest.eval(new JsonPrimitive(M + "belt_ridges"), v)};
    }
    @Test void coldMountainBiomesFollowRealHeight() {
        // Native rows: erosion below -0.78 peaks, -0.78..-0.375 slopes; |weirdness| 0.57..0.77 is the peak band.
        // Mountain inputs (the landscape raises mountains only at low erosion; mountain weirdness band).
        double[] peak = belt(-.8, 200, -.1, .5), slope = belt(-.8, 165, -.1, -.5), low = belt(-.8, 110, -.1, .5);
        assertTrue(peak[0] < -.78 && Math.abs(peak[1] - 2 / 3.0) < 1e-6, "summits pick the peak rows");
        assertTrue(slope[0] > -.78 && slope[0] < -.375 && Math.abs(slope[1] + SnowMassifCompiler.W_HIGH) < 1e-6, "upper flanks pick slope rows, sign kept");
        assertEquals(-.1, low[0], 1e-9); assertEquals(.5, low[1], 1e-9);
        // Temperate mountains: the same belts about 25 blocks higher.
        double[] tSlope = belt(-.2, 195, -.1, .5), tPeak = belt(-.2, 230, -.1, -.5), tLow = belt(-.2, 140, -.95, .7);
        assertTrue(tSlope[0] > -.78 && tSlope[0] < -.375 && Math.abs(tSlope[1] - SnowMassifCompiler.W_HIGH) < 1e-6, "temperate flanks pick slopes");
        assertTrue(tPeak[0] < -.78 && Math.abs(tPeak[1] + 2 / 3.0) < 1e-6, "temperate summits pick peaks, sign kept");
        assertTrue(tLow[0] >= -.375, "no peak or slope rows below the belts"); assertEquals(.7, tLow[1], 1e-9, "lowland weirdness (rivers) untouched");
        assertEquals(-.37, belt(-.8, 110, -.95, .7)[0], 1e-9, "cold lowland: low erosion lifted out of the mountain rows");
        assertEquals(-.95, belt(-.8, 110, -.95, .1)[0], 1e-9, "valley band untouched (no rivers on dry ground)");
        assertEquals(.6, belt(-.2, 230, .6, .5)[0], 1e-9, "high erosion (no landscape mountains) untouched");
    }
    @Test void beltInputsAreContinuousAcrossTheGuard() {
        // Audit R6 BIO-01: no jump of the classifier inputs at |W| = 0.3 or at erosion 0.2.
        for (double h : new double[]{150, 190, 230}) for (double t : new double[]{-.8, -.2}) {
            double[] w0 = belt(t, h, -.4, .2999), w1 = belt(t, h, -.4, .3001);
            assertEquals(w0[0], w1[0], 2e-3, "erosion at |W| 0.3, h=" + h); assertEquals(w0[1], w1[1], 2e-3, "weirdness at |W| 0.3, h=" + h);
            double[] e0 = belt(t, h, .1999, .5), e1 = belt(t, h, .2001, .5);
            assertEquals(e0[0], e1[0], 2e-3, "erosion at E 0.2, h=" + h); assertEquals(e0[1], e1[1], 2e-3, "weirdness at E 0.2, h=" + h);
        }
    }
    @Test void upliftAddsTheMassifOnce() {
        String s = pack.get(SnowMassifCompiler.path(L + "uplift")).toString();
        assertTrue(s.contains("\"" + M + "massif\"") && s.contains("\"" + L + "giant\""), s);
    }
}
