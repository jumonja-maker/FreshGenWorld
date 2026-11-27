package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.hydrology.HydrologyDensity;
import dev.freshgenerator.hydrology.VillageWater;
import dev.freshgenerator.road.RoadNetwork;
import dev.freshgenerator.village.VillageTerrainPlan;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.function.Predicate;

/** No building over the water of the drainage network or the sea (current world only; VillageWater): a house over a
 * river cut it in two (user request 24.11), paths and houses stood on the ice of a frozen lake (audit 23.11 and FG3-06,
 * seed 31337 X-3560 Z-2520), an outpost stood in a lake and igloos on lake ice (audit 25.11 S-1). Streets keep the
 * native plank rule over water, dead-end piers go. The road planner builds its village plans through the same method,
 * so roads lead to the villages the world really has. */
@Mixin(Structure.class)
abstract class VillageWaterMixin {
    @ModifyReturnValue(method = "generate", at = @At("RETURN"))
    private StructureStart freshgenerator$noVillageOnWater(StructureStart start, @Local(argsOnly = true) Holder<Structure> structure,
                                                          @Local(argsOnly = true) ChunkGenerator generator, @Local(argsOnly = true) RandomState state) {
        if (start == null || !start.isValid() || !(generator instanceof NoiseBasedChunkGenerator noise)) return start;
        String id = structure.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        boolean village = VillageTerrainPlan.villageId(id);
        if (!village && !VillageWater.surfaceId(id)) return start;
        HydroField field = HydrologyDensity.field(state, noise.generatorSettings().value());
        if (field == null) return start;
        if (!village) return VillageWater.filterSurface(start, field, id);
        StructureStart out = VillageWater.filter(start, field);
        RoadNetwork roads = RoadNetwork.existing(state);
        if (roads != null && out.isValid()) roads.record(structure, out.getChunkPos(), out);
        return out;
    }

    /** A village the road planner already built is handed to the world instead of being built a second time (the same
     * Structure.generate with the same seed, chunk and structure). */
    @Inject(method = "generate", at = @At("HEAD"), cancellable = true)
    private void freshgenerator$plannedVillage(Holder<Structure> structure, ResourceKey<Level> level, RegistryAccess registries,
                                               ChunkGenerator generator, BiomeSource biomes, Climate.Sampler sampler, RandomState state,
                                               StructureTemplateManager templates, long seed, ChunkPos pos, int references,
                                               LevelHeightAccessor height, Predicate<Holder<Biome>> valid,
                                               CallbackInfoReturnable<StructureStart> cir) {
        if (!(generator instanceof NoiseBasedChunkGenerator)) return;
        String id = structure.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        if (!VillageTerrainPlan.villageId(id)) return;
        RoadNetwork roads = RoadNetwork.existing(state);
        if (roads == null) return;
        StructureStart planned = roads.takePending(structure, pos);
        if (planned != null) cir.setReturnValue(planned);
    }
}
