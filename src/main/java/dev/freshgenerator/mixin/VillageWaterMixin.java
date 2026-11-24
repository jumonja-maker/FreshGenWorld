package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.hydrology.HydrologyDensity;
import dev.freshgenerator.village.VillageTerrainPlan;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No village over the water of the drainage network (current world only): a start is dropped when more than a
 * fifth of its pieces would stand on a lake or river (audit 23.11 and FG3-06: paths and houses on the ice of a
 * frozen lake, seed 31337 X-3560 Z-2520). The village is placed by the native rules elsewhere. */
@Mixin(Structure.class)
abstract class VillageWaterMixin {
    @ModifyReturnValue(method = "generate", at = @At("RETURN"))
    private StructureStart freshgenerator$noVillageOnWater(StructureStart start, @Local(argsOnly = true) Holder<Structure> structure,
                                                          @Local(argsOnly = true) ChunkGenerator generator, @Local(argsOnly = true) RandomState state) {
        if (start == null || !start.isValid() || !(generator instanceof NoiseBasedChunkGenerator noise)) return start;
        String id = structure.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        if (!VillageTerrainPlan.villageId(id)) return start;
        HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return start;
        HydroField.Column c = new HydroField.Column();
        int pieces = 0, wet = 0;
        for (StructurePiece piece : start.getPieces()) {
            var box = piece.getBoundingBox();
            pieces++;
            field.column((box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2, c);
            if (c.wet()) wet++;
        }
        return pieces > 0 && wet * 5 > pieces ? StructureStart.INVALID_START : start;
    }
}
