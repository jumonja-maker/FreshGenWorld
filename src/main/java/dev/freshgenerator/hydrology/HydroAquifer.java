package dev.freshgenerator.hydrology;

import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;

import java.util.Objects;

/** Rivers and lakes enter the native noise fill at their own levels (open air above the carved ground only);
 * caves and the sea stay native. A column below a higher neighbour by two or more blocks gets the falling
 * water of the waterfall right away (visible before any fluid tick and in distant views); only step lips and
 * curtains are scheduled for fluid updates, flat lake and river surfaces stay still. */
public final class HydroAquifer implements Aquifer {
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState FLOWING = WATER.setValue(LiquidBlock.LEVEL, 1);
    private static final BlockState FALLING = WATER.setValue(LiquidBlock.LEVEL, 8);
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};
    private final Aquifer nativeAquifer;
    private final HydroField field;
    private final HydroField.Column column = new HydroField.Column(), neighbour = new HydroField.Column();
    private int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
    private int ground, water, curtain;
    private boolean lowerNeighbour;
    private boolean ours, schedule;

    public HydroAquifer(Aquifer nativeAquifer, HydroField field) {
        this.nativeAquifer = Objects.requireNonNull(nativeAquifer);
        this.field = Objects.requireNonNull(field);
    }

    private void load(int x, int z) {
        if (x == lastX && z == lastZ) return;
        lastX = x; lastZ = z;
        field.column(x, z, column);
        ground = (int) Math.ceil(column.terrain);
        water = column.wet() ? column.water : HydroModel.NO_WATER;
        int self = column.effectiveWater();
        curtain = HydroModel.NO_WATER;
        lowerNeighbour = false;
        for (int d = 0; d < 4; d++) {
            field.column(x + DX[d], z + DZ[d], neighbour);
            int w = neighbour.effectiveWater();
            if (w == HydroModel.NO_WATER || !neighbour.wetIncludingSea()) continue;
            if (self != HydroModel.NO_WATER && w >= self + 2 && w > curtain) curtain = w;
            if (water != HydroModel.NO_WATER && w < water) lowerNeighbour = true;
        }
        if (water != HydroModel.NO_WATER && !lowerNeighbour) {
            // a dry neighbour lower than our surface: water will run over the edge (lip)
            for (int d = 0; d < 4 && !lowerNeighbour; d++) {
                field.column(x + DX[d], z + DZ[d], neighbour);
                if (!neighbour.wetIncludingSea() && neighbour.terrain <= water) lowerNeighbour = true;
            }
        }
    }

    @Override
    public BlockState computeSubstance(int x, int y, int z, double density) {
        ours = false; schedule = false;
        if (density <= 0) {
            load(x, z);
            if (y >= ground - 1) {
                if (water != HydroModel.NO_WATER && y <= water) {
                    ours = true; schedule = y == water && lowerNeighbour;
                    return WATER;
                }
                int surface = Math.max(water, column.effectiveWater());
                if (curtain != HydroModel.NO_WATER && surface != HydroModel.NO_WATER && y > surface && y <= curtain) {
                    ours = true; schedule = true;
                    return y == curtain ? FLOWING : FALLING;
                }
            }
        }
        return nativeAquifer.computeSubstance(x, y, z, density);
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return ours ? schedule : nativeAquifer.shouldScheduleFluidUpdate();
    }
}
