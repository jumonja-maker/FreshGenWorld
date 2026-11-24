package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.datafixers.util.Pair;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.hydrology.HydroBiomes;
import dev.freshgenerator.hydrology.HydrologyDensity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/** The biome search (/locate biome) sees the river biomes the chunks store (audit 23.11: the search read the source
 * without the drainage overlay - river at seed 42 X2914 Z2478 searched as ocean). A river search counts wide rivers at
 * any height near its probes and reports the river column itself; any other search reads the source and checks the
 * stored biome only where the source matches; the drainage model answers within a fixed radius of the search origin
 * (HydroBiomes.Search; audit 24.11 T-3, code audit 24.11 K-2, K-3, G1, G3). */
@Mixin(BiomeSource.class)
abstract class BiomeSourceRiverMixin {
    @Unique private static final ThreadLocal<HydroBiomes.Search> freshgenerator$search = new ThreadLocal<>();

    @WrapOperation(method = "findClosestBiome3d", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/BiomeSource;createCachingResolver(Lnet/minecraft/world/level/levelgen/RandomState;)Lnet/minecraft/world/level/biome/BiomeResolver;"), require = 1)
    private BiomeResolver freshgenerator$rivers(BiomeSource self, RandomState state, Operation<BiomeResolver> original,
                                               @Local(argsOnly = true) Predicate<Holder<Biome>> wanted, @Local(argsOnly = true) BlockPos origin) {
        BiomeResolver resolver = original.call(self, state);
        HydroField field = HydrologyDensity.boundField(state);
        BiomeResolver search = field != null ? HydroBiomes.search(resolver, field, self, wanted, origin) : resolver;
        freshgenerator$search.set(search instanceof HydroBiomes.Search s ? s : null);
        return search;
    }

    @ModifyReturnValue(method = "findClosestBiome3d", at = @At("RETURN"), require = 1)
    private Pair<BlockPos, Holder<Biome>> freshgenerator$riverPosition(Pair<BlockPos, Holder<Biome>> result) {
        HydroBiomes.Search search = freshgenerator$search.get();
        if (search == null) return result;
        freshgenerator$search.remove();
        return search.finish(result);
    }
}
