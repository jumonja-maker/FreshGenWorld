package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.landform.CaveRoomDensity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.Set;

/** No ancient city or trial chambers where a great breakdown chamber of the current world opens (user request 26.11:
 * "crumbs" in the chambers). Their pieces stand at their native depth whatever the cave around them; the dome of a
 * chamber carved the rock around and under them, and parts of an ancient city hung in the air of the chamber - deepslate
 * bricks, slabs, sculk and a chest (seed 31337 X480 Z152 Y-51: 755 blocks in 43 pieces, the largest 643). The structure
 * simply does not start there, decided on the generation point: the world, /locate and explorer maps all ask this
 * method, so none of them points at a structure that is not there. About one site in fifteen is lost. Surface
 * structures keep off the landforms of the current world the same way (LandformMaterials.noBuild). */
@Mixin(Structure.class)
abstract class DeepStructureMixin {
    private static final Set<String> DEEP = Set.of("minecraft:ancient_city", "minecraft:trial_chambers");
    /** Largest extent of these structures around their start (the jigsaw max_distance_from_center) and their depth
     * band (ancient city pieces Y-51 to about Y-10, trial chambers start at Y-40 to Y-20). */
    private static final int REACH = 116, Y_MIN = -66, Y_MAX = 6;

    @ModifyReturnValue(method = "findValidGenerationPoint", at = @At("RETURN"))
    private Optional<Structure.GenerationStub> freshgenerator$noStructureInChamber(Optional<Structure.GenerationStub> stub,
                                                                                    @Local(argsOnly = true) Structure.GenerationContext context) {
        if (stub.isEmpty() || !(context.chunkGenerator() instanceof NoiseBasedChunkGenerator noise)) return stub;
        Structure self = (Structure) (Object) this;
        Identifier id = context.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(self);
        if (id != null && MARINE.contains(id.toString()) && onIsland(stub, context, noise, id.toString())) return Optional.empty();
        if (self.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) return onLandform(stub, context, noise, self) ? Optional.empty() : stub;
        if (id == null || !DEEP.contains(id.toString())) return stub;
        CaveRoomDensity.Model rooms = CaveRoomDensity.model(context.randomState(), noise.generatorSettings().value(), context.registryAccess());
        if (rooms == null) return stub;
        BlockPos p = stub.get().position();
        return rooms.domesTouch(p.getX() - REACH, Y_MIN, p.getZ() - REACH, p.getX() + REACH, Y_MAX, p.getZ() + REACH) ? Optional.empty() : stub;
    }

    /** Marine structures and the highest island ground they may stand on: wrecks, ruins and monuments under at least
     * three blocks of water, a beached wreck on the shore. */
    private static final Set<String> MARINE = Set.of("minecraft:shipwreck", "minecraft:shipwreck_beached", "minecraft:ocean_ruin_cold",
            "minecraft:ocean_ruin_warm", "minecraft:monument");

    /** Marine structures keep off the land of volcanic islands (audit 26.11 Opus N-2: they pick their place by the ocean
     * biome, which the island kept - 20 of 28 ocean ruins and 20 of 24 shipwrecks round three islands stood on land up
     * to Y108, a wreck on a basalt slope at Y101, seed 7391827364 X-3676 Z-5967). The start and four points 16 blocks
     * round it (a ruin cluster spreads) must lie where the island ground stays under the water. */
    private static boolean onIsland(Optional<Structure.GenerationStub> stub, Structure.GenerationContext context,
                                    NoiseBasedChunkGenerator noise, String id) {
        var landforms = dev.freshgenerator.landform.LandformDensity.world(context.randomState(), noise.generatorSettings().value());
        if (landforms == null) return false;
        BlockPos p = stub.get().position();
        double top = id.equals("minecraft:shipwreck_beached") ? 66 : 59;
        for (int k = -1; k < 4; k++) {
            int x = p.getX() + (k < 0 ? 0 : k == 0 ? 16 : k == 1 ? -16 : 0), z = p.getZ() + (k == 2 ? 16 : k == 3 ? -16 : 0);
            double g = landforms.islandGround(x, z);
            if (!Double.isNaN(g) && g > top) return true;
        }
        return false;
    }

    /** Surface structures (villages, outposts, temples, camps, surface portals) keep off volcano cones, active volcanoes,
     * fresh craters and rifts (LandformMaterials.noBuild): their start and eight points round it - 48 blocks out for a
     * jigsaw village or outpost, 12 for a single building. */
    private static boolean onLandform(Optional<Structure.GenerationStub> stub, Structure.GenerationContext context,
                                      NoiseBasedChunkGenerator noise, Structure self) {
        var landforms = dev.freshgenerator.landform.LandformDensity.world(context.randomState(), noise.generatorSettings().value());
        if (landforms == null) return false;
        BlockPos p = stub.get().position();
        if (landforms.noBuild(p.getX(), p.getZ())) return true;
        int reach = self instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure ? 48 : 12;
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            if (landforms.noBuild(p.getX() + (int) Math.round(Math.cos(a) * reach), p.getZ() + (int) Math.round(Math.sin(a) * reach))) return true;
        }
        return false;
    }
}
