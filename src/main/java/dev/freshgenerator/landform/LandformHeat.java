package dev.freshgenerator.landform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Hot ground of the current world's active volcanoes, where no snow lies (their summit crater and fresh lava flows):
 * asked by the snow feature at generation and by snowfall in the game. */
public final class LandformHeat {
    private LandformHeat() {}

    public static boolean hot(ServerLevel level, ChunkGenerator generator, BlockPos pos) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return false;
        LandformDensity.World world = LandformDensity.world(level.getChunkSource().randomState(), noise.generatorSettings().value());
        return world != null && world.hot(pos.getX(), pos.getZ());
    }
}
