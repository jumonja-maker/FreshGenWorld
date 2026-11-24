package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;

/** Structure terrain adaptation that never fills the water of a river or lake of the current world (audit 24.11:
 * the thin beard of a snowy-taiga camp at seed 31337 X1872 Z-213 filled 20 columns of a river with dirt and grass,
 * a dry dam across the channel; without structures the river ran through). Inside the water body of a wet column
 * (from a block under the ground to the water level) the adaptation may still carve, but its fill is dropped. Deep
 * structures and the sea are not affected. */
public final class RiverBeardifier extends Beardifier {
    private static final ThreadLocal<HydroField.Column> COLUMN = ThreadLocal.withInitial(HydroField.Column::new);
    private final Beardifier inner;
    private final HydroField field;

    public RiverBeardifier(Beardifier inner, HydroField field) {
        super(List.of(), List.of(), null);
        this.inner = inner;
        this.field = field;
    }

    @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
        float v = inner.sampleValue(context, x, y, z);
        return v > 0 && inWater(x, y, z) ? 0 : v;
    }

    @Override public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
        inner.sampleVolume(context, output, volume);
        HydroField.Column c = COLUMN.get();
        for (int a = 0; a < volume.sizeX(); a++) for (int b = 0; b < volume.sizeZ(); b++) {
            boolean fill = false;
            for (int y = 0; y < volume.sizeY() && !fill; y++) fill = output.get(volume.indexUnchecked(a, y, b)) > 0;
            if (!fill) continue;
            field.column(volume.blockX(a), volume.blockZ(b), c);
            if (!c.wet()) continue;
            for (int y = 0; y < volume.sizeY(); y++) {
                int i = volume.indexUnchecked(a, y, b), by = volume.blockY(y);
                if (output.get(i) > 0 && by >= c.terrain - 1 && by <= c.water) output.set(i, 0);
            }
        }
    }

    private boolean inWater(int x, int y, int z) {
        HydroField.Column c = COLUMN.get();
        field.column(x, z, c);
        return c.wet() && y >= c.terrain - 1 && y <= c.water;
    }
}
