package dev.freshgenerator.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.IcebergFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ocean ice is already generated as a world-coordinate volume during surfaces.
 * The original feature remains available to commands and other world generators. */
@Mixin(IcebergFeature.class)
abstract class GlacialIcebergMixin {
    @Inject(method="place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
            at=@At("HEAD"), cancellable=true, require=1)
    private void freshgenerator$replaceGeneratedIceberg(WorldGenLevel level, ChunkGenerator generator,
            RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if(level instanceof WorldGenRegion && generator instanceof NoiseBasedChunkGenerator noise
                && noise.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("")
                    .equals("freshgenerator:highlands_v2"))
            cir.setReturnValue(false);
    }
}
