package dev.freshgenerator.mixin;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The aquifers' lava level is a constant in the game (Y-54, ten blocks above the native bottom). Terrain settings that
 * generate deeper (the current world, Y-112 since 26.11) keep the same relation: lava ten blocks above their own
 * bottom, so the lava pools stay at the floor of the world instead of flooding the 58 deepest blocks of every cave. */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class DeepLavaMixin {
    @Inject(method = "createFluidPicker", at = @At("HEAD"), cancellable = true, require = 1)
    private static void freshgenerator$deepLava(NoiseGeneratorSettings settings, CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
        int minY = settings.noiseSettings().minY();
        // Only the current world's settings (code audit 26.11 KO-03: another deep world - a datapack or mod with its floor
        // at Y-128 - got its lava moved too). They carry the landform debug label.
        if (minY >= -64 || settings.debugFunctions().functions().stream().noneMatch(d -> dev.freshgenerator.landform.LandformDensity.LABEL.equals(d.label()))) return;
        int lavaLevel = minY + 10, seaLevel = settings.seaLevel();
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        cir.setReturnValue((x, y, z) -> y < Math.min(lavaLevel, seaLevel) ? lava : sea);
    }
}
