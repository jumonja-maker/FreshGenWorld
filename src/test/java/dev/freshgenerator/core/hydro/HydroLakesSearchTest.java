package dev.freshgenerator.core.hydro;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The lake level search and the corner table are speed-ups only: same level, same mask, same heights. */
class HydroLakesSearchTest {
    /** The reference: scan down from the top one block at a time (the fill loop before the search). */
    private static HydroLakes.Mask scan(HydroModel m, long[] interior, int top, int bottom, boolean[] excluded, int x0, int z0, int w, int h) {
        for (int level = top; level > bottom; level--) {
            HydroLakes.Mask mask = HydroLakes.fill(m, interior, level, excluded, x0, z0, w, h);
            if (mask != HydroLakes.FAILED) return mask;
        }
        return HydroLakes.FAILED;
    }

    private static void assertSameMask(HydroLakes.Mask a, HydroLakes.Mask b, String what) {
        if (a == HydroLakes.FAILED || b == HydroLakes.FAILED) { assertSame(a, b, what); return; }
        assertEquals(a.level, b.level, what + " level");
        assertEquals(a.count, b.count, what + " count");
        assertArrayEquals(a.bits, b.bits, what + " columns");
        assertEquals(a.depthMax, b.depthMax, what + " depth max");
        assertEquals(a.depthMean, b.depthMean, what + " depth mean");
    }

    /** Heights rounded to float, like the game's density samples (the corner table path). */
    private static HydroModelTest.Field floats(HydroModelTest.Field f) {
        return new HydroModelTest.Field((x, z) -> (float) f.h().applyAsDouble(x, z), f.c(), f.t(), f.m(), f.e());
    }

    @Test void searchFindsTheLevelOfTheDownwardScan() {
        List<HydroModelTest.Field> fields = new ArrayList<>();
        for (long seed : new long[]{1, 42}) { fields.add(HydroModelTest.rugged(seed)); fields.add(floats(HydroModelTest.rugged(seed))); }
        fields.add(HydroModelTest.pitPlane(0, 0.15));
        fields.add(floats(HydroModelTest.island(0, 0.1)));
        int groups = 0, failed = 0, held = 0, excludedRuns = 0;
        for (int fi = 0; fi < fields.size(); fi++) {
            HydroModel m = new HydroModel(fields.get(fi), 17 + fi);
            Set<Long> seen = new HashSet<>();
            Random random = new Random(fi);
            for (int j = -24; j <= 24; j += 6) for (int i = -24; i <= 24; i += 6) {
                long pit = m.terminal(HydroModel.node(i, j));
                if (pit == HydroModel.OCEAN_ID || pit == HydroModel.NONE || !seen.add(pit)) continue;
                HydroModel.Group g = m.group(pit);
                if (g == null || g.interior.length == 0 || g.flood.region.length > 4000) continue;
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (long k : g.flood.region) { minX = Math.min(minX, m.x(k)); maxX = Math.max(maxX, m.x(k)); minZ = Math.min(minZ, m.z(k)); maxZ = Math.max(maxZ, m.z(k)); }
                int x0 = minX - HydroLakes.MARGIN, z0 = minZ - HydroLakes.MARGIN;
                int w = maxX - minX + 2 * HydroLakes.MARGIN + 1, h = maxZ - minZ + 2 * HydroLakes.MARGIN + 1;
                int bottom = (int) Math.floor(m.h(g.deepest)), rim = (int) Math.ceil(g.flood.level) - 1;
                boolean[] none = new boolean[w * h];
                // Targets from below the bottom to far above the rim: holds at the top, a few blocks lower, never.
                for (int top : new int[]{bottom - 1, bottom + 1, (bottom + rim) / 2, rim, rim + 1, rim + 3, rim + 9, rim + 40}) {
                    HydroLakes.Mask a = HydroLakes.highest(m, g.interior, top, bottom, none, x0, z0, w, h);
                    HydroLakes.Mask b = scan(m, g.interior, top, bottom, none, x0, z0, w, h);
                    assertSameMask(b, a, "field " + fi + " pit " + pit + " top " + top);
                    if (a == HydroLakes.FAILED) failed++; else held++;
                }
                // Excluded columns (channels) remove seeds and split the water.
                boolean[] excluded = new boolean[w * h];
                for (int k = 0; k < excluded.length; k++) excluded[k] = random.nextInt(9) == 0;
                for (long k : g.interior) if (random.nextBoolean()) excluded[(m.x(k) - x0) + w * (m.z(k) - z0)] = true;
                for (int top : new int[]{rim, rim + 5}) {
                    assertSameMask(scan(m, g.interior, top, bottom, excluded, x0, z0, w, h),
                            HydroLakes.highest(m, g.interior, top, bottom, excluded, x0, z0, w, h), "field " + fi + " pit " + pit + " excluded top " + top);
                    excludedRuns++;
                }
                groups++;
            }
        }
        System.out.println("LAKE_SEARCH groups " + groups + " held " + held + " failed " + failed + " excluded " + excludedRuns);
        assertTrue(groups > 40 && held > 40 && failed > 40, "both outcomes covered: groups " + groups + " held " + held + " failed " + failed);
    }

    @Test void cornerTableReturnsTheInputHeightEverywhere() {
        // Float heights (the table) and double heights (the record cache), far coordinates, slot conflicts.
        java.util.function.ToDoubleBiFunction<Integer, Integer> f = (x, z) -> 70 + 40 * Math.sin(x * 0.0137) * Math.cos(z * 0.0091) + (x ^ z) % 7 * 0.125;
        for (boolean asFloat : new boolean[]{true, false}) {
            int[] calls = {0};
            HydroModelTest.Field field = new HydroModelTest.Field((x, z) -> { calls[0]++; double v = f.applyAsDouble(x, z); return asFloat ? (float) v : v; },
                    (x, z) -> 0, 0, 0, 0);
            BaseTerrain t = new BaseTerrain(field);
            int[] xs = {0, -4, 4, 2044, 2048, -2048, -2052, 29_999_996, -30_000_000, 33_554_428, -33_554_432, 40_000_000, -40_000_000};
            for (int round = 0; round < 3; round++)
                for (int x : xs) for (int z : xs) {
                    double want = asFloat ? (float) f.applyAsDouble(x, z) : f.applyAsDouble(x, z);
                    assertEquals(Double.doubleToRawLongBits(want), Double.doubleToRawLongBits(t.corner(x, z)), "corner " + x + "," + z + " float " + asFloat);
                    // Same slot (low 9 bits of the corner index), another corner: never aliased.
                    assertEquals(Double.doubleToRawLongBits(asFloat ? (float) f.applyAsDouble(x + 2048, z) : f.applyAsDouble(x + 2048, z)),
                            Double.doubleToRawLongBits(t.corner(x + 2048, z)), "conflict " + x + "," + z);
                }
            // Off-grid positions are not corners of the table; they still return the input.
            assertEquals(asFloat ? (float) f.applyAsDouble(3, 5) : f.applyAsDouble(3, 5), t.corner(3, 5));
            // A window of 2048 blocks is cached without conflicts (float heights): a second pass computes nothing.
            calls[0] = 0;
            BaseTerrain w = new BaseTerrain(field);
            for (int z = -1024; z < 1024; z += 4) for (int x = 5000; x < 7048; x += 4) w.corner(x, z);
            int first = calls[0];
            for (int z = -1024; z < 1024; z += 4) for (int x = 5000; x < 7048; x += 4) w.corner(x, z);
            if (asFloat) assertEquals(first, calls[0], "second pass over the window hits the table");
        }
    }
}
