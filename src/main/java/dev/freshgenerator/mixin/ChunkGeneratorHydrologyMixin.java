package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.hydrology.HydroBiomes;
import dev.freshgenerator.hydrology.HydrologyDensity;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** River biomes from the drainage model when a chunk's biomes are filled (current world only). */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorHydrologyMixin {
    @WrapOperation(method = "doCreateBiomes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;)V"), require = 1)
    private void freshgenerator$rivers(ChunkAccess chunk, BiomeResolver resolver, Operation<Void> original,
                                      @Local(argsOnly = true) RandomState state) {
        if ((Object) this instanceof NoiseBasedChunkGenerator generator) {
            HydroField field = HydrologyDensity.field(state, generator.generatorSettings().value());
            if (field != null) resolver = HydroBiomes.wrap(resolver, field, generator.getBiomeSource());
        }
        original.call(chunk, resolver);
    }
}
