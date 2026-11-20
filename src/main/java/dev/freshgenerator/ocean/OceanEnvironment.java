package dev.freshgenerator.ocean;

import dev.freshgenerator.core.EcotoneModel;
import dev.freshgenerator.core.OceanEcology;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** 0.9.1: per-decoration-pass ocean community filter for the FreshGenerator world only.
 * Reads the generated floor height and substrate of the target column; never writes. */
public final class OceanEnvironment {
    private final WorldGenLevel level;
    private final long seed;
    private OceanEnvironment(WorldGenLevel level, long seed) { this.level = level; this.seed = seed; }

    public static OceanEnvironment create(WorldGenLevel level, ChunkGenerator generator) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
        String id = noise.generatorSettings().unwrapKey().map(k -> k.identifier().toString()).orElse("");
        if (!id.equals("freshgenerator:highlands_v2")) return null;
        return new OceanEnvironment(level, level.getSeed());
    }
    public static boolean hint(String configuredFeature) { return OceanEcology.community(configuredFeature) != OceanEcology.Community.NONE; }

    public boolean retain(String configuredFeature, BlockPos at) {
        var community = OceanEcology.community(configuredFeature);
        if (community == OceanEcology.Community.NONE) return true;
        int x = at.getX(), z = at.getZ();
        int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        // The real water column above the floor, wherever its surface is (0.9.1 audit E1:
        // depth from the sea level misjudged rivers, lakes and swamps above or below it).
        int depth = 0;
        var cursor = new BlockPos.MutableBlockPos(x, top, z);
        while (depth < 64 && level.getFluidState(cursor).is(FluidTags.WATER)) { depth++; cursor.move(0, 1, 0); }
        if (depth <= 0) return true;
        var ground = ground(level.getBlockState(new BlockPos(x, top - 1, z)));
        double patch = EcotoneModel.noise(seed ^ 0x6B3E1F5AL, x / 56.0, z / 56.0);
        double keep = OceanEcology.suitability(community, depth, ground, patch);
        return OceanEcology.roll(seed, x, z, community.ordinal() + 17) < keep;
    }
    private static OceanEcology.Ground ground(BlockState s) {
        if (s.is(Blocks.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.RED_SAND)) return OceanEcology.Ground.SEDIMENT;
        if (s.is(Blocks.CLAY) || s.is(Blocks.MUD)) return OceanEcology.Ground.FINE;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.SMOOTH_BASALT)) return OceanEcology.Ground.ROCK;
        return OceanEcology.Ground.OTHER;
    }
}
