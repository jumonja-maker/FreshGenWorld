package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IslandClimateTest {
    /** Sea everywhere except a round island (radius 600 at the origin), a large continent east of x = 3000, and a
     * thin isthmus (width 40) that joins a second island to the continent; temperature falls 0.0001 per block east. */
    static final class World implements IslandClimate.Inputs {
        public double height(int x, int z) {
            if (Math.hypot(x, z) < 600) return 70;                       // island A
            if (x > 3000) return 70;                                      // continent
            if (Math.hypot(x - 2300, z) < 500) return 70;                // island B
            if (x > 2750 && x <= 3000 && Math.abs(z) < 20) return 70;    // thin isthmus B - continent
            return 40;
        }
        public double temperature(int x, int z) { return -0.2 - 0.0001 * x; }
        public double moisture(int x, int z) { return 0.1 + 0.0002 * z; }
    }

    @Test void smallIslandTakesItsMeanClimate() {
        IslandClimate c = new IslandClimate(new World());
        double west = c.temperature(-450, 0), east = c.temperature(450, 0);
        // Locally the island spans -0.14..-0.26 (a climate border at -0.2 would cut it); now it is one climate.
        assertEquals(west, east, 0.005, "island climate is uniform: " + west + " vs " + east);
        assertEquals(-0.2, (west + east) / 2, 0.01, "the island mean");
        assertEquals(c.moisture(0, -450), c.moisture(0, 450), 0.02, "moisture uniform too");
    }

    @Test void mostlyWintryIslandIsWintry() {
        // Audit 23.11: with the mean, an island mostly below the frost line (-0.45) could end up on its warm side.
        // Here 60% of the island is frozen (-0.6) and the rest mild (-0.1): the mean is -0.40, the median -0.6.
        World base = new World();
        IslandClimate c = new IslandClimate(new IslandClimate.Inputs() {
            public double height(int x, int z) { return base.height(x, z); }
            public double temperature(int x, int z) { return x < 150 ? -0.6 : -0.1; }
            public double moisture(int x, int z) { return 0.1; }
        });
        for (int x : new int[]{-450, 0, 300, 450}) assertTrue(c.temperature(x, 0) < -0.45, "frozen at x " + x + ": " + c.temperature(x, 0));
    }

    @Test void continentAndSeaKeepTheirClimate() {
        IslandClimate c = new IslandClimate(new World());
        assertEquals(-0.2 - 0.0001 * 6000, c.temperature(6000, 1500), 1e-9, "large land unchanged");
        assertEquals(-0.2 - 0.0001 * 1200, c.temperature(1200, 1500), 1e-9, "open sea unchanged");
    }

    @Test void thinIsthmusDoesNotJoinAnIslandToTheContinent() {
        IslandClimate c = new IslandClimate(new World());
        assertTrue(c.islandCells(2300, 0) > 0, "island B is an island despite the isthmus: " + c.islandCells(2300, 0));
        assertEquals(c.temperature(1900, 0), c.temperature(2700, 0), 0.005, "island B uniform");
    }

    @Test void resultDoesNotDependOnQueryOrder() {
        IslandClimate a = new IslandClimate(new World()), b = new IslandClimate(new World());
        double fromWest = a.temperature(-500, 10), fromEast = b.temperature(500, -10);
        assertEquals(a.temperature(500, -10), fromEast, 0, "same value whatever cell resolved the island first");
        assertEquals(b.temperature(-500, 10), fromWest, 0);
    }
}
