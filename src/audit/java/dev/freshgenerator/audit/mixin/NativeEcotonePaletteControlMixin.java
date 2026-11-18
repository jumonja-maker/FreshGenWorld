package dev.freshgenerator.audit.mixin;

import dev.freshgenerator.audit.NativeEcotoneControls;
import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.core.ForestEcotone;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forces a contributing palette solely to exercise a guarded native path in
 * an isolated audit world. It does not change the playable JAR or biome IDs. */
@Mixin(value=ForestEcotone.class,remap=false)
public abstract class NativeEcotonePaletteControlMixin {
    @Inject(method="palette",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private static void freshgenerator$auditPalette(long seed,int x,int z,ForestEcology.Site site,List<ForestEcotone.Share> shares,
            CallbackInfoReturnable<String> callback){
        String forced=NativeEcotoneControls.forcedPalette();if(forced!=null)callback.setReturnValue(forced);
    }
    @Inject(method="mayBorrowPalette",at=@At("RETURN"),require=1,remap=false)
    private static void freshgenerator$auditGuard(String own,String donor,CallbackInfoReturnable<Boolean> callback){
        NativeEcotoneControls.paletteGuard(own,donor,callback.getReturnValue());
    }
}
