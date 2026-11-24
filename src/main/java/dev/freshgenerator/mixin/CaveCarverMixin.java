package dev.freshgenerator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.*;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.freshgenerator.cave.CaveCarverGuard;
import dev.freshgenerator.cave.NativeCarverSwitch;
import dev.freshgenerator.core.CaveAccessModel;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The active current world opts out of all native caves. Hidden historical
 * settings without that marker retain their published carver roof and RNG calls.
 * Shared state is a local variable of this invocation, safe for parallel chunk generation.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class CaveCarverMixin {
 /** In the current world every native carver is refused (see below), so the carver stage only
  * looked up the biomes of about 17x17 neighbouring chunks and discarded the result: its local
  * carving mask stays empty and is never applied. Each lookup evaluates the full climate graph
  * (which reads the landscape height), about 85% of the chunk generation time in the 0.9.1
  * profile. The stage is skipped there; generated blocks, biomes and heightmaps are unchanged. */
 @Inject(method="generateCarvers",at=@At("HEAD"),cancellable=true,require=1)
 private void freshgenerator$skipDisabledCarvers(ChunkAccess chunk,Blender blender,NoiseChunk noise,RandomState state,
   BiomeManager biomes,WorldGenRegion region,MaterialRule materials,CallbackInfo ci) {
  if(NativeCarverSwitch.carversOff((NoiseBasedChunkGenerator)(Object)this,region.registryAccess(),state))ci.cancel();
 }
 @WrapOperation(method="generateCarvers",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/carver/WorldCarver;carve(Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/CarverOutput;)Z"),require=1)
 private boolean freshgenerator$surfaceAccess(WorldCarver carver,WorldGenerationContext generation,RandomSource random,
   ChunkPos target,ChunkPos source,CarverOutput output,Operation<Boolean> original,
   ChunkAccess chunk,Blender blender,NoiseChunk noise,RandomState state,BiomeManager biomes,WorldGenRegion region,MaterialRule materials,
   @Local(ordinal=0) Holder<WorldCarver> definition,@Share("surfaceCaveGuard") LocalRef<CaveCarverGuard> shared) {
  var generator=(NoiseBasedChunkGenerator)(Object)this;
  if(generator.generatorSettings().unwrapKey().map(k->k.identifier().toString()).orElse("").equals(CaveAccessModel.SETTINGS)) {
   // 0.9.2 (audit "build10" T02): the value of the marker function decides, not a debug label.
   if(NativeCarverSwitch.carversOff(generator,region.registryAccess(),state))return false;
   String id=definition.unwrapKey().map(k->k.identifier().toString()).orElse("");
   if(id.equals("minecraft:cave")||id.equals("minecraft:cave_extra_underground")||id.equals("minecraft:canyon")) {
    var guard=shared.get();if(guard==null){guard=new CaveCarverGuard(generator.generatorSettings().value(),state,target);shared.set(guard);}
    output=guard.wrap(output,id.equals("minecraft:canyon"));
   }
  }
  return original.call(carver,generation,random,target,source,output);
 }
}
