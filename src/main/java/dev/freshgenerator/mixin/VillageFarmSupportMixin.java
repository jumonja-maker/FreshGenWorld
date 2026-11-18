package dev.freshgenerator.mixin;

import com.mojang.datafixers.util.Either;
import dev.freshgenerator.village.VillageFarmSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SinglePoolElement.class)
abstract class VillageFarmSupportMixin {
    @Shadow @Final protected Either<Identifier, StructureTemplate> template;
    @Shadow private StructureTemplate getTemplate(StructureTemplateManager manager) { throw new AssertionError(); }

    @Inject(method="place", at=@At("RETURN"), require=1)
    private void freshgenerator$supportCrops(StructureTemplateManager manager, WorldGenLevel level,
        StructureManager structures, ChunkGenerator generator, BlockPos origin, BlockPos pivot, Rotation rotation,
        BoundingBox bounds, RandomSource random, LiquidSettings liquids, boolean keepJigsaws,
        CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValue() || !(level instanceof WorldGenRegion region)
                || !(generator instanceof NoiseBasedChunkGenerator noise)) return;
        if (!noise.generatorSettings().unwrapKey().map(k -> k.identifier().toString()).orElse("")
                .equals("freshgenerator:highlands_v2")) return;
        var location = template.left().orElse(null);
        if (location == null || !VillageFarmSupport.eligibleTemplate(location.toString())) return;
        VillageFarmSupport.support(region, getTemplate(manager), origin, rotation, bounds);
    }
}
