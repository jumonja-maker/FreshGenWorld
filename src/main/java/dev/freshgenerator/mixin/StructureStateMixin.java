package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.hydrology.StructureSets;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.stream.Stream;

/** One family of ocean structure sets per world (StructureSets). */
@Mixin(ChunkGeneratorStructureState.class)
abstract class StructureStateMixin {
    @WrapOperation(method = "createForNormal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/HolderLookup;listElements()Ljava/util/stream/Stream;"), require = 1)
    private static Stream<Holder.Reference<StructureSet>> freshgenerator$oneFamily(HolderLookup<StructureSet> sets,
                                                                                   Operation<Stream<Holder.Reference<StructureSet>>> original) {
        return StructureSets.filter(original.call(sets));
    }
}
