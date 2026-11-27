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
 /** Identifier of a placed feature's configured feature, built once for both hooks of one placement (code audit 24.11
  * K-11: the string was built twice for every placed feature). */
 @Unique private PlacedFeature freshgenerator$hintFor;
 @Unique private String freshgenerator$hintValue;
 @Unique private String freshgenerator$hint(PlacedFeature placed){
  if(placed!=freshgenerator$hintFor){freshgenerator$hintValue=placed.feature().unwrapKey().map(k->k.identifier().toString()).orElse("");freshgenerator$hintFor=placed;}
  return freshgenerator$hintValue;
 }
 @Unique private ForestEnvironment freshgenerator$environment(){
  if(!freshgenerator$forestChecked){freshgenerator$forest=ForestEnvironment.create(level,generator);freshgenerator$forestChecked=true;}
  return freshgenerator$forest;
 }
 @Unique private boolean freshgenerator$landformChecked;
 @Unique private dev.freshgenerator.landform.LandformDensity.World freshgenerator$landforms;
 /** Fresh impact craters (user request 26.11): the forest of the blast zone is felled and burnt - no tree inside the
  * crater and on its rim, few up to three radii; part of the lost trees lie as charred trunks pointing away from the
  * centre. True when the tree must not grow here. */
 @Unique private boolean freshgenerator$blast(BlockPos at){
  if(!freshgenerator$landformChecked){
   freshgenerator$landformChecked=true;
   if(generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)
    freshgenerator$landforms=dev.freshgenerator.landform.LandformDensity.world(level.getLevel().getChunkSource().randomState(),noise.generatorSettings().value());
  }
  var world=freshgenerator$landforms;if(world==null)return false;
  var sites=world.near(at.getX(),at.getZ());if(sites.isEmpty())return false;
  double survival=dev.freshgenerator.core.LandformMaterials.blastSurvival(sites,at.getX(),at.getZ());
  if(survival>=1)return false;
  double roll=dev.freshgenerator.core.GradientNoise.unit(level.getSeed(),at.getX()*31L+7,at.getZ());
  if(roll<survival)return false;
  // An active volcano burns the trees where they stand (charred trunks); a fresh impact felled them away from the centre.
  if(roll<survival+(1-survival)*dev.freshgenerator.core.LandformMaterials.remains(sites,at.getX(),at.getZ())){
   if(dev.freshgenerator.core.LandformMaterials.volcanicBurn(sites,at.getX(),at.getZ()))freshgenerator$snag(at);
   else freshgenerator$fallenTrunk(sites,at);
  }
  return true;
 }
 @Unique private void freshgenerator$snag(BlockPos at){
  int x=at.getX(),z=at.getZ();
  var pos=new BlockPos.MutableBlockPos(x,at.getY(),z);
  if(!level.ensureCanWrite(pos))return;
  int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,x,z);
  pos.set(x,y-1,z);var below=level.getBlockState(pos);
  if(!below.isFaceSturdy(level,pos,net.minecraft.core.Direction.UP)||!below.getFluidState().isEmpty()
    ||below.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK))return;
  int height=2+(int)(dev.freshgenerator.core.GradientNoise.unit(level.getSeed(),x*17L+3,z)*4);
  var log=net.minecraft.world.level.block.Blocks.DARK_OAK_LOG.defaultBlockState();
  for(int k=0;k<height;k++){
   pos.set(x,y+k,z);
   if(!level.getBlockState(pos).canBeReplaced())return;
   level.setBlock(pos,log,2);
  }
 }
 @Unique private void freshgenerator$fallenTrunk(List<Object> sites,BlockPos at){
  for(Object o:sites){
   if(!(o instanceof dev.freshgenerator.core.Landforms.Crater c))continue;
   double dx=at.getX()-c.x,dz=at.getZ()-c.z;if(Math.hypot(dx,dz)<c.radius*1.1)return;
   boolean alongX=Math.abs(dx)>=Math.abs(dz);int sx=alongX?(dx>=0?1:-1):0,sz=alongX?0:(dz>=0?1:-1);
   int length=3+(int)(dev.freshgenerator.core.GradientNoise.unit(level.getSeed(),at.getX(),at.getZ()*13L+5)*3);
   var log=net.minecraft.world.level.block.Blocks.DARK_OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
     alongX?net.minecraft.core.Direction.Axis.X:net.minecraft.core.Direction.Axis.Z);
   var pos=new BlockPos.MutableBlockPos();
   int firstY=Integer.MIN_VALUE;
   for(int k=0;k<length;k++){
    int x=at.getX()+sx*k,z=at.getZ()+sz*k;
    if(!level.ensureCanWrite(pos.set(x,at.getY(),z)))return;
    int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,x,z);
    // A fallen trunk lies on one level: on a slope its logs met by an edge only (code audit 27.11 OpenAI K-07).
    if(firstY==Integer.MIN_VALUE)firstY=y;else if(y!=firstY)return;
    pos.set(x,y-1,z);var below=level.getBlockState(pos);
    if(!below.isFaceSturdy(level,pos,net.minecraft.core.Direction.UP)||!below.getFluidState().isEmpty())return;
    pos.set(x,y,z);if(!level.getBlockState(pos).canBeReplaced())return;
    level.setBlock(pos,log,2);
   }
   return;
  }
 }
 @WrapOperation(method="place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
  at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placement()Ljava/util/List;"),require=1)
 private List<PlacementModifier> freshgenerator$edgeCandidates(PlacedFeature placed,Operation<List<PlacementModifier>> original,
   PlacedFeature outer,RandomSource random,BlockPos origin,boolean biomeCheck){
  var modifiers=original.call(placed);if(!biomeCheck)return modifiers;
  String hint=freshgenerator$hint(placed);if(!ForestEnvironment.hint(hint))return modifiers;
  var forest=freshgenerator$environment();return forest==null?modifiers:forest.modifiers(placed,modifiers,origin);
 }
 @WrapOperation(method="place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
  at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),require=2)
 private boolean freshgenerator$ecologicalForest(Feature feature,WorldGenLevel level,ChunkGenerator generator,
   RandomSource random,BlockPos at,Operation<Boolean> original,PlacedFeature placed,RandomSource sourceRandom,BlockPos origin,boolean biomeCheck){
  if(biomeCheck){
   String hint=freshgenerator$hint(placed);
   // 0.9.1: seagrass, kelp and warm reefs follow light (depth), ground and patches.
   if(OceanEnvironment.hint(hint)){
    var ocean=freshgenerator$oceanEnvironment();
    if(ocean!=null&&!ocean.retain(hint,at))return false;
   }
   if(ForestEnvironment.hint(hint)&&freshgenerator$blast(at))return false;
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
