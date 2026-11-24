package dev.freshgenerator.hydrology;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/** Sand, gravel and clay disks of the current world stay on the bed of the water they were placed in. The native
 * disks (radius 2-6) are sized for the vanilla rivers, 7+ blocks wide: in a creek 2-4 wide they spread over the
 * banks as round patches of sand and gravel on grass (player report 23.11). A disk placed under water here changes
 * only the columns that hold water and the columns right beside them - a sand or gravel bar along the stream, not a
 * circle. Disks not placed under water (grass on mud) and other worlds keep the native rule. */
public final class WaterDisks {
    private static final int[] DX = {0, 1, -1, 0, 0}, DZ = {0, 0, 0, 1, -1};
    private WaterDisks() { }

    /** Whether a disk may change the column (x, z) between bottom (exclusive) and top. */
    public static boolean allowed(WorldGenLevel level, ChunkGenerator generator, BlockPos origin, int x, int z, int top, int bottom) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return true;
        if (!level.getFluidState(origin).is(FluidTags.WATER)) return true;
        RandomState state = level.getLevel().getChunkSource().randomState();
        if (HydrologyDensity.field(state, noise.generatorSettings().value()) == null) return true;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int d = 0; d < DX.length; d++)
            for (int y = bottom + 1; y <= top + 1; y++)
                if (level.getFluidState(p.set(x + DX[d], y, z + DZ[d])).is(FluidTags.WATER)) return true;
        return false;
    }
}
