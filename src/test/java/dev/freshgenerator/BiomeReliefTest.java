package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BiomeReliefTest {
    static final String P = BiomeReliefCompiler.P, R = BiomeReliefCompiler.R;
    static Map<String, JsonElement> pack;
    @BeforeAll static void compile() throws Exception { pack = WorldgenCatalog.compile(new VanillaAssets()); KarstCaveTest.pack = pack; }

    static double relief(double t, double e, double c, double p, double mesa, double hills) {
        var v = new HashMap<String, Double>(Map.of(P + "landscape/climate/zone_temperature", t, P + "erosion", e, P + "continents", c,
            P + "ridges_folded", p, "noise:" + R + "mesa", mesa, "noise:" + R + "hills", hills));
        return KarstCaveTest.eval(new JsonPrimitive(R + "biome_relief"), v);
    }
    @Test void badlandsGetSteppedMesasWithFlatBenches() {
        double[] bench = {0, 24, 50, 80};
        assertEquals(bench[0], relief(.8, -.2, .4, 0, -.6, -1), 1e-6);
        assertEquals(bench[1], relief(.8, -.2, .4, 0, .05, -1), 1e-6);
        assertEquals(bench[2], relief(.8, -.2, .4, 0, .35, -1), 1e-6);
        assertEquals(bench[3], relief(.8, -.2, .4, 0, .9, -1), 1e-6);
        // Benches are flat over a range of the field; steps rise monotonically.
        assertEquals(relief(.8, -.2, .4, 0, -.03, -1), relief(.8, -.2, .4, 0, .13, -1), 1e-6);
        double previous = -1;
        for (double m = -1; m <= 1; m += .005) { double r = relief(.8, -.2, .4, 0, m, -1); assertTrue(r >= previous - 1e-9); previous = r; }
    }
    @Test void mesasStayOutOfDesertsMountainCoresCoastsRiversAndCoolClimates() {
        assertEquals(0, relief(.8, .25, .4, 0, .9, -1), 1e-6, "desert erosion band");
        assertEquals(0, relief(.8, -.85, .4, 0, .9, -1), 1e-6, "mountain core");
        assertEquals(0, relief(.8, -.2, -.1, 0, .9, -1), 1e-6, "coast/ocean");
        assertEquals(0, relief(.8, -.2, .4, -.95, .9, -1), 1e-6, "river valley");
        assertEquals(0, relief(.3, -.2, .4, 0, .9, -1), 1e-6, "temperate climate");
    }
    @Test void windsweptSliceGetsRoundedHillsAndReliefNeverLowersGround() {
        assertEquals(44, relief(.0, .49, .4, 0, -1, .9), 1e-6);
        assertEquals(0, relief(.0, .49, .4, 0, -1, -.7), 1e-6);
        assertEquals(0, relief(.0, .2, .4, 0, -1, .9), 1e-6, "outside the windswept band");
        for (double t : new double[]{-1, .3, .6, 1}) for (double e = -1; e <= 1; e += .05) for (double m : new double[]{-1, 0, .5, 1})
            assertTrue(relief(t, e, .4, 0, m, m) >= 0);
    }
    @Test void landscapeRootIsTheOldRootPlusReliefAndIsReadOnce() {
        var root = pack.get(BiomeReliefCompiler.path(P + "landscape/height_unguarded")).getAsJsonObject().getAsJsonObject("input");
        // 0.9.1: the ocean floor layer reads the root once in ocean/raw = root + ocean_relief
        // and chooses a soft ceiling of it on the open ocean.
        if ("minecraft:range_choice".equals(root.get("type").getAsString()))
            root = pack.get(BiomeReliefCompiler.path(OceanFloorCompiler.O + "raw")).getAsJsonObject().getAsJsonObject("input").getAsJsonObject("left");
        assertEquals("minecraft:add", root.get("type").getAsString());
        assertEquals(R + "biome_relief", root.get("right").getAsString());
        long reads = pack.values().stream().filter(v -> v.toString().contains("\"" + P + "landscape/height_unguarded\"")).count();
        assertEquals(1, reads);
        assertFalse(pack.get(BiomeReliefCompiler.path(R + "biome_relief")).toString().contains("coherent/height"), "no circular height dependency");
    }
}
