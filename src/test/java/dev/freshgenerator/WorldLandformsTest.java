package dev.freshgenerator;

import dev.freshgenerator.core.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** World 1.0 (user request 26.11, docs/world-1.0.md): dunes, volcanoes and craters, great chambers. */
class WorldLandformsTest {
    @Test void dunesAreBoundedAsymmetricAndSeeded() {
        DuneField a = new DuneField(42, 72), b = new DuneField(42, 72), c = new DuneField(43, 72);
        double max = 0, diff = 0;
        for (int i = 0; i < 4000; i++) {
            double x = i * 13.7, z = i * 7.3;
            double h = a.height(x, z);
            assertTrue(h >= 0 && h <= 1.0000001, "dune height in [0, 1]: " + h);
            assertEquals(h, b.height(x, z), 0, "same seed, same dunes");
            diff += Math.abs(h - c.height(x, z));
            max = Math.max(max, h);
        }
        assertTrue(max > 0.8, "some crests reach full height");
        assertTrue(diff > 100, "another seed gives other dunes");
        // The slip face is steeper than the windward slope: the crest sits at 72% of the period.
        assertTrue(DuneField.CREST > 0.5);
    }

    @Test void landformsAreDeterministicAndStayWithinTheWorld() {
        Landforms m = new Landforms(31337);
        Landforms.Ground flat = (x, z) -> 70;
        int volcanoes = 0, craters = 0;
        var cache = new Landforms.MapCache();
        for (int j = -8; j < 8; j++) for (int i = -8; i < 8; i++) {
            var v = m.volcano(i, j, flat);
            if (v != null) {
                volcanoes++;
                assertTrue(v.reach() < Landforms.VOLCANO_CELL * 1.2, "a volcano stays near its cell");
                double top = m.apply(v.x, v.z, 70, flat, cache);
                assertTrue(top <= Landforms.SOFT_TOP + Landforms.SOFT_ROOM + 1e-9, "summit under the soft ceiling: " + top);
            }
            var c = m.crater(i, j, flat);
            if (c != null) {
                craters++;
                assertTrue(c.depth > 0 && c.depth <= 60 && c.rim <= 16, "crater proportions");
                double floor = m.apply(c.x, c.z, 70, flat, cache);
                assertTrue(floor < 70, "a crater floor lies below the old ground");
            }
        }
        assertTrue(volcanoes > 0 && craters > 0, "both kinds appear in 32 km");
        assertTrue(volcanoes < 120 && craters < 80, "and stay rare: " + volcanoes + " / " + craters);
        var again = new Landforms(31337);
        for (int i = 0; i < 6; i++) {
            var v1 = m.volcano(i, 2, flat); var v2 = again.volcano(i, 2, flat);
            assertEquals(v1 == null, v2 == null);
            if (v1 != null) { assertEquals(v1.x, v2.x); assertEquals(v1.height, v2.height, 0); }
        }
    }

    @Test void craterOnASlopeCutsACrescentScarp() {
        Landforms m = new Landforms(7);
        Landforms.Ground slope = (x, z) -> 100 + 0.2 * x;
        var cache = new Landforms.MapCache();
        // Find a crater and compare the uphill and downhill rims: the bowl is cut at a fixed height below the ground at
        // the centre, so the uphill wall is high, the downhill side opens.
        for (int j = -6; j < 6; j++) for (int i = -6; i < 6; i++) {
            var c = m.crater(i, j, slope);
            if (c == null || c.age == Landforms.Age.ANCIENT) continue;
            int ux = (int) (c.x + c.radius * 0.5), dx = (int) (c.x - c.radius * 0.5);
            double up = slope.at(ux, c.z) - m.apply(ux, c.z, slope.at(ux, c.z), slope, cache);
            double down = slope.at(dx, c.z) - m.apply(dx, c.z, slope.at(dx, c.z), slope, cache);
            assertTrue(up > down, "the uphill side is cut deeper than the downhill side: " + up + " vs " + down);
            return;
        }
        fail("no crater found in 12 x 12 cells");
    }

    @Test void chambersHaveAHollowCentreAndManyConduits() {
        CaveRooms rooms = new CaveRooms(31337);
        int found = 0;
        for (int j = -6; j < 6 && found < 5; j++) for (int i = -6; i < 6 && found < 5; i++) {
            var r = rooms.room(i, j);
            if (r == null) continue;
            found++;
            assertTrue(r.conduitCount >= 40 && r.conduitCount <= 90, "40-90 conduits: " + r.conduitCount);
            assertTrue(rooms.density(r, r.x, r.y + 2, r.z) < 0, "the dome is open above the centre");
            assertTrue(rooms.density(r, r.x, r.y + r.up + 30, r.z) > 0, "rock well above the dome");
            assertTrue(r.y >= CaveRooms.Y_LOW && r.y <= CaveRooms.Y_HIGH);
        }
        assertTrue(found > 0, "chambers exist");
    }

    @Test void cellsHaveNoTwinsMirroredThroughTheOrigin() {
        // A xor of the products i*A ^ j*B gave the cell (-i, -j) the seed of (i, j) whenever i and j were odd: a quarter
        // of the chambers, volcanoes and craters had an identical twin (seed 31337: one chamber at X1212 Z-355 and at
        // X-228 Z1085).
        CaveRooms rooms = new CaveRooms(31337);
        Landforms forms = new Landforms(31337);
        Landforms.Ground flat = (x, z) -> 70;
        int roomPairs = 0, formPairs = 0;
        for (int j = 1; j < 12; j += 2) for (int i = -11; i < 12; i += 2) {
            var a = rooms.room(i, j); var b = rooms.room(-i, -j);
            if (a != null && b != null) { roomPairs++; assertFalse(a.rx == b.rx && a.up == b.up && a.conduitCount == b.conduitCount, "chamber twin " + i + "," + j); }
            var v = forms.volcano(i, j, flat); var w = forms.volcano(-i, -j, flat);
            if (v != null && w != null) { formPairs++; assertFalse(v.height == w.height && v.radius == w.radius, "volcano twin " + i + "," + j); }
            var c = forms.crater(i, j, flat); var d = forms.crater(-i, -j, flat);
            if (c != null && d != null) { formPairs++; assertFalse(c.radius == d.radius && c.depth == d.depth, "crater twin " + i + "," + j); }
        }
        assertTrue(roomPairs > 0 && formPairs > 0, "pairs to compare: " + roomPairs + " / " + formPairs);
    }

    @Test void domeTestCoversTheChamberAndNothingFarAway() {
        // Deep structures (ancient city, trial chambers) do not start where a dome reaches their volume (DeepStructureMixin).
        CaveRooms rooms = new CaveRooms(31337);
        CaveRooms.Room r = null;
        for (int j = -6; j < 6 && r == null; j++) for (int i = -6; i < 6 && r == null; i++) r = rooms.room(i, j);
        assertNotNull(r);
        assertTrue(CaveRooms.domeTouches(r, r.x - 1, r.y - 1, r.z - 1, r.x + 1, r.y + 1, r.z + 1), "the centre");
        assertTrue(CaveRooms.domeTouches(r, r.x + r.rx - 2, r.y, r.z, r.x + r.rx + 50, r.y + 1, r.z + 1), "a box touching the wall");
        assertFalse(CaveRooms.domeTouches(r, r.x - 10, r.y + r.up + 2, r.z - 10, r.x + 10, r.y + r.up + 40, r.z + 10), "above the dome");
        assertFalse(CaveRooms.domeTouches(r, r.x + CaveRooms.MAX_RADIUS + 1, r.y - 5, r.z, r.x + 200, r.y + 5, r.z + 1), "beyond the widest dome");
        assertTrue(rooms.domesTouch(r.x - 1, r.y - 1, r.z - 1, r.x + 1, r.y + 1, r.z + 1, (i, j) -> rooms.room(i, j)), "found through the cells");
    }

    @Test void cubicLatticeIsExactOnLinearFields() {
        // Catmull-Rom (the formula of LatticeDensity's cubic mode) reproduces linear fields exactly: no facets, no
        // overshoot on a plane. The game class itself needs the game bootstrap, so the formula is checked here.
        double p0 = 1, p1 = 2, p2 = 3, p3 = 4, t = 0.37;
        double v = p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
        assertEquals(2.37, v, 1e-12);
    }
}
