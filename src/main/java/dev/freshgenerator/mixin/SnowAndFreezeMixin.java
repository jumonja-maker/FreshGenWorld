package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.hydrology.HydroIce;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lake ice decided per water body in the current world (HydroIce); everything else keeps the native rule. */
@Mixin(SnowAndFreezeFeature.class)
abstract class SnowAndFreezeMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z"), require = 1)
    private boolean freshgenerator$lakeIce(Biome biome, LevelReader reader, BlockPos pos, boolean edges, Operation<Boolean> original,
                                           @Local(argsOnly = true) WorldGenLevel level, @Local(argsOnly = true) ChunkGenerator generator) {
        return HydroIce.shouldFreeze(level, generator, pos, original.call(biome, reader, pos, edges));
    }
}
