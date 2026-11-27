package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Active, island and submarine volcanoes and rifts (user request 26.11, third iteration of docs/world-1.0.md). */
class LandformSitesTest {
    /** Active volcanoes with a lava crater on flat ground 70, searched over the cells of a seed. */
    private static List<Landforms.Volcano> lavaVolcanoes(Landforms m, Landforms.Ground ground, int cells) {
        List<Landforms.Volcano> out = new ArrayList<>();
        for (int j = -cells; j < cells; j++) for (int i = -cells; i < cells; i++) {
            var v = m.volcano(i, j, ground);
            if (v != null && v.lavaCrater()) out.add(v);
        }
        return out;
    }

    @Test void activeCraterHoldsAContainedCrustedLakeInItsPit() {
        Landforms.Ground flat = (x, z) -> 70;
        int checked = 0;
        for (long seed = 1; seed <= 6 && checked < 6; seed++) {
            Landforms m = new Landforms(seed);
            var cache = new Landforms.MapCache();
            for (var v : lavaVolcanoes(m, flat, 10)) {
                double level = m.lavaLevel(v, flat, cache);
                if (Double.isNaN(level)) continue;
                checked++;
                double centre = m.apply(v.x, v.z, 70, flat, cache);
                assertTrue(level >= centre + 3, "a lake at least three blocks deep: " + level + " over " + centre);
                // The lake stays in its pit: the ground round the pit stands two blocks above the lava.
                for (int k = 0; k < 48; k++) {
                    double a = k * Math.PI / 24, d = Math.min(v.pitRadius(a) + 3, v.craterR - 1);
                    int px = (int) Math.round(v.x + Math.cos(a) * d), pz = (int) Math.round(v.z + Math.sin(a) * d);
                    assertTrue(m.apply(px, pz, 70, flat, cache) >= level + 1.5, "the pit rim holds the lava");
                }
                // The pit is well inside the crater and the lake is partly crusted.
                assertTrue(v.pitR < 0.6 * v.craterR, "the pit is smaller than the crater");
                int crust = 0, n = 0;
                for (int dz = -(int) v.pitR; dz <= v.pitR; dz++) for (int dx = -(int) v.pitR; dx <= v.pitR; dx++) {
                    if (Math.hypot(dx, dz) > 0.8 * v.pitR) continue;
                    n++;
                    if (v.crust(v.x + dx, v.z + dz)) crust++;
                }
                double share = crust / (double) n;
                assertTrue(share > 0.15 && share < 0.9, "crust plates between glowing cracks: " + share);
            }
        }
        assertTrue(checked > 0, "lava lakes were found");
    }

    @Test void hotGroundAndNoBuildZonesBelongToTheLandforms() {
        Landforms.Ground flat = (x, z) -> 70;
        Landforms m = new Landforms(3);
        var cache = new Landforms.MapCache();
        int found = 0;
        for (long seed = 1; seed <= 8 && found == 0; seed++) {
            m = new Landforms(seed);
            for (var v : lavaVolcanoes(m, flat, 10)) {
                found++;
                List<Object> sites = m.near(v.x, v.z, flat, cache);
                assertTrue(LandformMaterials.hot(sites, v.x, v.z), "the summit crater is hot");
                assertTrue(LandformMaterials.noBuild(sites, v.x, v.z), "nothing is built on an active cone");
                int far = (int) (v.x + v.reach() + 200);
                List<Object> away = m.near(far, v.z, flat, cache);
                assertFalse(LandformMaterials.hot(away, far, v.z));
                assertFalse(LandformMaterials.noBuild(away, far, v.z));
                // Hot ground is the crater and narrow flows, not the whole cone.
                int hot = 0, all = 0;
                for (int k = 0; k < 720; k++) {
                    double a = k * Math.PI / 360, r = v.radius * 0.5;
                    int px = (int) Math.round(v.x + Math.cos(a) * r), pz = (int) Math.round(v.z + Math.sin(a) * r);
                    all++;
                    if (LandformMaterials.hot(m.near(px, pz, flat, cache), px, pz)) hot++;
                }
                assertTrue(hot > 0 && hot < all * 0.5, "flows streak the middle flanks: " + hot + " of " + all);
            }
        }
        assertTrue(found > 0, "an active volcano was found");
    }

    @Test void islandFlowsStayOnTheIsland() {
        Landforms.Ground sea = (x, z) -> 40;
        int islands = 0;
        for (long seed = 1; seed <= 12 && islands < 4; seed++) {
            Landforms m = new Landforms(seed);
            for (int j = -10; j < 10; j++) for (int i = -10; i < 10; i++) {
                var v = m.volcano(i, j, sea);
                if (v == null || !v.ocean || v.kind == Landforms.Kind.CINDER) continue;
                double shore = v.shore();
                assertTrue(shore >= 0 && shore <= 1, "shore share in [0, 1]: " + shore);
                if (shore <= 0) continue;
                islands++;
                // Beyond its shore the island's flows cover nothing (they crossed the sea onto another coast).
                for (int k = 0; k < 360; k++) {
                    double a = k * Math.PI / 180, u = Math.min(0.81, shore + 0.06);
                    if (u <= shore + 0.04) continue;
                    int px = (int) Math.round(v.x + Math.cos(a) * u * v.radius), pz = (int) Math.round(v.z + Math.sin(a) * u * v.radius);
                    assertEquals(LandformMaterials.Cover.NATIVE, LandformMaterials.flow(v, px, pz, u, true, 0.1, 0, 0.82));
                }
            }
        }
        assertTrue(islands > 0, "volcanic islands were found");
    }

    @Test void riftsStayOnLandAboveTheirFloorLimitAndVary() {
        // Land east of x = 0, sea to the west: no rift may run into the sea.
        Landforms.Ground coast = (x, z) -> x >= 0 ? 112 + 0.004 * x : 40;
        double minLen = Double.MAX_VALUE, maxLen = 0, minDepth = Double.MAX_VALUE, maxDepth = 0;
        int rifts = 0;
        for (long seed = 1; seed <= 10; seed++) {
            Landforms m = new Landforms(seed);
            for (int j = -6; j < 6; j++) for (int i = -6; i < 6; i++) {
                var rf = m.rift(i, j, coast);
                if (rf == null) continue;
                rifts++;
                minLen = Math.min(minLen, rf.length); maxLen = Math.max(maxLen, rf.length);
                minDepth = Math.min(minDepth, rf.depth); maxDepth = Math.max(maxDepth, rf.depth);
                for (double s = 0; s <= rf.length; s += 8) {
                    double[] p = rf.pointAt(s);
                    assertTrue(coast.at((int) Math.floor(p[0]), (int) Math.floor(p[1])) >= Landforms.RIFT_LAND,
                            "the rift line stays on land: " + p[0] + "," + p[1]);
                    assertTrue(rf.floorAt(s) >= Landforms.RIFT_FLOOR_MIN, "the floor stays far above the bedrock");
                }
            }
        }
        assertTrue(rifts >= 10, "rifts appear: " + rifts);
        assertTrue(maxLen > 2 * minLen, "short and long rifts: " + minLen + " .. " + maxLen);
        assertTrue(maxDepth > 1.6 * minDepth, "shallow and deep rifts: " + minDepth + " .. " + maxDepth);
    }

    @Test void riftCutsAMountainDownToTheLowGroundBesideIt() {
        // A ridge 160 blocks high along the middle of a row of rift cells (z0) on a plain at 90: a rift crossing it keeps
        // its floor near the plain's level.
        final int z0 = Landforms.RIFT_CELL / 2;
        Landforms.Ground ridge = (x, z) -> 90 + 160 * Math.exp(-(double) (z - z0) * (z - z0) / (2 * 150.0 * 150.0));
        for (long seed = 1; seed <= 40; seed++) {
            Landforms m = new Landforms(seed);
            var cache = new Landforms.MapCache();
            for (int j = -3; j < 3; j++) for (int i = -3; i < 3; i++) {
                var rf = m.rift(i, j, ridge);
                if (rf == null || rf.length < 1200) continue;
                double[] n = new double[3];
                for (double s = 0.2 * rf.length; s <= 0.8 * rf.length; s += 8) {
                    double[] p = rf.pointAt(s);
                    int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]);
                    if (Math.abs(pz - z0) > 60 || !rf.nearest(px, pz, n)) continue;
                    double floor = m.apply(px, pz, ridge.at(px, pz), ridge, cache);
                    // The floor follows the low ground on either side, so the cut through the crest is far deeper than
                    // the rift's own depth on flat ground.
                    assertTrue(ridge.at(px, pz) - floor > rf.depth + 20,
                            "the rift splits the ridge: floor " + floor + " under " + ridge.at(px, pz) + ", depth " + rf.depth);
                    return;
                }
            }
        }
        fail("no long rift crossed the ridge");
    }

    /** A narrow valley along z (sides 0.5 blocks per block, floor falling 0.01 to +z) and a stratovolcano 300 blocks to
     * its west, built directly. */
    private static final Landforms.Ground VALLEY = (x, z) -> 100 + 0.5 * Math.abs(x) - 0.01 * z;
    private static Landforms.Volcano valleyVolcano(boolean active) {
        double[][] none = {new double[0], new double[0], new double[0], new double[0]};
        return new Landforms.Volcano(-300, 0, Landforms.Kind.STRATO, 60, 300, 20, 12, 77, none, false, active,
                new GradientNoise(5), VALLEY.at(-300, 0));
    }

    @Test void lavaFlowsRunDownhillAndSpreadOnFlatGround() {
        Landforms m = new Landforms(9);
        var v = valleyVolcano(true);
        var f = m.traceFlow(v, VALLEY, 0, 900, 8, 7, true);
        assertNotNull(f, "a flow is traced");
        for (int i = 1; i < f.n; i++) assertTrue(f.zc[i] <= f.zc[i - 1] + 1.5, "the flow never climbs: " + f.zc[i - 1] + " -> " + f.zc[i]);
        // Narrow on the steep cone, wide on the gentle valley floor.
        double cone = Double.MAX_VALUE, floor = 0;
        for (int i = 0; i < f.n; i++) {
            if (Math.hypot(f.x[i] + 300, f.z[i]) < 150 && f.s[i] > 30) cone = Math.min(cone, f.w[i]);
            if (Math.abs(f.x[i]) < 8) floor = Math.max(floor, f.w[i]);
        }
        assertTrue(floor > 2 * cone, "the flow spreads on the valley floor: " + cone + " -> " + floor);
    }

    @Test void aLavaFlowDamsANarrowValley() {
        Landforms m = new Landforms(9);
        var v = valleyVolcano(true);
        var f = m.traceFlow(v, VALLEY, 0, 900, 8, 7, true);
        v.lavaFlows = new Landforms.Flow[]{f};
        // Where the flow comes down to the valley axis, the valley is closed across from side to side above the floor
        // 60 blocks upstream: the river behind it ponds (the drainage model makes a lake there).
        int ia = 0;
        for (int i = 0; i < f.n; i++) if (Math.abs(f.x[i]) < Math.abs(f.x[ia])) ia = i;
        int za = (int) Math.round(f.z[ia]);
        assertTrue(Math.abs(f.x[ia]) < 8, "the flow reaches the valley axis");
        double upstream = VALLEY.at(0, za - 60), crest = Double.MAX_VALUE;
        for (int x = -40; x <= 40; x++) crest = Math.min(crest, v.flowSurface(x, za, VALLEY.at(x, za)));
        assertTrue(crest > upstream + 2, "the flow dams the valley: crest " + crest + " over the floor upstream " + upstream);
        // It came down into the valley from the side and filled it from side to side: a dam at the valley entry.
        assertTrue(f.dammed() && Math.abs(f.damPoint()[0]) < 40, "a dam where the flow enters the valley");
        // On an open slope (no valley sides rising above the crest) a flow makes no dam.
        Landforms.Ground slope = (x, z) -> 200 - 0.3 * x;
        var open = m.traceFlow(valleyVolcano(true), slope, 0, 600, 8, 7, true);
        assertTrue(open == null || !open.dammed(), "no dam on an open slope");
    }


    /** Fifth iteration (audits 26.11 Opus N-1/N-3/N-9, KO-01/KO-03/KO-08) and sixth (audit 27.11 Opus N-2, KO-02/KO-03). */
    @Test void riftsKeepAwayFromLowGroundBesideThem() {
        // Land at Y100 with diagonal strips of ground below the sea (Y55), 60 blocks wide every 700 blocks: the dry air of
        // a rift beside sea-filled low ground stood as a wall of water.
        Landforms.Ground striped = (x, z) -> Math.floorMod(x + z, 700) < 60 ? 55 : 100;
        int rifts = 0;
        for (long seed = 1; seed <= 12; seed++) {
            Landforms m = new Landforms(seed);
            for (int j = -4; j < 4; j++) for (int i = -4; i < 4; i++) {
                var rf = m.rift(i, j, striped);
                if (rf == null) continue;
                rifts++;
                for (double s = 0; s <= rf.length; s += 4) {
                    double[] p = rf.pointAt(s), q = rf.pointAt(Math.min(rf.length, s + 1));
                    double tx = q[0] - p[0], tz = q[1] - p[1], tl = Math.max(1e-6, Math.hypot(tx, tz));
                    for (double o = -(rf.width / 2 + 30); o <= rf.width / 2 + 30; o += 4) {
                        int x = (int) Math.floor(p[0] - tz / tl * o), z = (int) Math.floor(p[1] + tx / tl * o);
                        assertTrue(striped.at(x, z) >= Landforms.SEA + 1, "low ground beside a rift at " + x + "," + z + " (seed " + seed + ")");
                    }
                }
            }
        }
        assertTrue(rifts >= 3, "rifts appear between the strips: " + rifts);
    }

    @Test void closedCraterAndCalderaBowlsHoldNoSea() {
        Landforms m = new Landforms(5);
        Landforms.Ground low = (x, z) -> 66, coast = (x, z) -> x > 60 ? 50 : 66;
        var crater = new Landforms.Crater(0, 0, 100, 30, 6, Landforms.Age.FRESH, false);
        assertTrue(m.closedBowl(List.of(crater), 0, 0, low, null), "a crater bowl below the sea, its rim above it all round");
        assertFalse(m.closedBowl(List.of(crater), 0, 150, low, null), "outside the bowl");
        var open = new Landforms.Crater(0, 0, 100, 30, 6, Landforms.Age.FRESH, false);
        assertFalse(m.closedBowl(List.of(open), 0, 0, coast, null), "a bowl open to the sea stays sea");
        double[][] none = {new double[0], new double[0], new double[0], new double[0]};
        var caldera = new Landforms.Volcano(0, 0, Landforms.Kind.CALDERA, 60, 600, 250, 70, 7, none, false, false, new GradientNoise(5), 66);
        assertTrue(m.closedBowl(List.of(caldera), 0, 0, low, null), "a caldera floor below the sea inside a closed ring");
        var openCaldera = new Landforms.Volcano(0, 0, Landforms.Kind.CALDERA, 60, 600, 250, 70, 7, none, false, false, new GradientNoise(5), 66);
        Landforms.Ground breached = (x, z) -> x > 200 ? 40 : 66;
        assertFalse(m.closedBowl(List.of(openCaldera), 0, 0, breached, null), "a caldera ring broken to the sea");
    }

    @Test void aConeNeverLowersTheGroundItStandsOn() {
        // A cone in a valley at Y66 between plateaus at Y178: the old damping cut a moat into the plateaus round it.
        Landforms m = new Landforms(3);
        Landforms.Ground valley = (x, z) -> 66 + 112 * Landforms.smooth(150, 450, Math.abs(x));
        double[][] none = {new double[0], new double[0], new double[0], new double[0]};
        for (Landforms.Kind kind : new Landforms.Kind[]{Landforms.Kind.STRATO, Landforms.Kind.SHIELD, Landforms.Kind.CALDERA}) {
            var v = new Landforms.Volcano(0, 0, kind, 147, 827, kind == Landforms.Kind.CALDERA ? 200 : 60, 20, 11, none, false, false, new GradientNoise(3), 66);
            for (int x = -820; x <= 820; x += 5) {
                if (Math.abs(x) <= v.craterR + 2) continue;
                double h = valley.at(x, 0);
                assertTrue(m.volcano(v, x, 0, h, 66) >= h - 1e-9, kind + " lowers the ground at x " + x);
            }
        }
    }
}
