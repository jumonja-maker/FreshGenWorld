package dev.freshgenerator.mixin;

import dev.freshgenerator.ice.GlacialSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The scoped glacial pass replaces the original frozen-ocean column extension. */
@Mixin(MaterialSystem.class)
abstract class GlacialMaterialMixin {
    @Inject(method="frozenOceanExtension", at=@At("HEAD"), cancellable=true, require=1)
    private void freshgenerator$replaceFrozenOceanExtension(int minSurfaceLevel, Biome biome,
            BlockColumn column, BlockPos.MutableBlockPos pos, int x, int z, int topY, CallbackInfo ci) {
        if(GlacialSurface.active())ci.cancel();
    }
}
