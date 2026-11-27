package dev.freshgenerator;

import dev.freshgenerator.core.road.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Village roads (user request 24.11) on synthetic land: a straight-ish worn path over a plain, one bridge over a river,
 * a detour round a lake, no road over the sea, a winding road (or none) up a steep slope, the same road every time. */
class RoadRouterTest {
    static final long SEED = 20261124L;
    static final RoadRouter.Blocked NONE = (x, z) -> false;

    static RoadTerrain land(java.util.function.ToDoubleBiFunction<Integer, Integer> ground) {
        return (x, z, s) -> { s.ground = ground.applyAsDouble(x, z); s.kind = RoadTerrain.DRY; s.water = RoadTerrain.NO_WATER; s.width = 0; };
    }
    static RoadLine plan(RoadTerrain t, int x0, int z0, int x1, int z1) { return plan(7, t, x0, z0, x1, z1); }
    static RoadLine plan(long id, RoadTerrain t, int x0, int z0, int x1, int z1) {
        return new RoadRouter(t, NONE).plan(id, SEED, List.of(new RoadRouter.Exit(x0, z0, 0)), List.of(new RoadRouter.Exit(x1, z1, 0)),
                x0, z0, x1, z1, RoadGraph.FACTOR * Math.hypot(x1 - x0, z1 - z0) + 2 * RoadGraph.REACH);
    }

    @Test void aPlainGivesAWornPathWithGaps() {
        RoadLine r = plan(land((x, z) -> 64.3), 0, 0, 400, 40);
        assertNotNull(r);
        assertTrue(r.length() > 395 && r.length() < 470, "near the straight line: " + r.length());
        assertTrue(r.bridges.isEmpty());
        // Breaks of 10-30 blocks on most roads (a seeded chance per stretch).
        int broken = 0;
        for (long id = 1; id <= 12; id++) {
            RoadLine q = plan(id, land((x, z) -> 64.3), 0, 0, 400, 40);
            int run = 0, longest = 0, gaps = 0;
            for (int k = 0; k < q.length(); k++) {
                if (q.density[k] == 0) { run++; longest = Math.max(longest, run); } else { if (run > 0) gaps++; run = 0; }
            }
            if (gaps > 0) broken++;
            assertTrue(longest <= 30, "a gap of at most ~30 blocks: " + longest);
        }
        assertTrue(broken >= 7, "most roads break off somewhere: " + broken);
        for (int k = 0; k < 20; k++) assertTrue(r.density[k] > 0 && r.density[r.length() - 1 - k] > 0, "painted at the villages");
        for (int k = 0; k < r.length(); k++) assertTrue(r.half[k] >= 0.5f && r.half[k] <= 1.8f);
    }

    @Test void aRiverIsCrossedOnceByABridge() {
        RoadTerrain t = (x, z, s) -> {
            boolean river = x >= 200 && x <= 207;
            s.ground = river ? 59 : 64.2; s.kind = river ? RoadTerrain.RIVER : RoadTerrain.DRY;
            s.water = river ? 62 : RoadTerrain.NO_WATER; s.width = river ? 8 : 0;
        };
        RoadLine r = plan(t, 0, 0, 400, 30);
        assertNotNull(r);
        assertEquals(1, r.bridges.size(), "one crossing");
        Bridge b = r.bridges.get(0);
        assertTrue(b.alongX && b.lo <= 200 && b.hi >= 207 && b.hi - b.lo <= 9, b.toString());
        assertEquals(62, b.water);
    }

    /** Code audit 26.11 OpenAI R01: over a river the line runs on the deck, never on the water beside it. */
    @Test void aRoadAcrossARiverAtAnAngleRunsOnTheDeck() {
        RoadTerrain t = (x, z, s) -> {
            boolean river = x >= 200 && x <= 207;
            s.ground = river ? 59 : 64.2; s.kind = river ? RoadTerrain.RIVER : RoadTerrain.DRY;
            s.water = river ? 62 : RoadTerrain.NO_WATER; s.width = river ? 8 : 0;
        };
        RoadLine r = plan(t, 0, 0, 400, 160);
        assertNotNull(r);
        assertFalse(r.bridges.isEmpty(), "a bridge");
        for (int k = 0; k < r.length(); k++) {
            int bx = (int) Math.floor(r.x[k]), bz = (int) Math.floor(r.z[k]);
            if (bx < 200 || bx > 207) continue;
            assertTrue(r.bridges.stream().anyMatch(b -> b.contains(bx, bz)), "on the water beside the deck at " + bx + "," + bz);
        }
    }

    @Test void aLakeIsWalkedRound() {
        RoadTerrain t = (x, z, s) -> {
            boolean lake = Math.hypot(x - 200, z) < 80;
            s.ground = lake ? 55 : 64.2; s.kind = lake ? RoadTerrain.LAKE : RoadTerrain.DRY;
            s.water = lake ? 62 : RoadTerrain.NO_WATER; s.width = 0;
        };
        RoadLine r = plan(t, 0, 0, 400, 0);
        assertNotNull(r);
        for (int k = 0; k < r.length(); k++) assertTrue(Math.hypot(r.x[k] - 200, r.z[k]) > 76, "on the lake at " + k);
        assertTrue(r.bridges.isEmpty());
    }

    @Test void noRoadOverTheSea() {
        RoadTerrain t = (x, z, s) -> {
            boolean sea = x >= 150 && x <= 260;
            s.ground = sea ? 50 : 64.2; s.kind = sea ? RoadTerrain.SEA : RoadTerrain.DRY;
            s.water = sea ? 62 : RoadTerrain.NO_WATER; s.width = 0;
        };
        assertNull(plan(t, 0, 0, 400, 0), "an island village has no road");
    }

    @Test void aSteepSlopeGetsAWindingRoadWithinTheGradeLimit() {
        RoadTerrain t = land((x, z) -> 64 + Math.max(0, Math.min(120, (x - 100) * 0.6)));
        RoadLine r = plan(t, 0, 0, 420, 0);
        assertNotNull(r, "a winding road fits the corridor");
        double zmin = Double.MAX_VALUE, zmax = -Double.MAX_VALUE;
        for (double z : r.z) { zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
        assertTrue(zmax - zmin > 60, "the road winds across the slope: " + (zmax - zmin));
        assertTrue(r.length() > 1.25 * 420, "longer than the straight line: " + r.length());
        // The finished line is checked block by block: where the path is painted it keeps the line limit (points one
        // block apart along the road; audit 25.11 D-1: the lattice limit alone gave 0.44).
        for (int k = 16; k < r.length(); k++) {
            if (r.density[k] == 0 || r.density[k - 16] == 0) continue;
            double g = Math.abs(slope(r.x[k]) - slope(r.x[k - 16])) / 16;
            assertTrue(g <= RoadRouter.LINE_GRADE + 0.03, "grade " + g + " at " + k);
        }
        // Too steep for any road inside the corridor: a cliff.
        assertNull(plan(land((x, z) -> 64 + Math.max(0, Math.min(150, (x - 150) * 3.0))), 0, 0, 300, 0), "no road up a cliff");
    }
    private static double slope(double x) { return 64 + Math.max(0, Math.min(120, (x - 100) * 0.6)); }

    @Test void theSameRoadEveryTime() {
        RoadTerrain t = land((x, z) -> 64 + 6 * Math.sin(x / 50.0) * Math.cos(z / 70.0));
        RoadLine a = plan(t, 0, 0, 500, 120), b = plan(t, 0, 0, 500, 120);
        assertArrayEquals(a.x, b.x); assertArrayEquals(a.z, b.z); assertArrayEquals(a.density, b.density); assertArrayEquals(a.half, b.half);
    }

    @Test void bridgesComeInSeveralKindsAndStatesAndStandAboveTheWater() {
        Set<String> seen = new TreeSet<>();
        for (int v = 0; v < 7; v++) {
            int width = new int[]{2, 3, 6, 12, 26, 2, 5}[v];
            double bed = v >= 5 ? 61.5 : 60; // shallow streams for stepping stones
            RoadTerrain t = (x, z, s) -> {
                boolean river = x >= 100 && x < 100 + width;
                s.ground = river ? bed : 64.2; s.kind = river ? RoadTerrain.RIVER : RoadTerrain.DRY;
                s.water = river ? 62 : RoadTerrain.NO_WATER; s.width = river ? width : 0;
            };
            for (long seed = 0; seed < 120; seed++) {
                Bridge b = Bridge.plan(t, 100 + width / 2.0, 0, 1, 0.1, seed, RoadRouter.MAX_BRIDGE);
                assertNotNull(b);
                seen.add(b.type + "/" + b.state);
                int[] box = b.box();
                int[] deck = {0};
                for (int x = box[0]; x <= box[2]; x++) for (int z = box[1]; z <= box[3]; z++) {
                    final int fx = x;
                    Set<Integer> floors = new HashSet<>(), rails = new HashSet<>();
                    b.column(x, z, (y, role, variant, dir, links) -> {
                        if (role == Bridge.DECK && fx >= b.lo && fx <= b.hi) { deck[0]++; assertTrue(y >= b.water, "deck above the water"); }
                        if (role == Bridge.STAIR || role == Bridge.ARCH) assertTrue(dir == 1 || dir == -1);
                        if (role == Bridge.DECK || role == Bridge.STAIR) floors.add(y);
                        if (role == Bridge.RAIL) rails.add(y);
                    });
                    for (int ry : rails) assertTrue(floors.contains(ry - 1), "a rail stands on the deck, not over a hole: " + b + " at " + x + "," + z);
                }
                if (b.state == Bridge.State.INTACT && b.type != Bridge.Type.LOG && b.type != Bridge.Type.STEPPING)
                    assertTrue(deck[0] >= width, "an intact deck covers the water: " + b);
            }
        }
        for (String k : List.of("LOG/INTACT", "LOG/RUINED", "STEPPING/INTACT", "BOARDWALK/WORN", "WOOD/INTACT", "WOOD/RUINED", "STONE/INTACT", "STONE/WORN", "STONE/RUINED"))
            assertTrue(seen.contains(k), "missing " + k + " in " + seen);
    }

    /** A creek along X between z 20 and 23 (4 wide) with the distance to it known (audit 25.11 M-1). */
    static final RoadTerrain CREEK = (x, z, s) -> {
        boolean wet = z >= 20 && z <= 23;
        s.ground = wet ? 61 : 64.2; s.kind = wet ? RoadTerrain.RIVER : RoadTerrain.DRY;
        s.water = wet ? 62 : RoadTerrain.NO_WATER; s.width = wet ? 4 : 0; s.near = Math.max(0, Math.max(20 - z, z - 23));
    };

    @Test void aRoadAlongACreekStaysOffItAndCrossesItAcross() {
        RoadLine along = plan(CREEK, 0, 0, 400, 8);
        assertNotNull(along);
        assertTrue(along.bridges.isEmpty(), "no bridge laid along the stream: " + along.bridges);
        RoadTerrain.Sample s = new RoadTerrain.Sample();
        for (int k = 0; k < along.length(); k++) {
            CREEK.sample((int) Math.floor(along.x[k]), (int) Math.floor(along.z[k]), s);
            assertEquals(RoadTerrain.DRY, s.kind, "the road walks in the creek at " + k);
        }
        RoadLine across = plan(CREEK, 0, 0, 400, 80);
        assertNotNull(across);
        assertEquals(1, across.bridges.size(), "one crossing");
        Bridge b = across.bridges.get(0);
        assertFalse(b.alongX, "the bridge spans the creek, not its length: " + b);
        assertTrue(b.span() <= 4 * 1.6 + 3, "span close to the creek's width: " + b);
        // A road heading along the channel at a crossing gets no bridge at all.
        assertNull(Bridge.plan(CREEK, 200, 21.5, 1, 0.05, 3, RoadRouter.MAX_BRIDGE), "a stretch along the stream is not a crossing");
    }

    @Test void bridgeWoodFollowsTheBiome() {
        Map<String, String> expected = Map.ofEntries(Map.entry("cherry_grove", "cherry"), Map.entry("mangrove_swamp", "mangrove"),
                Map.entry("grove", "spruce"), Map.entry("taiga", "spruce"), Map.entry("snowy_plains", "spruce"), Map.entry("savanna", "acacia"),
                Map.entry("plains", "oak"), Map.entry("dark_forest", "dark_oak"), Map.entry("birch_forest", "birch"),
                Map.entry("bamboo_jungle", "jungle"), Map.entry("pale_garden", "pale_oak"), Map.entry("frozen_river", "spruce"), Map.entry("meadow", "oak"));
        expected.forEach((biome, wood) -> assertEquals(wood, RoadMaterials.wood(biome), biome));
        assertEquals("sandstone", RoadMaterials.stone("desert", 1));
    }

    @Test void theFinishedLineHasNoStepAndNoWaterWithoutABridge() {
        // A ridge one block thick and eight high between two lattice readings, and the same strip as a lake
        // (audit 25.11 K2: the line crossed both).
        RoadTerrain ridge = land((x, z) -> x == 201 && Math.abs(z) <= 6 ? 72.2 : 64.2);
        RoadTerrain lake = (x, z, s) -> {
            boolean wet = x == 201 && Math.abs(z) <= 6;
            s.ground = wet ? 60 : 64.2; s.kind = wet ? RoadTerrain.LAKE : RoadTerrain.DRY; s.water = wet ? 62 : RoadTerrain.NO_WATER; s.width = 0;
        };
        RoadTerrain.Sample s = new RoadTerrain.Sample(), p = new RoadTerrain.Sample();
        for (long id = 1; id <= 3; id++) {
            RoadLine r = plan(id, ridge, 0, 0, 400, 0);
            assertNotNull(r);
            assertNoPaintedStep(r, ridge);
            RoadLine w = plan(id, lake, 0, 0, 400, 0);
            assertNotNull(w);
            for (int k = 0; k < w.length(); k++) {
                lake.sample((int) Math.floor(w.x[k]), (int) Math.floor(w.z[k]), s);
                assertNotEquals(RoadTerrain.LAKE, s.kind, "the road crosses the lake strip at " + k);
            }
        }
    }

    static int top(double ground) { return (int) Math.ceil(ground) - 1; }
    /** Between neighbouring painted points the top blocks differ by a block at most. */
    static void assertNoPaintedStep(RoadLine r, RoadTerrain t) {
        RoadTerrain.Sample s = new RoadTerrain.Sample(), p = new RoadTerrain.Sample();
        for (int k = 1; k < r.length(); k++) {
            if (r.density[k] == 0 || r.density[k - 1] == 0) continue;
            t.sample((int) Math.floor(r.x[k]), (int) Math.floor(r.z[k]), s);
            t.sample((int) Math.floor(r.x[k - 1]), (int) Math.floor(r.z[k - 1]), p);
            assertTrue(Math.abs(top(s.ground) - top(p.ground)) <= RoadRouter.MAX_STEP,
                    "a step from Y" + top(p.ground) + " to Y" + top(s.ground) + " at " + k + " (" + r.x[k] + "," + r.z[k] + ")");
        }
    }

    @Test void aStepOfTwoBlocksCountsByTheTopBlocks() {
        // Ground 64.0 and 65.1 across the whole corridor: tops Y63 and Y65, 1.1 apart in height (code audit 25.11
        // OpenAI 2: the planner took it as walkable and painted over it).
        RoadTerrain step = land((x, z) -> x < 200 ? 64.0 : 65.1);
        for (long id = 1; id <= 4; id++) {
            RoadLine r = plan(id, step, 0, 0, 400, 0);
            assertNotNull(r);
            assertNoPaintedStep(r, step);
        }
    }

    @Test void aRidgeOrALakeAtTheVillageExitIsSeen() {
        // A ridge eight high and a lake strip ten blocks from the street end: the 16 points next to each end were not
        // checked (code audits 25.11 OpenAI, Opus KO-04).
        RoadTerrain ridge = land((x, z) -> x == 10 && Math.abs(z) <= 40 ? 72.2 : 64.2);
        RoadTerrain lake = (x, z, s) -> {
            boolean wet = x == 10 && Math.abs(z) <= 40;
            s.ground = wet ? 60 : 64.2; s.kind = wet ? RoadTerrain.LAKE : RoadTerrain.DRY; s.water = wet ? 62 : RoadTerrain.NO_WATER; s.width = 0;
        };
        RoadTerrain.Sample s = new RoadTerrain.Sample();
        for (long id = 1; id <= 3; id++) {
            RoadLine r = plan(id, ridge, 0, 0, 400, 0);
            if (r != null) assertNoPaintedStep(r, ridge);
            RoadLine w = plan(id, lake, 0, 0, 400, 0);
            if (w == null) continue;
            for (int k = 2; k < w.length(); k++) { // the street end itself stays unchecked
                lake.sample((int) Math.floor(w.x[k]), (int) Math.floor(w.z[k]), s);
                assertNotEquals(RoadTerrain.LAKE, s.kind, "the road walks into the lake at " + k);
            }
        }
    }

    @Test void bothEndsStreetPreferencesCount() {
        // Two street ends of the second village: one 8 blocks farther but preferred by 12 (code audit 25.11 K9).
        RoadRouter router = new RoadRouter(land((x, z) -> 64.2), NONE);
        RoadLine r = router.plan(9, SEED, List.of(new RoadRouter.Exit(0, 0, 0)),
                List.of(new RoadRouter.Exit(240, 0, 12), new RoadRouter.Exit(248, 0, 0)), 0, 0, 248, 0, 600);
        assertNotNull(r);
        assertEquals(248.5, r.x[r.length() - 1], 1e-6, "the cheaper end: 248 + 0 < 240 + 12");
    }

    /** Blocks of a bridge without a chain of blocks to the ground (6-connected), as a player would see them hang. */
    static int floating(Bridge b, RoadTerrain t) {
        RoadTerrain.Sample s = new RoadTerrain.Sample();
        java.util.function.IntBinaryOperator top = (x, z) -> { t.sample(x, z, s); return (int) Math.ceil(s.ground) - 1; };
        Set<List<Integer>> placed = new HashSet<>(), seeds = new HashSet<>();
        int[] box = b.box();
        for (int x = box[0]; x <= box[2]; x++) for (int z = box[1]; z <= box[3]; z++) {
            final int fx = x, fz = z, ground = top.applyAsInt(x, z);
            b.column(x, z, (y, role, variant, dir, links) -> {
                if (role == Bridge.APPROACH) return;
                if (role == Bridge.PILLAR || role == Bridge.POST_UNDER || role == Bridge.RUBBLE || role == Bridge.STEP) {
                    // Runs down to the bed (the painter descends through water and air).
                    for (int yy = role == Bridge.RUBBLE ? ground + 1 : y; yy > ground; yy--) { placed.add(List.of(fx, yy, fz)); seeds.add(List.of(fx, yy, fz)); }
                    return;
                }
                if (y > ground || role == Bridge.DECK) placed.add(List.of(fx, y, fz));
            });
        }
        for (List<Integer> k : placed) {
            int x = k.get(0), y = k.get(1), z = k.get(2);
            if (y - 1 <= top.applyAsInt(x, z) || y <= top.applyAsInt(x + 1, z) || y <= top.applyAsInt(x - 1, z)
                    || y <= top.applyAsInt(x, z + 1) || y <= top.applyAsInt(x, z - 1)) seeds.add(k);
        }
        Set<List<Integer>> reached = new HashSet<>();
        ArrayDeque<List<Integer>> queue = new ArrayDeque<>();
        for (List<Integer> k : seeds) if (placed.contains(k) && reached.add(k)) queue.add(k);
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            List<Integer> k = queue.poll();
            for (int[] d : steps) {
                List<Integer> m = List.of(k.get(0) + d[0], k.get(1) + d[1], k.get(2) + d[2]);
                if (placed.contains(m) && reached.add(m)) queue.add(m);
            }
        }
        return placed.size() - reached.size();
    }

    /** A river along Z at x 100..100+width-1 (water Y62) between banks of their own heights, the far side of each bank
     * sloping down by slope blocks across the axis (z > 0): banks of every height and side columns lower than the axis
     * (code audit 25.11 Fable K-02, K-03: the test held one bank height, two above the water). */
    static RoadTerrain banks(int width, double bedTop, double west, double east, double slope) {
        return (x, z, s) -> {
            boolean river = x >= 100 && x < 100 + width;
            double bank = x < 100 ? west : east;
            s.ground = river ? bedTop : bank - (z > 0 ? slope : 0); s.kind = river ? RoadTerrain.RIVER : RoadTerrain.DRY;
            s.water = river ? 62 : RoadTerrain.NO_WATER; s.width = river ? width : 0;
        };
    }
    static final double[] BANKS = {62.2, 63.2, 64.2, 65.2, 66.2, 68.2};

    @Test void everyBlockOfAWornOrRuinedBridgeHangsOnASupport() {
        // Audit 25.11 M-2: 99% of ruined stone bridges had blocks hanging over the water; code audit 25.11 K-02: a deck
        // over a bank a block below it held on to a stair that a ruin had lost.
        int bridges = 0;
        Set<String> seen = new TreeSet<>();
        for (int width : new int[]{2, 3, 5, 8, 13, 21, 30, 38}) for (double bedTop : new double[]{60, 61.5})
            for (double west : BANKS) for (double east : BANKS) for (double slope : new double[]{0, 1.0, 2.0}) {
                RoadTerrain t = banks(width, bedTop, west, east, slope);
                for (long seed = 0; seed < 8; seed++) {
                    Bridge b = Bridge.plan(t, 100 + width / 2.0, 0, 1, 0.1, seed, RoadRouter.MAX_BRIDGE);
                    if (b == null) continue;
                    bridges++;
                    seen.add(b.type + "/" + b.state);
                    assertEquals(0, floating(b, t), "hanging blocks: " + b + " seed " + seed + " banks " + west + "/" + east + " slope " + slope);
                }
            }
        assertTrue(seen.contains("STONE/RUINED") && seen.contains("WOOD/RUINED") && seen.contains("BOARDWALK/RUINED"), seen.toString());
        assertTrue(bridges > 8000, "bridges " + bridges);
    }

    @Test void intactAndWornBridgesAreWalkedFromBankToBank() {
        // Code audits 25.11 Fable K-01/K-05, Opus KO-02: stepping stones and boardwalks at the water's surface under banks
        // two blocks higher (seed 314159265 X404 Z-401), decks below high banks on both sides. Walked on the blocks the
        // bridge draws on its centre line, from the ground before it to the ground after it.
        int walked = 0, none = 0;
        Set<String> seen = new TreeSet<>();
        for (int width : new int[]{2, 3, 5, 8, 13, 21}) for (double bedTop : new double[]{60, 61.5})
            for (double west : BANKS) for (double east : BANKS) {
                RoadTerrain t = banks(width, bedTop, west, east, 0);
                for (long seed = 0; seed < 12; seed++) {
                    Bridge b = Bridge.plan(t, 100 + width / 2.0, 0, 1, 0.1, seed, RoadRouter.MAX_BRIDGE);
                    if (b == null) { none++; continue; }
                    if (b.state == Bridge.State.RUINED) continue;
                    seen.add(b.type.toString());
                    walked++;
                    int[] box = b.box();
                    int prev = top(west);
                    for (int x = box[0] - 1; x <= box[2] + 1; x++) {
                        boolean water = x >= 100 && x < 100 + width;
                        int[] best = {water ? Integer.MIN_VALUE : top(x < 100 ? west : east)};
                        b.column(x, b.centre, (y, role, variant, dir, links) -> {
                            if (role == Bridge.DECK || role == Bridge.STAIR || role == Bridge.LOG || role == Bridge.STEP || role == Bridge.SUPPORT)
                                best[0] = Math.max(best[0], y);
                        });
                        if (best[0] == Integer.MIN_VALUE) {
                            // Stepping stones: the shallow stream between them is waded; the stones at the banks are the way out.
                            assertEquals(Bridge.Type.STEPPING, b.type, "no block over the water at x " + x + ": " + b);
                            continue;
                        }
                        assertTrue(Math.abs(best[0] - prev) <= 1, "a step from Y" + prev + " to Y" + best[0] + " at x " + x + ": " + b
                                + " banks " + west + "/" + east + " seed " + seed);
                        prev = best[0];
                    }
                    assertTrue(Math.abs(prev - top(east)) <= 1, "onto the east bank: " + b);
                }
            }
        assertTrue(walked > 2000 && seen.size() == 5, walked + " walked, " + none + " without a bridge, kinds " + seen);
        // Banks two blocks above the water on both sides of a narrow creek still get a crossing.
        for (long seed = 0; seed < 40; seed++) assertNotNull(Bridge.plan(banks(3, 61.5, 64.2, 64.2, 0), 101.5, 0, 1, 0.1, seed, RoadRouter.MAX_BRIDGE));
    }

    @Test void tooWideWaterHasNoBridge() {
        RoadTerrain t = (x, z, s) -> {
            boolean river = x >= 100 && x < 160;
            s.ground = river ? 58 : 64.2; s.kind = river ? RoadTerrain.RIVER : RoadTerrain.DRY;
            s.water = river ? 62 : RoadTerrain.NO_WATER; s.width = river ? 60 : 0;
        };
        assertNull(Bridge.plan(t, 130, 0, 1, 0, 1, RoadRouter.MAX_BRIDGE));
    }

    @Test void graphRules() {
        var a = new RoadGraph.Site(1, 0, 0, 64);
        var b = new RoadGraph.Site(2, 600, 0, 64);
        var c = new RoadGraph.Site(3, 300, 50, 64);
        assertFalse(RoadGraph.neighbours(a, b, List.of(a, b, c)), "a village between them takes the road");
        assertTrue(RoadGraph.neighbours(a, c, List.of(a, b, c)));
        assertTrue(RoadGraph.chance(300, 0) > RoadGraph.chance(850, 0));
        assertTrue(RoadGraph.chance(300, 80) < RoadGraph.chance(300, 0));
        assertEquals(RoadGraph.rolled(SEED, a, c), RoadGraph.rolled(SEED, c, a), "order-free");
        int rolled = 0;
        for (int i = 0; i < 2000; i++) if (RoadGraph.rolled(SEED, new RoadGraph.Site(i, 0, 0, 64), new RoadGraph.Site(i + 5000, 400, 0, 64))) rolled++;
        assertTrue(rolled > 1150 && rolled < 1450, "about the base chance (0.65): " + rolled);
    }
}
