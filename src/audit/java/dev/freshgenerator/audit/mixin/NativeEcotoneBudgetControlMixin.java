package dev.freshgenerator.audit.mixin;

import dev.freshgenerator.forest.ForestEnvironment;
import dev.freshgenerator.audit.NativeEcotoneControls;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Audit-only ablation, packaged solely in the external observer JAR. Both arms
 * use the exact same playable JAR, seed, climate, soil and native filters. When
 * enabled before the fresh world starts, no proposal plan can ever be created
 * in a FeaturePlacer's new environment, so native counts and ecology are used.
 */
@Mixin(value=ForestEnvironment.class,remap=false)
public abstract class NativeEcotoneBudgetControlMixin {
    @Inject(method="modifiers",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void freshgenerator$auditNativeCounts(PlacedFeature feature,List<PlacementModifier> original,BlockPos origin,
            CallbackInfoReturnable<List<PlacementModifier>> callback){
        if("true".equals(System.getenv("FG_ECOTONE_NATIVE_COUNTS_CONTROL"))){NativeEcotoneControls.countBypassed();callback.setReturnValue(original);}
    }
}
