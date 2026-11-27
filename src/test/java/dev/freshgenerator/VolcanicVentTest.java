package dev.freshgenerator;

import dev.freshgenerator.core.volcano.VolcanicVent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Volcanic vents instead of the native surface lava lakes (user request 26.11). */
class VolcanicVentTest {
    @Test void lavaNeverRunsOutAndTheShapesKeepTheirSize() {
        int cones = 0, pits = 0;
        for (long seed = 0; seed < 400; seed++) for (VolcanicVent.Kind kind : VolcanicVent.Kind.values()) {
            VolcanicVent v = new VolcanicVent(kind, seed);
            // The vent reaches at most a chunk beyond the centre chunk (centre at local 8: offsets up to 23).
            assertTrue(v.r <= 23, "radius " + v.r);
            if (kind == VolcanicVent.Kind.CONE) { assertTrue(v.size >= 3 && v.size <= 6, "cone height " + v.size); cones++; }
            else { assertTrue(v.size >= 5 && v.size <= 7, "pit depth " + v.size); pits++; }
            int lavaColumns = 0, maxTop = Integer.MIN_VALUE;
            for (int dz = -v.r; dz <= v.r; dz++) for (int dx = -v.r; dx <= v.r; dx++) {
                maxTop = Math.max(maxTop, v.top(dx, dz));
                int lava = v.lava(dx, dz);
                if (lava == VolcanicVent.NO_LAVA) continue;
                lavaColumns++;
                assertTrue(v.top(dx, dz) < lava, "lava over solid floor");
                int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] s : side) {
                    int x = dx + s[0], z = dz + s[1];
                    assertTrue(Math.abs(x) <= v.r && Math.abs(z) <= v.r, "lava at the grid edge");
                    assertTrue(v.lava(x, z) != VolcanicVent.NO_LAVA || v.top(x, z) >= lava,
                            "lava of " + kind + " seed " + seed + " runs out at " + x + "," + z);
                }
            }
            assertTrue(lavaColumns >= 4, "a lava pool in every vent: " + lavaColumns);
            if (kind == VolcanicVent.Kind.CONE) {
                assertEquals(v.size, maxTop, "the rim stands at the cone's height");
                assertTrue(v.lava(0, 0) == v.size - 1, "the lava a block below the rim");
            } else assertTrue(v.lava(0, 0) < -3, "the pit's lava well below the ground");
        }
        assertEquals(400, cones);
        assertEquals(400, pits);
    }
}
