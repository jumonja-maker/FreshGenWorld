package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.hydrology.HydrologyDensity;
import dev.freshgenerator.hydrology.StructureSets;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks whether the structure state being built belongs to the current world (StructureSets). */
@Mixin(ChunkGenerator.class)
abstract class StructureSetsMixin {
    @WrapOperation(method = "createState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;createForNormal(Lnet/minecraft/world/level/levelgen/RandomState;JLnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/core/HolderLookup;)Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"),
            require = 1)
    private ChunkGeneratorStructureState freshgenerator$markWorld(RandomState state, long seed, ChunkPos origin, BiomeSource source,
                                                                 HolderLookup<StructureSet> sets, Operation<ChunkGeneratorStructureState> original) {
        boolean current = (Object) this instanceof NoiseBasedChunkGenerator noise
                && HydrologyDensity.field(state, noise.generatorSettings().value()) != null;
        StructureSets.begin(current);
        try { return original.call(state, seed, origin, source, sets); }
        finally { StructureSets.end(); }
    }
}
