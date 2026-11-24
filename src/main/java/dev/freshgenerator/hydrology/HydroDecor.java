package dev.freshgenerator.hydrology;

import com.mojang.serialization.MapCodec;
import dev.freshgenerator.core.hydro.HydroClimate;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.core.hydro.HydroModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/** Life by the water: lily pads in patches on shallow, still lakes and slow wide rivers of temperate, tropical,
 * wetland and savanna landscapes (never on frozen water), and a green ribbon (grass, short plants, sugar cane) on the
 * sand banks of rivers crossing deserts, badlands and savannas. Runs once per chunk; a no-op outside the current
 * world. */
public record HydroDecor() implements Feature {
    public static final Identifier ID = Identifier.parse("freshgenerator:hydro_decor");
    public static final MapCodec<HydroDecor> CODEC = MapCodec.unit(new HydroDecor());

    @Override public MapCodec<HydroDecor> codec() { return CODEC; }

    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return false;
        HydroField field = HydrologyDensity.field(level.getLevel().getChunkSource().randomState(), noise.generatorSettings().value());
        if (field == null) return false;
        int x0 = origin.getX() & ~15, z0 = origin.getZ() & ~15;
        HydroField.Column c = new HydroField.Column();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        boolean placed = false;
        // The pattern of chosen columns depends on the world seed (and a salt of this layer), never on the chunk
        // order: the same suitable water gives different pads in different worlds.
        long salt = level.getSeed() * 0x2545F4914F6CDD1DL ^ 0x5DEECE66DL;
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = x0 + dx, z = z0 + dz;
            field.column(x, z, c);
            HydroClimate.Setting s = field.model().setting(HydroModel.nodeAt(x, z));
            if (!c.wet()) {
                // Green ribbon along rivers of dry land: wet sand turns to grass with short plants, sugar cane at
                // the water's edge (audit 22.11 G-2: desert rivers had bare sand banks).
                if ((s == HydroClimate.Setting.DESERT || s == HydroClimate.Setting.BADLANDS || s == HydroClimate.Setting.SAVANNA)
                        && c.wetDistance <= RIPARIAN && riparian(level, x, z, c.wetDistance, salt, at)) placed = true;
                continue;
            }
            // Lily pads: shallow calm water only (2 blocks), in patches (a low-frequency seeded noise), never spread
            // evenly over a whole lake (audit 22.11 G-4).
            if (c.water - c.terrain > 2.5) continue;
            double chance;
            if (c.kind == HydroField.LAKE) chance = 0.06;
            else if (c.kind == HydroField.RIVER && c.width >= 6) chance = 0.015;
            else continue;
            if (s == HydroClimate.Setting.WETLAND) chance *= 1.8;
            else if (s != HydroClimate.Setting.TEMPERATE && s != HydroClimate.Setting.TROPICAL && s != HydroClimate.Setting.SAVANNA) continue;
            chance *= 6 * HydroClimate.smooth(0.55, 0.85, patch(salt, x / 14.0, z / 14.0));
            if (unit(salt, x, z) >= chance) continue;
            at.set(x, c.water, z);
            // Never on water that freezes (code audit 24.11 F-10): a pad survives on the ice the next stage puts under
            // it. A lake freezes as a whole (HydroIce), a river by the native rule of its biome.
            if (c.kind == HydroField.LAKE) {
                HydroModel.Group lake = field.lakeAt(x, z);
                if (lake != null && lake.lakeLevel() == c.water) {
                    int ice = lake.ice;
                    if (ice == 0) lake.ice = ice = HydroIce.decide(lake, generator, level.getLevel().getChunkSource().randomState(), level.getSeaLevel());
                    if (ice == HydroModel.Group.ICE_FROZEN) continue;
                }
            } else if (!level.getBiome(at).value().warmEnoughToRain(at, level.getSeaLevel())) continue;
            if (!level.getBlockState(at).is(Blocks.WATER) || !level.getFluidState(at).isSource()) continue;
            at.set(x, c.water + 1, z);
            if (!level.getBlockState(at).isAir()) continue;
            level.setBlock(at, Blocks.LILY_PAD.defaultBlockState(), 2);
            placed = true;
        }
        return placed;
    }

    /** Bank width (blocks from the water) of the green ribbon along rivers of dry land. */
    static final double RIPARIAN = 3.5;

    private static boolean riparian(WorldGenLevel level, int x, int z, double distance, long salt, BlockPos.MutableBlockPos at) {
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        at.set(x, top, z);
        var ground = level.getBlockState(at);
        if (!ground.is(Blocks.SAND) && !ground.is(Blocks.RED_SAND)) return false;
        double u = unit(salt ^ 0x5A7E, x, z), near = 1 - distance / RIPARIAN;
        boolean byWater = false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(at.relative(d)).is(net.minecraft.tags.FluidTags.WATER)) { byWater = true; break; }
        at.set(x, top + 1, z);
        if (!level.getBlockState(at).isAir()) return false;
        if (byWater && u < 0.22) {
            // Sugar cane on the wet sand right at the edge (native survival rule: water next to the block below).
            int height = 1 + (int) (unit(salt ^ 0xCA4E, x, z) * 3);
            for (int i = 0; i < height; i++) {
                at.set(x, top + 1 + i, z);
                if (!level.getBlockState(at).isAir() || !Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, at)) break;
                level.setBlock(at, Blocks.SUGAR_CANE.defaultBlockState(), 2);
            }
            return true;
        }
        if (u >= 0.25 + 0.55 * near) return false;
        at.set(x, top, z);
        level.setBlock(at, unit(salt ^ 0xD127, x, z) < 0.18 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        double plant = unit(salt ^ 0x9A55, x, z);
        at.set(x, top + 1, z);
        var state = plant < 0.45 ? Blocks.SHORT_GRASS.defaultBlockState() : plant < 0.55 ? Blocks.FERN.defaultBlockState()
                : plant < 0.62 ? Blocks.BUSH.defaultBlockState() : null;
        if (state != null && state.canSurvive(level, at)) level.setBlock(at, state, 2);
        return true;
    }
    /** Seeded uniform value in [0, 1) of a column. */
    private static double unit(long salt, int x, int z) {
        long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ salt;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        return (h >>> 11) * 0x1.0p-53;
    }
    /** Smooth seeded value noise in [0, 1] (patches). */
    private static double patch(long salt, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz, sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double a = unit(salt ^ 0x7A7C, ix, iz), b = unit(salt ^ 0x7A7C, ix + 1, iz), c = unit(salt ^ 0x7A7C, ix, iz + 1), d = unit(salt ^ 0x7A7C, ix + 1, iz + 1);
        return (a + (b - a) * sx) * (1 - sz) + (c + (d - c) * sx) * sz;
    }
}
