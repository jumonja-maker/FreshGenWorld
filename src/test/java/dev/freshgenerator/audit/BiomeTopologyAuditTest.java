package dev.freshgenerator.audit;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeTopologyAuditTest {
    @Test void enclosedIslandHasMeasuredAreaAndSingleSurroundingBiome() {
        int[] raster = {0,0,0,0,0, 0,1,1,0,0, 0,1,1,0,0, 0,0,0,0,0, 0,0,0,0,0};
        var parts = BiomeTopologyAudit.components(raster, 5);
        assertEquals(2, parts.size());
        var island = parts.stream().filter(p -> p.id() == 1).findFirst().orElseThrow();
        assertEquals(4, island.cells()); assertFalse(island.boundary());
        assertEquals(Set.of(0), island.surrounding());
        assertEquals(1, island.minX()); assertEquals(2, island.maxZ());
    }
    @Test void clippedPatchIsNotReportedAsAnEnclosedIsland() {
        int[] raster = {0,1,0, 0,1,0, 0,0,0};
        var island = BiomeTopologyAudit.components(raster, 3).stream().filter(p -> p.id() == 1).findFirst().orElseThrow();
        assertEquals(2, island.cells()); assertTrue(island.boundary());
    }
    @Test void diagonalContactDoesNotMergeTwoIslandsButRealNarrowCorridorDoes() {
        int[] diagonal = {0,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,0,0, 0,0,0,0,0};
        assertEquals(2, BiomeTopologyAudit.components(diagonal, 5).stream().filter(p -> p.id() == 1).count());
        diagonal[7] = 1;
        assertEquals(1, BiomeTopologyAudit.components(diagonal, 5).stream().filter(p -> p.id() == 1).count());
    }
    @Test void JunctionReportsAllTouchingBiomesWithoutClaimingSingleBiomeSurrounding() {
        int[] raster = {0,0,2, 0,1,2, 0,0,2};
        var island = BiomeTopologyAudit.components(raster, 3).stream().filter(p -> p.id() == 1).findFirst().orElseThrow();
        assertEquals(Set.of(0,2), island.surrounding());
    }
    @Test void malformedRastersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BiomeTopologyAudit.components(new int[3], 2));
        assertThrows(IllegalArgumentException.class, () -> BiomeTopologyAudit.components(new int[0], 0));
    }
}
