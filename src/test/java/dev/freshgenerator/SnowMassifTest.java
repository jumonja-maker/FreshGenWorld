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
        v.put(L + "climate/surface_temperature", zoneT);
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
        assertEquals(-.95, belt(-.8, 110, -.95, .03)[0], 1e-9, "valley band untouched (no rivers on dry ground)");
        assertEquals(-.37, belt(-.2, 90, -.85, -.27)[0], 1e-9, "low weirdness band: no grove at forest height (R7, seed 123456789 X-7493)");
        // Own audit 21.11: just outside the valley slice (|W| 0.05..0.1) grove and snowy slopes stood below Y90.
        for (double w : new double[]{.051, .06, .087, -.072}) assertEquals(-.37, belt(-.8, 70, -.5, w)[0], 1e-9, "low slice edge |W|=" + w);
        assertEquals(.6, belt(-.2, 150, .6, .5)[0], 1e-9, "high erosion below the belts untouched");
        double[] flat = belt(-.2, 190, .6, .5);
        assertTrue(flat[0] > -.78 && flat[0] < -.375, "a high plateau of any erosion is in the slope belt: " + flat[0]);
    }
    @Test void warmMountainsKeepTheirRockPeaks() {
        // stony_peaks (lookup T 0.2..0.55) has no snow line: its peak contour is WARM_PEAK_SHIFT lower.
        double h = SnowMassifCompiler.BELT_PEAK - SnowMassifCompiler.WARM_PEAK_SHIFT + 2;
        assertEquals(0, row(belt(.4, h, -.5, .65)[0]), "warm peak rows from about Y157");
        assertEquals(2 / 3.0, belt(.4, h, -.5, .65)[1], 1e-6);
        assertEquals(2, row(belt(.4, 140, -.9, .65)[0]), "no warm peaks at forest height");
        assertEquals(2, row(belt(.1, h, -.9, .65)[0]), "temperate (snowy) mountains: no peaks below the snow-line belts");
    }
    @Test void beltRowsNeverReturnDownhillAsTheSurfaceRises() {
        // Audit R8 D-01: with fixed regional inputs a higher surface must not switch the peak belt off again.
        for (double zone = -.9; zone <= .6; zone += .05) for (double e = -1; e <= .6; e += .2) for (double w : new double[]{.35, .5, .65, -.8}) {
            int last = 2;
            for (double h = 100; h <= 300; h += 1) {
                int r = row(belt(zone, h, e, w)[0]);
                assertTrue(r <= last, "row rose back at h=" + h + " zone=" + zone + " e=" + e + " w=" + w);
                last = r;
            }
        }
    }
    static double[] routed(double zoneT, double h, double erosion, double ridges, double depthBlocks) {
        var v = new HashMap<String, Double>();
        v.put(L + "climate/zone_temperature", zoneT); v.put(L + "climate/surface_temperature", zoneT); v.put(P + "coherent/height", h);
        v.put(L + "climate/coast_erosion", erosion); v.put(P + "erosion", erosion); v.put(P + "ridges", ridges);
        v.put(P + "coherent/depth_blocks", depthBlocks);
        return new double[]{KarstCaveTest.eval(new JsonPrimitive(M + "routed_erosion"), v), KarstCaveTest.eval(new JsonPrimitive(M + "routed_ridges"), v)};
    }
    @Test void beltsFadeWithDepthLikeTheOtherLookupAxes() {
        // Audit R8 D-02: deep cave biomes and the aquifer dry guard read the raw E/W again below the surface band.
        double[] surface = belt(.4, 160, .7, -.9), top = routed(.4, 160, .7, -.9, 0), deep = routed(.4, 160, .7, -.9, 128);
        assertEquals(surface[0], top[0], 1e-9); assertEquals(surface[1], top[1], 1e-9);
        assertEquals(.7, deep[0], 1e-9, "deep erosion is the raw field (aquifer exclusion input)");
        assertEquals(-.9, deep[1], 1e-9, "deep weirdness is the raw field");
        double prev = top[0];
        for (double d = 0; d <= 80; d += 2) {
            double e = routed(.4, 160, .7, -.9, d)[0];
            assertTrue(e >= prev - 1e-9, "monotone fade at depth " + d); prev = e;
        }
    }
    /** Native row of an erosion value: 0 peaks, 1 slopes, 2 the rows below. */
    static int row(double e) { return e < -.78 ? 0 : e < -.375 ? 1 : 2; }
    @Test void beltsFollowHeightContoursForAnyErosion() {
        // Audit R7 BIO-02 witnesses (seed 0): jagged_peaks | pale_garden at Y151, snowy_slopes | cherry_grove at Y169.7.
        assertEquals(2, row(belt(-.2, 151.53, -.3805, -.6395)[0]), "no jagged peaks at forest height");
        assertEquals(2, row(belt(-.2, 151.53, -.37, -.649)[0]));
        double[] a = belt(-.2, 169.71, -.3767, .4972), b = belt(-.2, 169.71, -.3741, .4996);
        assertEquals(row(a[0]), row(b[0]), "one height, one belt"); assertEquals(a[1], b[1], 1e-9);
        double lo = SnowMassifCompiler.BELT_SLOPE - SnowMassifCompiler.BELT_EDGE, hi = SnowMassifCompiler.BELT_SLOPE + SnowMassifCompiler.BELT_EDGE;
        double pLo = SnowMassifCompiler.BELT_PEAK - SnowMassifCompiler.BELT_EDGE, pHi = SnowMassifCompiler.BELT_PEAK + SnowMassifCompiler.BELT_EDGE;
        for (double w : new double[]{.45, .55, .65, .8, -.5, -.7}) for (double e = -1; e <= 1; e += .05) {
            for (double h : new double[]{100, 140, lo - .01}) assertEquals(2, row(belt(-.2, h, e, w)[0]), "below the slope contour h=" + h + " e=" + e);
            for (double h : new double[]{hi + .01, 190, pLo - .01}) {
                double[] r = belt(-.2, h, e, w);
                assertEquals(1, row(r[0]), "slope belt h=" + h + " e=" + e);
                assertEquals(Math.signum(w) * SnowMassifCompiler.W_HIGH, r[1], 1e-6, "slopes, not peaks, on the slope rows");
            }
            for (double h : new double[]{pHi + .01, 240}) assertEquals(0, row(belt(-.2, h, e, w)[0]), "peak belt h=" + h + " e=" + e);
            // Cold regions: the same contours 25 blocks lower.
            assertEquals(1, row(belt(-.8, hi + .01 - SnowMassifCompiler.BELT_COLD_SHIFT, e, w)[0]), "cold slope belt e=" + e);
        }
    }
    @Test void beltInputsAreContinuousAcrossTheGuard() {
        // Audit R6 BIO-01: no jump of the classifier inputs at any guard edge (|W| 0.05 and 0.3,
        // erosion at the floor, height at the slope contour).
        double edge = SnowMassifCompiler.BELT_SLOPE - SnowMassifCompiler.BELT_EDGE, wedge = SnowMassifCompiler.BELT_SLOPE - 3 * SnowMassifCompiler.BELT_EDGE;
        for (double h : new double[]{120, 150, 190, 230}) for (double t : new double[]{-.8, -.2}) for (double e : new double[]{-.9, -.5, .1, .6}) {
            for (double w : new double[]{.3}) {
                double[] w0 = belt(t, h, e, w - 1e-4), w1 = belt(t, h, e, w + 1e-4);
                assertEquals(w0[0], w1[0], 2e-3, "erosion at |W| " + w + ", h=" + h + " e=" + e); assertEquals(w0[1], w1[1], 2e-3, "weirdness at |W| " + w);
            }
            double[] f0 = belt(t, h, SnowMassifCompiler.FLOOR_E - 1e-4, .5), f1 = belt(t, h, SnowMassifCompiler.FLOOR_E + 1e-4, .5);
            assertEquals(f0[0], f1[0], 2e-3, "erosion at the floor guard, h=" + h);
        }
        for (double e : new double[]{-.9, -.2, .6}) for (double w : new double[]{.2, .5}) {
            assertEquals(belt(-.2, edge - 1e-4, e, w)[0], belt(-.2, edge + 1e-4, e, w)[0], 2e-3, "erosion at the height guard");
            assertEquals(belt(-.2, wedge - 1e-4, e, w)[1], belt(-.2, wedge + 1e-4, e, w)[1], 2e-3, "weirdness at the height guard");
        }
    }
    @Test void upliftAddsTheMassifOnce() {
        String s = pack.get(SnowMassifCompiler.path(L + "uplift")).toString();
        assertTrue(s.contains("\"" + M + "massif\"") && s.contains("\"" + L + "giant\""), s);
    }
}
