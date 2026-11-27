package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** The deeper world's dimension type (freshgenerator:overworld, user request 26.11) is the native overworld type with a
 * deeper range. The game counts only the native type as a stable overworld and would mark such worlds "experimental";
 * this treats the mod's type like the native one (same biome-source check). */
@Mixin(WorldDimensions.class)
abstract class DeepWorldMixin {
    @Unique private static final ResourceKey<DimensionType> FRESHGENERATOR$TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse("freshgenerator:overworld"));

    @ModifyReturnValue(method = "isStableOverworld", at = @At("RETURN"), require = 1)
    private static boolean freshgenerator$deepOverworldIsStable(boolean stable, LevelStem stem) {
        if (stable || !stem.type().is(FRESHGENERATOR$TYPE)) return stable;
        return stem.generator().getBiomeSource() instanceof MultiNoiseBiomeSource source
                && source.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
    }
}
