package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.hydrology.WaterDisks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Water disks of the current world stay on the bed and the edge of their water (WaterDisks). */
@Mixin(DiskFeature.class)
abstract class DiskFeatureMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/DiskFeature;placeColumn(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;IILnet/minecraft/core/BlockPos$MutableBlockPos;)Z"), require = 1)
    private boolean freshgenerator$bedOnly(DiskFeature disk, WorldGenLevel level, RandomSource random, int top, int bottom, BlockPos.MutableBlockPos pos,
                                           Operation<Boolean> original, @Local(argsOnly = true) ChunkGenerator generator, @Local(argsOnly = true) BlockPos origin) {
        if (!WaterDisks.allowed(level, generator, origin, pos.getX(), pos.getZ(), top, bottom)) return false;
        return original.call(disk, level, random, top, bottom, pos);
    }
}
