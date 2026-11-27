package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.ice.GlacialSurface;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Set;

/** Current-chunk ice volumes during native surface generation, before features.
 * The invocation scope is always restored, including nested calls and failures. */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class GlacialSurfaceMixin {
    @WrapOperation(method="buildSurface", at=@At(value="INVOKE", target=
        "Lnet/minecraft/world/level/levelgen/material/MaterialSystem;buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;Ljava/util/Set;)V"), require=1)
    private void freshgenerator$glacialSurface(MaterialSystem system, RandomState state,
            BiomeManager biomes, WorldGenerationContext context, ChunkAccess chunk, NoiseChunk noise,
            MaterialRule rule, Set<Holder<Biome>> possibleBiomes, Operation<Void> original) {
        var generator=(NoiseBasedChunkGenerator)(Object)this;
        if(!generator.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("")
                .equals("freshgenerator:highlands_v2")) {
            original.call(system,state,biomes,context,chunk,noise,rule,possibleBiomes);
            return;
        }
        boolean previous=GlacialSurface.begin();
        // The landform model of the world for the native surface pass: no badlands pillars in rifts and crater bowls.
        var landforms=dev.freshgenerator.landform.LandformSurfaceScope.enter(
            dev.freshgenerator.landform.LandformDensity.world(state,generator.generatorSettings().value()));
        try {
            original.call(system,state,biomes,context,chunk,noise,rule,possibleBiomes);
            GlacialSurface.apply(chunk,state,system,biomes,possibleBiomes,generator.generatorSettings().value());
        } finally {
            dev.freshgenerator.landform.LandformSurfaceScope.exit(landforms);
            GlacialSurface.end(previous);
        }
    }
}
