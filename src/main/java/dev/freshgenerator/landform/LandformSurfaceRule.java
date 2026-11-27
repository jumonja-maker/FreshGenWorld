package dev.freshgenerator.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.GradientNoise;
import dev.freshgenerator.core.LandformMaterials;
import dev.freshgenerator.mixin.MaterialRuleContextAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

import java.util.List;

/** Ground cover of volcanoes and impact craters ({@link LandformMaterials}). {@code height} is the landform height
 * field (its sampler carries the world's landform model); {@code top} selects the floor block or the blocks under it.
 * Passes (null) away from the landforms and under water. */
public record LandformSurfaceRule(DensityFunction height, boolean top) implements MaterialRule {
    public static final Identifier ID = Identifier.parse("freshgenerator:landform_surface");
    public static final MapCodec<LandformSurfaceRule> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.CODEC.fieldOf("height").forGetter(LandformSurfaceRule::height),
            Codec.BOOL.optionalFieldOf("top", true).forGetter(LandformSurfaceRule::top)
    ).apply(i, LandformSurfaceRule::new));

    @Override public MapCodec<LandformSurfaceRule> codec() { return CODEC; }

    @Override public RuleEvaluator compile(MaterialRuleContext c) {
        var state = ((MaterialRuleContextAccessor) (Object) c).freshgenerator$randomState();
        LandformDensity.World world = LandformDensity.worldOf(state.getSampler(height));
        if (world == null) return (x, y, z) -> null;
        long seed = c.getOrCreateRandomFactory(ID).at(0, 0, 0).nextLong();
        GradientNoise patch = new GradientNoise(seed);
        return new RuleEvaluator() {
            int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
            List<Object> sites = List.of();
            @Override public BlockState tryApply(int x, int y, int z) {
                if (x != lastX || z != lastZ) { sites = world.near(x, z); lastX = x; lastZ = z; }
                if (sites.isEmpty()) return null;
                // Also under snow: the rock and ash of an upper cone carry no forest; the snow layer is laid on top later.
                // Under water only seamounts and the drowned flanks of volcanic islands take a cover.
                return block(LandformMaterials.pick(sites, x, y, z, top, c.waterHeight() > y, seed, patch));
            }
        };
    }

    static BlockState block(LandformMaterials.Cover cover) {
        return switch (cover) {
            case NATIVE -> null;
            case BASALT -> Blocks.BASALT.defaultBlockState();
            case SMOOTH_BASALT -> Blocks.SMOOTH_BASALT.defaultBlockState();
            case BLACKSTONE -> Blocks.BLACKSTONE.defaultBlockState();
            case TUFF -> Blocks.TUFF.defaultBlockState();
            case ANDESITE -> Blocks.ANDESITE.defaultBlockState();
            case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
            case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case MAGMA -> Blocks.MAGMA_BLOCK.defaultBlockState();
            case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
            case STONE -> Blocks.STONE.defaultBlockState();
        };
    }
}
