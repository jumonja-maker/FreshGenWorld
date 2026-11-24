package dev.freshgenerator.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeTableTest {
    @Test void valuesRoundTripIncludingNegativeIndicesAndSpecialFloats() {
        NodeTable t = new NodeTable();
        int[][] nodes = {{0, 0}, {-1, -1}, {255, -256}, {-4096 * 256, 4095 * 256 + 255}, {123456, -654321}};
        float[] values = {0f, -0f, 1.5f, -2912.25f, Float.MIN_VALUE};
        for (int k = 0; k < nodes.length; k++) {
            assertTrue(Float.isNaN(t.get(nodes[k][0], nodes[k][1])), "empty table");
            t.put(nodes[k][0], nodes[k][1], values[k]);
        }
        for (int k = 0; k < nodes.length; k++)
            assertEquals(Float.floatToRawIntBits(values[k]), Float.floatToRawIntBits(t.get(nodes[k][0], nodes[k][1])), "node " + k);
    }

    @Test void slotConflictsNeverReturnAnotherNodesValue() {
        NodeTable t = new NodeTable();
        // Same low 8 bits of both indices: the same slot, different tags.
        t.put(5, 7, 1f);
        assertTrue(Float.isNaN(t.get(5 + 256, 7)));
        assertTrue(Float.isNaN(t.get(5, 7 - 256)));
        t.put(5 + 256, 7, 2f);
        assertEquals(2f, t.get(5 + 256, 7));
        assertTrue(Float.isNaN(t.get(5, 7)), "the overwritten node is recomputed, never aliased");
    }

    @Test void indicesBeyondTheTagRangeAreNotMemoized() {
        NodeTable t = new NodeTable();
        int far = 4096 * 256; // first index past the 13-bit tag range
        t.put(far, 0, 3f);
        assertTrue(Float.isNaN(t.get(far, 0)));
        assertTrue(Float.isNaN(t.get(far - 8192 * 256, 0)), "no alias with the wrapped tag");
        t.put(0, -far - 1, 4f);
        assertTrue(Float.isNaN(t.get(0, -far - 1)));
    }
}
