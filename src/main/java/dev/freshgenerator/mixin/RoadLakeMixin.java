package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.road.RoadLakeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No native lava lake on a village road in the current world (RoadLakeGuard); generation-time lakes only, as for the
 * village plots (VillageLakeMixin). No native spring and no lava lake opens in the wall of a rift. */
@Mixin(FeaturePlacer.class)
abstract class RoadLakeMixin {
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
            require = 2)
    private boolean freshgenerator$keepLavaOffRoads(Feature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos at,
                                                    Operation<Boolean> original, PlacedFeature placed, RandomSource sourceRandom, BlockPos origin, boolean biomeCheck) {
        if (biomeCheck && feature instanceof LakeFeature
                && placed.feature().unwrapKey().map(k -> k.identifier().toString()).orElse("").equals("minecraft:lake_lava")
                && (RoadLakeGuard.surfaceLakeOff(level, generator, placed) || !RoadLakeGuard.permit(level, generator, at)
                    || RoadLakeGuard.inRiftWall(level, generator, at, 12))) return false;
        if (biomeCheck && feature instanceof net.minecraft.world.level.levelgen.feature.SpringFeature
                && RoadLakeGuard.inRiftWall(level, generator, at, 4)) return false;
        return original.call(feature, level, generator, random, at);
    }
}
