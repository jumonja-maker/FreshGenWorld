package dev.freshgenerator.mixin;

import dev.freshgenerator.landform.LandformSurfaceScope;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No native eroded-badlands pillar inside a rift or a crater bowl of the current world (LandformSurfaceScope). */
@Mixin(MaterialSystem.class)
abstract class BadlandsPillarMixin {
    @Inject(method = "erodedBadlandsExtension", at = @At("HEAD"), cancellable = true, require = 1)
    private void freshgenerator$noPillarInLandform(BlockColumn column, int x, int z, int height, LevelHeightAccessor level, CallbackInfo ci) {
        if (LandformSurfaceScope.noPillar(x, z)) ci.cancel();
    }
}
