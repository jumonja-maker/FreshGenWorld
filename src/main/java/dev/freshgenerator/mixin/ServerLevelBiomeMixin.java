package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.hydrology.HydroIce;
import dev.freshgenerator.hydrology.WorldBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Current world only: the level's fallback biome resolver for unloaded chunks caches per thread (WorldBiomes:
 * explorer maps no longer stall the server thread), in-game freezing keeps a lake decided open at generation
 * open (HydroIce.keepsFreezing), and snowfall leaves the hot ground of active volcanoes bare (LandformHeat). */
@Mixin(ServerLevel.class)
abstract class ServerLevelBiomeMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/BiomeSource;createUncachedResolver(Lnet/minecraft/world/level/levelgen/RandomState;)Lnet/minecraft/world/level/biome/BiomeResolver;"), require = 1)
    private BiomeResolver freshgenerator$cachedFallback(BiomeSource source, RandomState state, Operation<BiomeResolver> original,
                                                       @Local(argsOnly = true) LevelStem stem) {
        return WorldBiomes.fallback(source, state, stem.generator(), original.call(source, state));
    }

    @WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"), require = 1)
    private boolean freshgenerator$lakeDecision(Biome biome, LevelReader reader, BlockPos pos, Operation<Boolean> original) {
        return HydroIce.keepsFreezing((ServerLevel) (Object) this, pos, original.call(biome, reader, pos));
    }

    /** Snowfall in the game leaves the hot ground of an active volcano bare, as generation did. */
    @WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"), require = 1)
    private boolean freshgenerator$hotGround(Biome biome, LevelReader reader, BlockPos pos, Operation<Boolean> original) {
        ServerLevel level = (ServerLevel) (Object) this;
        return original.call(biome, reader, pos) && !dev.freshgenerator.landform.LandformHeat.hot(level, level.getChunkSource().getGenerator(), pos);
    }
}
