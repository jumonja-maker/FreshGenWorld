package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HydroBiomesTest {
    /** The chunk resolver of 26.3: a climate buffer of 4 x 96 x 4 quart cells read without a bounds check
     * (DensityVolume.indexUnchecked = y + (x + 4z) * 96). A cell above the top reads the bottom of the next column; past
     * the last column it throws inside the biome step of the chunk. */
    record ChunkBuffer(int qx0, int qy0, int qz0, List<Holder<Biome>> columns) implements BiomeResolver {
        @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
            int index = (qy - qy0) + ((qx - qx0) + 4 * (qz - qz0)) * 96;
            if (index < 0 || index >= 1536) throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for length 1536");
            return columns.get(index / 96);
        }
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome> holder(String name) { return (Holder<Biome>) (Holder<?>) Holder.direct(name); }

    @Test void aDeepValleyUnderAHighPeakReadsItsOwnColumnBelowTheWorldTop() {
        // Original ground Y283 and a valley floor at Y205 (78 blocks of incision) in every column of the chunk, the last
        // one (3, 3) included: the generation failure of seed 161803398 X-3605 Z-4377 and the deep_dark cells in the
        // gorges of icy peaks (audits 25.11 Opus N-1, N-2).
        List<Holder<Biome>> columns = new ArrayList<>();
        for (int i = 0; i < 16; i++) columns.add(holder("column " + i));
        int qx0 = -906, qz0 = -1076, qy0 = -16, maxQy = qy0 + 95;
        BiomeResolver chunk = new ChunkBuffer(qx0, qy0, qz0, columns);
        HydroField.Column c = new HydroField.Column();
        c.base = 283;
        c.terrain = 205;
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int qy = qy0; qy <= maxQy; qy++)
            assertEquals(columns.get(x + 4 * z), HydroBiomes.fromColumn(chunk, c, qx0 + x, qy, qz0 + z, null, maxQy),
                    "column " + x + "," + z + " cell " + qy);
        // Without the bound the shifted cell leaves the buffer: the cell at Y242 (between the floor and the original ground)
        // moves up 20 cells to quart 80, above the top quart 79 - the neighbour's bottom in (0, 0), past the end in (3, 3).
        int deep = 60;
        assertNotEquals(columns.get(0), HydroBiomes.fromColumn(chunk, c, qx0, deep, qz0, null, Integer.MAX_VALUE));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> HydroBiomes.fromColumn(chunk, c, qx0 + 3, deep, qz0 + 3, null, Integer.MAX_VALUE));
    }
}
