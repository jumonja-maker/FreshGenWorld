package dev.freshgenerator.core.hydro;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HydroModelTest {
    /** Smooth value noise in [-1, 1]. */
    static double noise(long seed, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz, sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double a = u(seed, ix, iz), b = u(seed, ix + 1, iz), c = u(seed, ix, iz + 1), d = u(seed, ix + 1, iz + 1);
        return (a + (b - a) * sx) * (1 - sz) + (c + (d - c) * sx) * sz;
    }
    static double u(long seed, int x, int z) { return ((HydroModel.hash(seed, x, z) >>> 11) * 0x1.0p-53) * 2 - 1; }

    record Field(java.util.function.ToDoubleBiFunction<Integer, Integer> h, java.util.function.ToDoubleBiFunction<Integer, Integer> c,
                 double t, double m, double e) implements HydroInputs {
        public double height(int x, int z) { return h.applyAsDouble(x, z); }
        public double continents(int x, int z) { return c.applyAsDouble(x, z); }
        public double temperature(int x, int z) { return t; }
        public double moisture(int x, int z) { return m; }
        public double erosion(int x, int z) { return e; }
    }

    /** Round island, radius 2400, rolling relief, ocean around it. */
    static Field island(double t, double m) {
        return new Field((x, z) -> {
            double r = Math.hypot(x, z) / 2400.0;
            double base = 63 + 75 * Math.pow(Math.max(0, 1 - r), 1.15) - 25 * Math.max(0, r - 1);
            return base + 6 * noise(11, x / 260.0, z / 260.0) + 2 * noise(12, x / 70.0, z / 70.0);
        }, (x, z) -> 0.35 - 0.6 * Math.hypot(x, z) / 2400.0, t, m, 0);
    }
    /** Plane falling to +x with a deep closed pit at (300, 0); sea beyond x ~ 2850. */
    static Field pitPlane(double t, double m) {
        return new Field((x, z) -> 120 - 0.02 * x - 26 * Math.exp(-((x - 300.0) * (x - 300.0) + z * (double) z) / (2 * 110.0 * 110.0))
                        + 1.5 * noise(21, x / 90.0, z / 90.0),
                (x, z) -> 0.3 - x / 3000.0, t, m, 0);
    }

    @Test void flowPathsTerminateWithoutCycles() {
        HydroModel m = new HydroModel(island(0, 0.1), 42);
        for (int j = -40; j <= 40; j += 3) for (int i = -40; i <= 40; i += 3) {
            long n = HydroModel.node(i, j);
            HashSet<Long> seen = new HashSet<>();
            long cur = n;
            for (int step = 0; step < 10_000; step++) {
                assertTrue(seen.add(cur), "cycle at " + i + "," + j);
                long d = m.down(cur);
                if (d == HydroModel.OCEAN_ID || d == HydroModel.NONE || d == HydroModel.BASIN_FLAG) break;
                cur = d;
            }
        }
    }

    @Test void catchmentGrowsDownstreamAndRiversReachTheSea() {
        HydroModel m = new HydroModel(island(0, 0.1), 42);
        int rivers = 0, wet = 0;
        for (int j = -60; j <= 60; j += 2) for (int i = -60; i <= 60; i += 2) {
            long n = HydroModel.node(i, j);
            long d = m.down(n);
            if (d != HydroModel.OCEAN_ID && d != HydroModel.NONE && d != HydroModel.BASIN_FLAG)
                assertTrue(m.area(d) >= m.area(n) - 1e-9, "area decreases downstream");
            int c = m.channel(n);
            if (c > 0) rivers++;
            if (c == 2) {
                wet++;
                assertTrue(d == HydroModel.NONE || d == HydroModel.OCEAN_ID || d == HydroModel.BASIN_FLAG || m.channel(d) == 2,
                        "a wet river continues downstream");
                if (d != HydroModel.NONE && d != HydroModel.OCEAN_ID && d != HydroModel.BASIN_FLAG)
                    assertTrue(m.water(d) <= m.water(n) + 1e-6, "water level rises downstream");
            }
        }
        assertTrue(rivers > 20, "channels exist: " + rivers);
        assertTrue(wet > 10, "wet channels exist: " + wet);
    }

    @Test void deepPitBecomesAContainedLakeWithAnOutlet() {
        HydroModel m = new HydroModel(pitPlane(0, 0.15), 7);
        long pit = m.terminal(HydroModel.nodeAt(300, 0));
        assertNotEquals(HydroModel.OCEAN_ID, pit);
        HydroModel.Group g = m.decided(m.group(pit));
        assertEquals(HydroModel.Group.BASIN, g.kind, "deep pit is a basin");
        assertEquals(HydroModel.Group.LAKE, g.state, "humid basin holds a lake");
        int level = m.water(g);
        assertNotEquals(HydroModel.NO_WATER, level);
        assertTrue(level > m.h(g.deepest) && level < g.flood.level, "level " + level + " between bottom and rim " + g.flood.level);
        HydroLakes.Mask mask = g.mask;
        assertTrue(mask.count > 200, "lake area " + mask.count);
        // Watertight: every lake column's neighbours are lake, a channel, or ground above the level.
        int leaks = 0;
        HydroField field = new HydroField(m);
        HydroField.Column col = new HydroField.Column();
        for (int b = 0; b < mask.h; b++) for (int a = 0; a < mask.w; a++) {
            int x = a + mask.x0, z = b + mask.z0;
            if (!mask.contains(x, z)) continue;
            for (int d = 0; d < 4; d++) {
                int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (mask.contains(nx, nz)) continue;
                field.column(nx, nz, col);
                if (!col.wetIncludingSea() && col.terrain <= level) {
                    if (leaks < 8) System.out.println("LEAK " + nx + "," + nz + " level " + level + " kind " + col.kind + " water " + col.water
                            + " terrain " + col.terrain + " base " + col.base + " chd " + col.channelDistance);
                    leaks++;
                }
            }
        }
        System.out.println("LAKE level " + level + " rim " + g.flood.level + " count " + mask.count + " pit " + m.h(g.deepest));
        assertEquals(0, leaks, "lake leaks");
        // The outlet carries water on to the sea.
        assertEquals(2, m.channel(g.flood.rim), "lake outlet is a wet channel");
    }

    @Test void desertPitStaysADryPlaya() {
        // Zone temperature hot enough that even the altitude-cooled uplands upstream stay desert (no mountain river).
        HydroModel m = new HydroModel(pitPlane(1.2, -0.4), 7);
        long pit = m.terminal(HydroModel.nodeAt(300, 0));
        HydroModel.Group g = m.decided(m.group(pit));
        assertEquals(HydroModel.Group.DRY, g.state, "arid basin without exotic inflow is a playa");
        assertEquals(HydroModel.NO_WATER, m.water(g));
    }

    @Test void riverColumnsAreWatertight() {
        HydroModel m = new HydroModel(island(0, 0.2), 99);
        HydroField field = new HydroField(m);
        // Window centred on a large river between the summit and the coast.
        long best = HydroModel.node(30, 0); double bf = -1;
        for (int j = -50; j <= 50; j += 2) for (int i = -50; i <= 50; i += 2) {
            long k = HydroModel.node(i, j);
            if (m.channel(k) == 2 && m.flow(k) > bf && m.flow(k) < 40) { bf = m.flow(k); best = k; }
        }
        int n = 600, x0 = m.x(best) - n / 2, z0 = m.z(best) - n / 2;
        HydroField.Column[] grid = new HydroField.Column[n * n];
        for (int b = 0; b < n; b++) for (int a = 0; a < n; a++) {
            HydroField.Column c = new HydroField.Column();
            field.column(x0 + a, z0 + b, c);
            grid[a + n * b] = c;
        }
        int wet = 0, leaks = 0;
        String example = null;
        for (int b = 1; b < n - 1; b++) for (int a = 1; a < n - 1; a++) {
            HydroField.Column c = grid[a + n * b];
            if (!c.wet()) continue;
            wet++;
            int level = c.effectiveWater();
            for (int d = 0; d < 4; d++) {
                HydroField.Column o = grid[a + (d == 0 ? 1 : d == 1 ? -1 : 0) + n * (b + (d == 2 ? 1 : d == 3 ? -1 : 0))];
                if (!o.wetIncludingSea() && o.terrain <= level) {
                    leaks++;
                    if (example == null) example = (x0 + a) + "," + (z0 + b) + " water " + level + " neighbour terrain " + o.terrain + " kind " + o.kind + " chd " + o.channelDistance;
                }
            }
        }
        assertTrue(wet > 500, "river columns in the window: " + wet);
        assertTrue(leaks <= wet / 200, "leaking edges " + leaks + " of " + wet + " wet columns, e.g. " + example);
    }

    @Test void resultsDoNotDependOnOrderOrThreads() throws Exception {
        Field f = island(0, 0.1);
        int[][] pts = new int[3000][];
        Random rnd = new Random(5);
        for (int i = 0; i < pts.length; i++) pts[i] = new int[]{rnd.nextInt(3000) - 1500, rnd.nextInt(3000) - 1500};
        HydroField a = new HydroField(new HydroModel(f, 3));
        double[] ra = new double[pts.length * 2];
        HydroField.Column c = new HydroField.Column();
        for (int i = 0; i < pts.length; i++) { a.column(pts[i][0], pts[i][1], c); ra[2 * i] = c.terrain; ra[2 * i + 1] = c.water; }
        HydroField b = new HydroField(new HydroModel(f, 3));
        double[] rb = new double[pts.length * 2];
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            int start = t;
            jobs.add(pool.submit(() -> {
                HydroField.Column cc = new HydroField.Column();
                for (int i = pts.length - 1 - start; i >= 0; i -= 6) { b.column(pts[i][0], pts[i][1], cc); rb[2 * i] = cc.terrain; rb[2 * i + 1] = cc.water; }
            }));
        }
        for (Future<?> j : jobs) j.get();
        pool.shutdown();
        assertArrayEquals(ra, rb, 0, "same columns in any order and thread interleaving");
    }

    @Test void renderSyntheticLandscapes() throws Exception {
        java.io.File dir = new java.io.File("build/hydro-render");
        HydroRender.render(new HydroField(new HydroModel(island(0, 0.15), 42)), -2700, -2700, 5400, 6, new java.io.File(dir, "island-temperate.png"));
        HydroRender.render(new HydroField(new HydroModel(island(-0.3, 0.1), 42)), -2700, -2700, 5400, 6, new java.io.File(dir, "island-boreal.png"));
        HydroRender.render(new HydroField(new HydroModel(island(0.7, -0.3), 42)), -2700, -2700, 5400, 6, new java.io.File(dir, "island-desert.png"));
        HydroRender.render(new HydroField(new HydroModel(pitPlane(0, 0.15), 7)), -400, -800, 1600, 2, new java.io.File(dir, "pit-lake.png"));
        for (double[] cl : new double[][]{{0, 0.15}, {-0.3, 0.1}, {0.7, -0.3}}) {
            HydroModel m = new HydroModel(island(cl[0], cl[1]), 42);
            int[] counts = new int[3]; double maxFlow = 0, maxArea = 0;
            for (int j = -75; j <= 75; j++) for (int i = -75; i <= 75; i++) {
                long k = HydroModel.node(i, j); if (m.ocean(k)) continue;
                counts[m.channel(k)]++; maxFlow = Math.max(maxFlow, m.flow(k)); maxArea = Math.max(maxArea, m.area(k));
            }
            System.out.println("CLIMATE " + cl[0] + "/" + cl[1] + " " + m.setting(HydroModel.node(10, 10)) + " none/dry/wet " + java.util.Arrays.toString(counts)
                    + " maxArea " + maxArea + " maxFlow " + maxFlow);
        }
        HydroRender.render(new HydroField(new HydroModel(island(0, 0.15), 42)), 600, -300, 600, 1, new java.io.File(dir, "island-temperate-zoom.png"));
        HydroRender.render(new HydroField(new HydroModel(island(0.7, -0.3), 42)), 600, -300, 600, 1, new java.io.File(dir, "island-desert-zoom.png"));
    }

    @Test void exactFlatsAndNestedHollowsNeverRouteInCycles() {
        // Real seed 42: touching pits at exactly Y64.00 and a shallow hollow nested inside another made the outer
        // depression's breach path run through the inner one and back (a flow cycle).
        Field f = new Field((x, z) -> {
            double r = Math.hypot(x - 400, z) / 300.0;
            double outer = r < 1 ? -3 * (1 - r * r) : 0;                       // shallow outer hollow
            double inner = -1.5 * Math.exp(-((x - 450.0) * (x - 450.0) + (z - 60.0) * (z - 60.0)) / (2 * 50.0 * 50.0));
            double plane = 66 - 0.004 * x;
            double flat = Math.abs(x - 1200) < 300 && Math.abs(z) < 300 ? 64.0 : plane; // exactly level patch
            return Math.min(flat, plane + outer + inner);
        }, (x, z) -> 0.3 - x / 4000.0, 0, 0.15, 0);
        HydroModel m = new HydroModel(f, 11);
        assertEquals(List.of(), m.cycles(-10, -20, 60, 20, 10), "flow cycles");
    }

    /** Rugged humid land falling to a sea at +x: many confluences, knickpoints and lakes. */
    static Field rugged(long seed) {
        return new Field((x, z) -> {
            double base = 160 - 0.05 * x;
            return base + 12 * noise(seed, x / 300.0, z / 300.0) + 7 * noise(seed + 1, x / 110.0, z / 110.0) + 3 * noise(seed + 2, x / 40.0, z / 40.0);
        }, (x, z) -> 0.4 - x / 4000.0, 0.1, 0.4, 0.2);
    }

    @Test void networkIsConsistentOnRuggedLand() {
        // Audit 22.11: junction levels (H03), waterfalls without a drop (FG-03), lakes without water (H02), NaN.
        for (long seed : new long[]{1, 2, 3, 42}) {
            HydroModel m = new HydroModel(rugged(seed), seed);
            Map<String, Number> r = m.integrity(-1500, -1500, 1500, 1500);
            System.out.println("RUGGED " + seed + " " + r);
            assertEquals(0, r.get("falls_without_drop").intValue(), "falls without a drop " + r);
            assertEquals(0, r.get("lakes_without_water").intValue(), "lakes without water " + r);
            assertEquals(0, r.get("dry_lakes_feeding_rivers").intValue(), "dry lakes feeding rivers " + r);
            assertEquals(0, r.get("nan_bed").intValue(), "NaN beds " + r);
            assertEquals(0, r.get("junction_steps_3plus").intValue(), "junction steps " + r);
            assertTrue(r.get("junctions").intValue() > 50, "confluences exist " + r);
            // Player report 23.11 (seed 42 X254 Z-1566): a knickpoint took the whole drop of a steep reach.
            assertEquals(0, r.get("knick_falls_over_cap").intValue(), "knickpoint falls above their cap " + r);
            // Final columns (audit 22.11 N-02/H03): a wet channel's axis is never dry (another valley or a dry wash).
            Map<String, Number> f = new TreeMap<>();
            dev.freshgenerator.audit.HydroMapAudit.axisChecks(new HydroField(m), -1500, -1500, 1500, 1500, f);
            System.out.println("RUGGED_AXIS " + seed + " " + f);
            assertEquals(0, f.get("axis_dry").intValue(), "dry columns on wet channel axes " + f);
        }
    }

    @Test void longRiverProfilesDoNotRecurse() throws Exception {
        // Audit 22.11 FG-01: a 60 km valley (about 1900 wet reaches) built cold from the mouth on a small stack.
        Field f = new Field((x, z) -> x >= 0 && x <= 60000 && Math.abs(z) <= 512 ? 70 + 0.002 * (60000 - x) + 0.04 * Math.abs(z) : 55,
                (x, z) -> x >= 0 && x <= 60000 && Math.abs(z) <= 512 ? 0.3 : -0.5, 0.1, 0.3, 0.2);
        HydroModel m = new HydroModel(f, 12345);
        Throwable[] error = new Throwable[1];
        int[] chain = new int[1];
        Thread t = new Thread(null, () -> {
            try {
                HydroField field = new HydroField(m);
                HydroField.Column c = new HydroField.Column();
                field.column(59872, 0, c);
                long n = HydroModel.nodeAt(59872, 0);
                for (long k = n; chain[0] < 10_000; chain[0]++) { long md = m.mainDonor(k); if (md == HydroModel.NONE || m.channel(md) != 2) break; k = md; }
                HydroReach r = m.reach(m.mainDonor(n));
                if (r != null) assertTrue(r.profile(m).water.length > 0);
            } catch (Throwable e) { error[0] = e; }
        }, "small-stack", 256 * 1024);
        t.start(); t.join();
        if (error[0] != null) throw new AssertionError("long river failed: " + error[0], error[0]);
        assertTrue(chain[0] > 1000, "long wet chain upstream: " + chain[0]);
    }

    @Test void closedTerminalEndIsFinite() {
        // Audit 22.11 FG-03: at the exact end of a closing terminal channel the half-width is 0 (0/0 before).
        HydroModel m = new HydroModel(pitPlane(0, 0.15), 7);
        HydroField field = new HydroField(m);
        HydroField.Column c = new HydroField.Column();
        for (int z = -600; z <= 600; z += 3) for (int x = -200; x <= 1400; x += 3) {
            field.column(x, z, c);
            assertTrue(Double.isFinite(c.terrain), "terrain at " + x + "," + z);
        }
    }

    @Test void largeLakeKeepsItsLevelAndOverflowsIntoItsOutlet() {
        // Audit 22.11 N-01: a ring basin whose watertight lake exceeds 2 million columns was lowered to fit a budget
        // (level 87 instead of ~94) and lost its outlet.
        double R = 2048;
        Field f = new Field((x, z) -> {
            double r = Math.hypot(x, z);
            return r > R ? 55 : 70 + 28 * Math.exp(-Math.pow((r - 0.5 * R) / (0.16 * R), 2)) + 0.003 * x;
        }, (x, z) -> Math.hypot(x, z) > R ? -0.5 : 0.3, 0.1, 0.3, 0.2);
        HydroModel m = new HydroModel(f, 12345);
        long pit = m.terminal(HydroModel.nodeAt(0, 0));
        HydroModel.Group g = m.decided(m.group(pit));
        int level = m.water(g);
        System.out.println("RING kind " + g.kind + " state " + g.state + " spill " + g.flood.level + " level " + level + " mask " + (g.mask == null ? 0 : g.mask.count));
        assertEquals(HydroModel.Group.LAKE, g.state);
        assertTrue(level >= g.flood.level - 3, "lake level " + level + " near the spill " + g.flood.level);
        Map<String, Number> r = m.integrity(-2200, -2200, 2200, 2200);
        System.out.println("RING " + r);
        assertEquals(0, r.get("lake_outlets_detached").intValue(), "detached outlets " + r);
    }

    @Test void bendsNeverFoldTheCentreline() {
        // Player report 23.11: creeks bend now too. The summed displacement field of river meanders and creek bends
        // must stay a smooth one-to-one map (positive Jacobian with margin) for every width and flatness.
        double h = 0.5, worst = Double.POSITIVE_INFINITY, far = 0;
        for (double w : new double[]{2, 3, 3.5, 4, 5, 6, 8, 9, 11, 14, 20, 40, 64})
            for (double flat : new double[]{0, 0.35, 0.7, 1})
                for (int z = -900; z <= 900; z += 13) for (int x = -900; x <= 900; x += 13) {
                    double[] c = displaced(x, z, w, flat), ex = displaced(x + h, z, w, flat), ez = displaced(x, z + h, w, flat);
                    double a = (ex[0] - c[0]) / h, b = (ez[0] - c[0]) / h, cc = (ex[1] - c[1]) / h, d = (ez[1] - c[1]) / h;
                    worst = Math.min(worst, a * d - b * cc);
                    far = Math.max(far, Math.hypot(c[0] - x, c[1] - z));
                }
        System.out.println("BENDS worst jacobian " + worst + " largest shift " + far);
        assertTrue(worst > 0.2, "displacement folds the line: jacobian " + worst);
        assertTrue(far < 60, "bend shift " + far);
    }
    private static double[] displaced(double x, double z, double w, double flat) {
        double[] px = {x}, pz = {z};
        HydroReach.meander(px, pz, w, w, flat, flat, 77);
        return new double[]{px[0], pz[0]};
    }

    @Test void creekWidthHasASoftFloor() {
        // Player report 23.11: a creek 2 blocks wide showed one line of water in a slot. Small channels are at least
        // MIN_WIDTH wide, grow smoothly with the flow, and rivers keep their geometric width.
        double last = 0;
        for (double q = 0; q <= 60; q += 0.01) {
            double w = HydroClimate.width(1.0, q);
            assertTrue(w >= HydroClimate.MIN_WIDTH - 1e-9, "width " + w + " at flow " + q);
            assertTrue(w >= last - 1e-9, "width shrinks with flow at " + q);
            assertTrue(q == 0 || w - last < 0.2, "width jumps at flow " + q);
            last = w;
        }
        double raw = 3.2 * Math.pow(30, 0.45) * (1 + 0.6 * HydroClimate.smooth(4, 40, 30));
        assertEquals(raw, HydroClimate.width(1.0, 30), 1e-9, "rivers keep the geometric width");
    }

    @Test void riverMouthsFadeOutOnTheSeaFloor() {
        // Audit 23.11 (seed 42 X2900 Z2482): the last reach ran on to its node in the sea and cut a straight trench
        // across the shallows. Below MOUTH_FADE under the sea surface the floor stays native.
        // Rugged land: the coast lies near x 1940, the sea floor deepens to the east.
        HydroModel m = new HydroModel(rugged(42), 42);
        HydroField field = new HydroField(m);
        HydroField.Column c = new HydroField.Column();
        int mouths = 0, cut = 0;
        for (int z = -1500; z <= 1500; z += 2) for (int x = 1900; x <= 2500; x += 2) {
            field.column(x, z, c);
            if (c.kind != HydroField.RIVER || c.water == HydroModel.NO_WATER) continue;
            if (c.base < HydroModel.SEA_LEVEL - HydroField.MOUTH_FADE) { mouths++; if (c.terrain < c.base - 1e-9) cut++; }
        }
        System.out.println("MOUTHS sea-floor channel columns " + mouths + " cut " + cut);
        assertTrue(mouths > 20, "rivers reach the sea: " + mouths);
        assertEquals(0, cut, "channel cut into the sea floor");
    }

    @Test void renewedModelGivesTheWorldOfAFreshSession() throws Exception {
        // Code audit 24.11 F-1 (seed 20261124 X9771 Z7596): forgetting part of the model's derived state left a
        // depression half registered; the next query described the same land as two basins with contradicting routes
        // (a flow cycle, a stream moved over 800 blocks). Bounded memory now replaces the whole model: an area read
        // before the renewal, an area read after it and the seam between them equal a fresh session column by column.
        for (long seed : new long[]{2, 42}) {
            HydroField fresh = new HydroField(new HydroModel(rugged(seed), seed));
            HydroField renewed = new HydroField(new HydroModel(rugged(seed), seed));
            HydroField.Column c = new HydroField.Column();
            // Window without the giant basin of seed 42 beyond z 544 (2499 model tiles on this endless slope).
            for (int z = -800; z <= 400; z += 11) for (int x = -800; x <= 0; x += 11) renewed.column(x, z, c);
            HydroModel before = renewed.model();
            renewed.renew(before);
            assertNotSame(before, renewed.model(), "a fresh model replaces the full one");
            assertEquals(0, renewed.model().tilesCached(), "the fresh model starts empty");
            // A new thread: its column memo is empty, every column is evaluated again.
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                int[] result = pool.submit(() -> {
                    HydroField.Column a = new HydroField.Column(), b = new HydroField.Column();
                    int diffs = 0, wet = 0;
                    for (int z = -800; z <= 400; z += 5) for (int x = -800; x <= 800; x += 5) {
                        fresh.column(x, z, a); renewed.column(x, z, b);
                        if (a.wet()) wet++;
                        if (a.terrain != b.terrain || a.water != b.water || a.kind != b.kind || a.wetDistance != b.wetDistance) diffs++;
                    }
                    return new int[]{diffs, wet};
                }).get();
                System.out.println("RENEWED seed " + seed + " differing columns " + result[0] + " wet " + result[1]);
                assertTrue(result[1] > 300, "water in the window: " + result[1]);
                assertEquals(0, result[0], "columns differing from a fresh session after the renewal");
            } finally { pool.shutdown(); }
            assertEquals(List.of(), renewed.model().cycles(-25, -25, 25, 25, 10), "flow cycles in the renewed model");
        }
    }

    @Test void valleyCarveEndsWithoutWalls() {
        // Audit 24.11 G-1 (seed 31337 X-3226 Z2416, seed 42 X1765 Z-1985): a reach's valley profile was cut off at its
        // influence radius, and the nearest of two reaches could swap between neighbouring columns - dry walls of 20+
        // blocks on smooth native ground. The carve fades out toward the influence edge (EDGE_FADE), a swap blends over
        // SWAP_BLEND blocks, the ridge toward a separate valley rises at most to the natural ground and fades with that
        // valley's influence, a fading strand hands over to the separate one, and the valley side follows the blended
        // arc on the inner side of bends (before these, seed 42 here: 167 steps of 6+ blocks, worst 18.5).
        // Measured here: steps between dry neighbours beyond the native step.
        for (long seed : new long[]{1, 42}) {
            HydroField field = new HydroField(new HydroModel(rugged(seed), seed));
            HydroField.Column c = new HydroField.Column();
            int n = 512, x0 = -700, z0 = -256;
            double[] terrain = new double[n * n], base = new double[n * n];
            boolean[] wet = new boolean[n * n];
            for (int b = 0; b < n; b++) for (int a = 0; a < n; a++) {
                field.column(x0 + a, z0 + b, c);
                int i = a + n * b;
                terrain[i] = c.terrain; base[i] = c.base; wet[i] = c.wetIncludingSea();
            }
            int pairs = 0, over6 = 0, over12 = 0; double worst = 0; String where = null;
            for (int b = 0; b < n; b++) for (int a = 0; a < n; a++) for (int d = 0; d < 2; d++) {
                int a2 = a + (d == 0 ? 1 : 0), b2 = b + (d == 1 ? 1 : 0);
                if (a2 >= n || b2 >= n) continue;
                int i = a + n * b, j = a2 + n * b2;
                if (wet[i] || wet[j]) continue;
                pairs++;
                double excess = Math.abs(terrain[i] - terrain[j]) - Math.abs(base[i] - base[j]);
                if (excess >= 6) over6++;
                if (excess >= 12) over12++;
                if (excess > worst) { worst = excess; where = (x0 + a) + "," + (z0 + b) + (d == 0 ? " x" : " z"); }
            }
            System.out.printf("CARVE_WALLS seed %d dry pairs %d excess>=6 %d excess>=12 %d worst %.1f at %s%n", seed, pairs, over6, over12, worst, where);
            assertTrue(pairs > 400_000, "dry neighbour pairs in the window: " + pairs);
            assertEquals(0, over6, "dry steps of 6+ blocks beyond the native step, worst " + worst + " at " + where);
        }
    }

    @Test void climateParametersAreContinuousAcrossClassBorders() {
        // Audit 22.11 N-04/C01: runoff, channel heads, size factors and aridity change gradually, not at T = 0.55.
        for (double[] at : new double[][]{{0.55, 0.4}, {0.55, 0.8}, {0.2, 0.1}, {-0.15, 0.2}, {-0.45, 0.0}, {0.3, 0.1}}) {
            HydroClimate.Params a = HydroClimate.params(at[0] - 1e-6, at[1], 0.2, 80), b = HydroClimate.params(at[0] + 1e-6, at[1], 0.2, 80);
            HydroClimate.Params c = HydroClimate.params(at[0], at[1] - 1e-6, 0.2, 80), d = HydroClimate.params(at[0], at[1] + 1e-6, 0.2, 80);
            for (HydroClimate.Params[] pair : new HydroClimate.Params[][]{{a, b}, {c, d}}) {
                assertEquals(pair[0].runoff(), pair[1].runoff(), 0.05 * Math.max(0.05, pair[0].runoff()), "runoff at " + java.util.Arrays.toString(at));
                assertEquals(pair[0].channelArea(), pair[1].channelArea(), 0.05 * pair[0].channelArea(), "channel area at " + java.util.Arrays.toString(at));
                assertEquals(pair[0].arid(), pair[1].arid(), 0.06, "aridity at " + java.util.Arrays.toString(at));
                assertEquals(pair[0].depthK(), pair[1].depthK(), 0.06, "depth factor at " + java.util.Arrays.toString(at));
            }
        }
        // The hot zone is still dry land (the biome table's hot column is desert at any humidity) - reached gradually.
        assertTrue(HydroClimate.params(0.7, 0.8, 0.2, 80).arid() > 0.99);
        assertTrue(HydroClimate.params(0.45, 0.8, 0.2, 80).arid() < 0.01);
        // Mountains are classed by their cooled climate, like the biomes on them.
        assertEquals(HydroClimate.Setting.ALPINE, HydroClimate.setting(0.65, 0.4, 0.2, 250));
    }
}
