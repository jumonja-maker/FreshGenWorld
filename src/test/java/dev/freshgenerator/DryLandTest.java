package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.core.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.9.2 (audit "build10"): hot dry lowland is a dry pan, not sea-level puddles; trees agree with the
 * biome humidity; forest neighbour compatibility is continuous; the dead surface setting is gone. */
class DryLandTest {
    static final String P = DryLandCompiler.P, L = DryLandCompiler.L, CL = DryLandCompiler.CL;
    static Map<String, JsonElement> pack;
    @BeforeAll static void compile() throws Exception { pack = WorldgenCatalog.compile(new VanillaAssets()); KarstCaveTest.pack = pack; }

    static double floor(double zoneT, double m, double c, double w, double e, double h) { return floor(zoneT, m, c, w, e, h, 62.8); }
    static double floor(double zoneT, double m, double c, double w, double e, double h, double nativeHeight) {
        var v = new HashMap<String, Double>(); v.put(L + "native_height", nativeHeight);
        v.put(CL + "zone_temperature", zoneT); v.put(CL + "biome_moisture", m); v.put(P + "continents", c);
        v.put(P + "ridges", w); v.put(P + "erosion", e); v.put(L + "height_limited", h);
        return KarstCaveTest.eval(new JsonPrimitive(L + "dry_floor"), v);
    }
    @Test void hotDryFlatsBecomeDryPans() {
        // Seed 8675309 X-1472 Z-6304: desert over 1-2 blocks of sea-level water.
        for (double h = 55; h < 63.5; h += .5) {
            double lifted = floor(.6, -.3, .2, .5, .2, h);
            assertTrue(lifted >= 63.5 && lifted <= DryLandCompiler.TOP, "desert flat h=" + h + ": " + lifted);
            assertTrue(floor(.3, -.3, .2, .5, .2, h) >= 63.5, "dry savanna flat h=" + h);
        }
    }
    @Test void waterThatBelongsThereStays() {
        for (double h = 50; h < 63.5; h += 1) {
            assertEquals(h, floor(0, -.3, .2, .5, .2, h), 1e-9, "temperate lowland stays vanilla-like");
            assertEquals(h, floor(.3, .3, .2, .5, .2, h), 1e-9, "humid warm lowland (jungle, mangrove) stays");
            assertEquals(h, floor(.6, -.3, .2, .02, .2, h), 1e-9, "river valley keeps its water");
            assertEquals(h, floor(.6, -.3, .2, .5, .5, h), 1e-9, "mangrove/swamp row (and its edge) keeps its water");
            assertEquals(h, floor(.6, -.3, -.3, .5, .2, h), 1e-9, "ocean and coastal band untouched");
        }
        assertEquals(45, floor(.6, -.3, .2, .5, .2, 45), 1e-9, "real basins (below H50) keep their water");
        // Probe 21.11 (seed -5714217828218873590, X-3664 Z6432): flooded savanna at |W| 0.061, E 0.218, native 60.4.
        assertTrue(floor(.535, -.192, .448, .061, .218, 60.6, 60.4) >= 63.5, "floodplain flat beside a river valley is a dry pan");
        for (double h = DryLandCompiler.TOP; h < 200; h += 7) assertEquals(h, floor(.6, -.3, .2, .5, .2, h), 1e-9, "higher ground untouched");
        // Realism audit A092-02: relief kept (no single level at Y64): a 6-block difference stays above 1.5 blocks.
        assertTrue(floor(.6, -.3, .2, .5, .2, 63) - floor(.6, -.3, .2, .5, .2, 57) > 1.5, "low relief is compressed, not erased");
    }
    @Test void liftNeverFallsBelowTheFirstVersion() {
        // Updated realism audit A092-05: seed 8675309 X-4279 Z-6764, desert lagoon edge flooded by the flatter curve
        // (H below 56.98 with the same weight). Every weight and height must stay at or above the first lift.
        for (double e : new double[]{.2, .42, .45, .48}) for (double w : new double[]{.5, .055, .058})
            for (double h = 49; h < DryLandCompiler.TOP; h += .05) {
                double lifted = floor(.6, -.3, .2, w, e, h), weight = weight(.6, -.3, .2, w, e, h);
                double first = h < 64.64 ? h + weight * .9375 * (64.64 - h) : h;
                assertTrue(lifted >= first - 1e-9, "h=" + h + " w=" + w + " e=" + e + ": " + lifted + " < " + first);
            }
        assertTrue(floor(.6, -.3, .2, .5, .2, 55) >= 64.0, "fully weighted lagoon floor is dry");
    }
    static double weight(double zoneT, double m, double c, double w, double e, double h) {
        var v = new HashMap<String, Double>(); v.put(L + "native_height", 62.8);
        v.put(CL + "zone_temperature", zoneT); v.put(CL + "biome_moisture", m); v.put(P + "continents", c);
        v.put(P + "ridges", w); v.put(P + "erosion", e); v.put(L + "height_limited", h);
        return KarstCaveTest.eval(new JsonPrimitive(L + "dry_weight"), v);
    }
    @Test void liftIsContinuousAndMonotone() {
        double prev = -1e9;
        for (double h = 40; h <= 70; h += .01) {
            double v = floor(.6, -.3, .2, .5, .2, h);
            assertTrue(v >= prev - 1e-9, "no inversion at h=" + h); prev = v;
        }
        assertEquals(floor(.6, -.3, .2, .5, .2, DryLandCompiler.TOP - 1e-6), floor(.6, -.3, .2, .5, .2, DryLandCompiler.TOP + 1e-6), 1e-4);
        assertTrue(pack.get(DryLandCompiler.path(P + "coherent/height")).toString().contains("\"" + L + "dry_floor\""));
        assertFalse(pack.get(VanillaAssets.path("material_rule", P + "surface")).toString().contains("continentality"), "T01: no dead setting");
    }
    @Test void forestHumidityFollowsTheBiomeLookup() {
        // Audit F04: a savanna picked by the dry belt (lookup -0.15) grew trees for the local +0.90.
        var v = new HashMap<String, Double>();
        v.put(P + "vegetation", .9); v.put(CL + "biome_moisture", .8); v.put(CL + "surface_temperature", .52); v.put(P + "coherent/height", 70.0);
        double forest = KarstCaveTest.eval(new JsonPrimitive(CL + "forest_moisture"), v);
        double lookup = KarstCaveTest.eval(new JsonPrimitive(CL + "frozen_moisture"), v);
        assertEquals(.9 + lookup - .8, forest, 1e-9);
        assertTrue(forest < .0, "dry-belt savanna is dry for the forest model too: " + forest);
        v.put(CL + "surface_temperature", .0);
        assertEquals(.9, KarstCaveTest.eval(new JsonPrimitive(CL + "forest_moisture"), v), 1e-9, "no correction: the local field stays");
    }
    @Test void forestWeightsStayValidAtFloatRampEdges() {
        // Realism audit 0.9.2 A092-05: T = (double)(-0.4f) gave a compatibility of 1.000000000000001 and
        // ForestEcotone.blend threw "Invalid ecotone weight" (forest beside taiga, H90).
        var forest = new ForestEcotone.Pool("minecraft:forest", 10.1); var taiga = new ForestEcotone.Pool("minecraft:taiga", 10.1);
        for (int i = -4000; i <= 4000; i++) {
            float tf = -0.4f + i * 1e-5f;
            var site = new ForestEcology.Site(90, 63, .05, 0, (double) tf, 0, 0, .5);
            double c = ForestEcotone.compatibility("minecraft:forest", "minecraft:taiga", site);
            assertTrue(c >= 0 && c <= 1, "compatibility in [0,1] at T=" + tf + ": " + c);
            for (var share : ForestEcotone.blend(8, 8, forest, site, (x, z) -> taiga)) assertTrue(share.weight() > 0 && share.weight() <= 1);
        }
    }
    @Test void forestCompatibilityHasNoJump() {
        // Audit F06: dry woodland next to temperate forest at moisture 0.199999 vs 0.2.
        var a = new ForestEcology.Site(90, 63, .1, -.3, .2, .199999, 0, .5);
        var b = new ForestEcology.Site(90, 63, .1, -.3, .2, .200000, 0, .5);
        double ca = ForestEcotone.compatibility("minecraft:savanna", "minecraft:forest", a), cb = ForestEcotone.compatibility("minecraft:savanna", "minecraft:forest", b);
        assertEquals(ca, cb, 1e-3);
        for (double m = .1; m <= .3; m += .001) {
            ForestEcology.Site s1 = new ForestEcology.Site(90, 63, .1, -.3, .2, m, 0, .5), s2 = new ForestEcology.Site(90, 63, .1, -.3, .2, m + .001, 0, .5);
            assertEquals(ForestEcotone.compatibility("minecraft:savanna", "minecraft:forest", s1),
                    ForestEcotone.compatibility("minecraft:savanna", "minecraft:forest", s2), .06, "smooth ramp at m=" + m);
        }
        assertEquals(1, ForestEcotone.compatibility("minecraft:savanna", "minecraft:forest", new ForestEcology.Site(90, 63, .1, -.3, .2, 0, 0, .5)), 1e-9);
        assertEquals(0, ForestEcotone.compatibility("minecraft:savanna", "minecraft:forest", new ForestEcology.Site(90, 63, .1, -.3, .2, .4, 0, .5)), 1e-9);
        assertEquals(0, ForestEcotone.compatibility("minecraft:mangrove_swamp", "minecraft:jungle", a), 1e-9, "identities stay hard");
    }
}
