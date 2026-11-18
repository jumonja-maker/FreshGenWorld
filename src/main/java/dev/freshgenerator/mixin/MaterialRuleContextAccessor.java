package dev.freshgenerator.mixin;

import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the seeded sampler owner, never the material system's mutable
 * chunk sampling context. Each material evaluator owns its separate cache. */
@Mixin(MaterialRuleContext.class)
public interface MaterialRuleContextAccessor {
    @Accessor("randomState")
    RandomState freshgenerator$randomState();
}
