package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.freshgenerator.core.hydro.HydroField;
import dev.freshgenerator.hydrology.HydroAquifer;
import dev.freshgenerator.hydrology.HydrologyDensity;
import dev.freshgenerator.hydrology.RiverBeardifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rivers and lakes of the drainage model enter the native noise fill (current world only), and structure terrain
 * adaptation does not fill their water (RiverBeardifier). */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkHydrologyMixin {
    @Shadow @Final @Mutable private Aquifer aquifer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void freshgenerator$surfaceWater(RandomState state, Beardifier beard, NoiseGeneratorSettings settings,
                                             Aquifer.FluidPicker picker, Blender blender, DensityVolume volume, CallbackInfo callback) {
        HydroField field = HydrologyDensity.field(state, settings);
        if (field != null) aquifer = new HydroAquifer(aquifer, field);
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/util/context/ContextMap$Builder;set(Lnet/minecraft/util/context/ContextKey;Ljava/lang/Object;)Lnet/minecraft/util/context/ContextMap$Builder;"), require = 1)
    private ContextMap.Builder freshgenerator$riverSafeBeard(ContextMap.Builder builder, ContextKey<Object> key, Object value, Operation<ContextMap.Builder> original,
                                                             @Local(argsOnly = true) RandomState state, @Local(argsOnly = true) NoiseGeneratorSettings settings) {
        if (key == (Object) Beardifier.CONTEXT_KEY && value instanceof Beardifier beard && beard != Beardifier.EMPTY) {
            HydroField field = HydrologyDensity.field(state, settings);
            if (field != null) value = new RiverBeardifier(beard, field);
        }
        return original.call(builder, key, value);
    }
}
