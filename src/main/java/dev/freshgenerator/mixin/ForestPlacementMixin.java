package dev.freshgenerator.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.freshgenerator.forest.ForestEnvironment;
import dev.freshgenerator.forest.ForestTelemetry;
import dev.freshgenerator.ocean.OceanEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

/** Native biome, height, water and survival filters remain in control. Bounded
 * edge proposals use the native position filters, without moved candidates or retries.
 * Variant choice is coordinate-seeded; native tree generators
 * consume their normal randomness. Saplings, commands, nested features and other worlds
 * stay on the original path. The name check is only a cheap hint; identity is checked next.
 */
@Mixin(FeaturePlacer.class)
abstract class ForestPlacementMixin {
 @Shadow @Final private WorldGenLevel level;
 @Shadow @Final private ChunkGenerator generator;
 @Unique private boolean freshgenerator$forestChecked;
 @Unique private ForestEnvironment freshgenerator$forest;
 @Unique private boolean freshgenerator$oceanChecked;
 @Unique private OceanEnvironment freshgenerator$ocean;
 @Unique private OceanEnvironment freshgenerator$oceanEnvironment(){
  if(!freshgenerator$oceanChecked){freshgenerator$ocean=OceanEnvironment.create(level,generator);freshgenerator$oceanChecked=true;}
  return freshgenerator$ocean;
 }
 @Unique private ForestEnvironment freshgenerator$environment(){
  if(!freshgenerator$forestChecked){freshgenerator$forest=ForestEnvironment.create(level,generator);freshgenerator$forestChecked=true;}
  return freshgenerator$forest;
 }
 @WrapOperation(method="place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
  at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placement()Ljava/util/List;"),require=1)
 private List<PlacementModifier> freshgenerator$edgeCandidates(PlacedFeature placed,Operation<List<PlacementModifier>> original,
   PlacedFeature outer,RandomSource random,BlockPos origin,boolean biomeCheck){
  var modifiers=original.call(placed);if(!biomeCheck)return modifiers;
  String hint=placed.feature().unwrapKey().map(k->k.identifier().toString()).orElse("");if(!ForestEnvironment.hint(hint))return modifiers;
  var forest=freshgenerator$environment();return forest==null?modifiers:forest.modifiers(placed,modifiers,origin);
 }
 @WrapOperation(method="place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
  at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),require=2)
 private boolean freshgenerator$ecologicalForest(Feature feature,WorldGenLevel level,ChunkGenerator generator,
   RandomSource random,BlockPos at,Operation<Boolean> original,PlacedFeature placed,RandomSource sourceRandom,BlockPos origin,boolean biomeCheck){
  if(biomeCheck){
   String hint=placed.feature().unwrapKey().map(k->k.identifier().toString()).orElse("");
   // 0.9.1: seagrass, kelp and warm reefs follow light (depth), ground and patches.
   if(OceanEnvironment.hint(hint)){
    var ocean=freshgenerator$oceanEnvironment();
    if(ocean!=null&&!ocean.retain(hint,at))return false;
   }
   if(ForestEnvironment.hint(hint)){
    freshgenerator$environment();
    if(freshgenerator$forest!=null){
     if(!freshgenerator$forest.retain(placed,at))return false;
     PlacedFeature ecotone=freshgenerator$forest.ecotoneFeature(placed,at);
     if(ecotone!=null)return freshgenerator$forest.grow(placed,ecotone,at).place(level,generator,random,at);
     PlacedFeature replacement=freshgenerator$forest.replacement(placed,at);
     if(replacement!=null){
      boolean success=freshgenerator$forest.grow(placed,replacement,at).place(level,generator,random,at);
      ForestTelemetry.variant(replacement,success);return success;
     }
     feature=freshgenerator$forest.grow(placed,feature,at);
    }
   }
  }
  return original.call(feature,level,generator,random,at);
 }
}
