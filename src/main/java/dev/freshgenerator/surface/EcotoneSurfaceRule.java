package dev.freshgenerator.surface;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.freshgenerator.core.EcotoneModel;
import dev.freshgenerator.core.LandscapeMaterials;
import dev.freshgenerator.mixin.MaterialRuleContextAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/** Native material rule, before structures/vegetation. Never edits a loaded world's blocks. */
public record EcotoneSurfaceRule(DensityFunction height,DensityFunction temperature,DensityFunction erosion,
        boolean subsurface,boolean landscape,DensityFunction moisture,DensityFunction continentality,DensityFunction oceanRelief) implements MaterialRule {
 public static final MapCodec<EcotoneSurfaceRule> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
  DensityFunction.CODEC.fieldOf("height").forGetter(EcotoneSurfaceRule::height),
  DensityFunction.CODEC.fieldOf("temperature").forGetter(EcotoneSurfaceRule::temperature),
  DensityFunction.CODEC.fieldOf("erosion").forGetter(EcotoneSurfaceRule::erosion),
  Codec.BOOL.optionalFieldOf("subsurface",false).forGetter(EcotoneSurfaceRule::subsurface),
  Codec.BOOL.optionalFieldOf("landscape",false).forGetter(EcotoneSurfaceRule::landscape),
  DensityFunction.CODEC.optionalFieldOf("moisture",DensityFunctions.zero()).forGetter(EcotoneSurfaceRule::moisture),
  DensityFunction.CODEC.optionalFieldOf("continentality",DensityFunctions.zero()).forGetter(EcotoneSurfaceRule::continentality),
  // 0.9.1 update 19.11: the deep-floor province relief (blocks); only the seabed copies set it.
  DensityFunction.CODEC.optionalFieldOf("ocean_relief",DensityFunctions.zero()).forGetter(EcotoneSurfaceRule::oceanRelief)
 ).apply(i,EcotoneSurfaceRule::new));
 @Override public MapCodec<EcotoneSurfaceRule> codec(){return CODEC;}
 @Override public RuleEvaluator compile(MaterialRuleContext c){
  // false selects lazy native sampling, not three unnecessary full 3-D buffers for 2-D fields.
  var h=c.getDensitiesInChunk(height,false);var t=c.getDensitiesInChunk(temperature,false);var e=c.getDensitiesInChunk(erosion,false);
  var m=c.getDensitiesInChunk(moisture,false);var r=c.getDensitiesInChunk(oceanRelief,false);
  // Native WORLD_SURFACE_WG gradients are rounded to block heights, include water,
  // and clamp at chunk edges. Sample the continuous ground envelope instead.
  // Only this rule evaluator owns the context; no chunk state or global cache is used.
  var ground=landscape?((MaterialRuleContextAccessor)(Object)c).freshgenerator$randomState()
      .getSampler(height).bind(SamplerContext.builder().enableCaches().build()):null;
  long seed=c.getOrCreateRandomFactory(EcotonePolicy.SEED_ID).at(0,0,0).nextLong();
  return new RuleEvaluator(){
   int lastX=Integer.MIN_VALUE,lastZ=Integer.MIN_VALUE;float elevation,climate,eroded,wetness;
   double landscapeSlope,relief;boolean slopeReady,reliefReady;EcotoneModel.Fields fields;
   // The continuous ground slope costs four height samples; it is computed once per
   // column and only when a branch actually needs it.
   double slope(int x,int z){
    if(!landscape)return 0.5*Math.hypot(c.surfaceGradientX(),c.surfaceGradientZ());
    if(!slopeReady){landscapeSlope=LandscapeMaterials.continuousSlope(
        groundAt(x-4,z),groundAt(x+4,z),groundAt(x,z-4),groundAt(x,z+4),4);slopeReady=true;}
    return landscapeSlope;
   }
   // The ground envelope is interpolated on the native 4-block cell grid, so its value
   // between grid nodes is bilinear in the node values. Caching the nodes of this chunk
   // (about 50) replaces four full landscape evaluations per column (1024 per chunk).
   final java.util.HashMap<Long,Double> nodes=new java.util.HashMap<>();
   double node(int x,int z){
    long key=((long)x<<32)^(z&0xffffffffL);Double v=nodes.get(key);
    if(v==null){if(nodes.size()>4096)nodes.clear();v=(double)ground.sampleValue(x,0,z);nodes.put(key,v);}
    return v;
   }
   double groundAt(int x,int z){
    int x0=Math.floorDiv(x,4)*4,z0=Math.floorDiv(z,4)*4;double tx=(x-x0)/4.0,tz=(z-z0)/4.0;
    double a=node(x0,z0),b=node(x0+4,z0),c0=node(x0,z0+4),d=node(x0+4,z0+4);
    return (a+(b-a)*tx)*(1-tz)+(c0+(d-c0)*tx)*tz;
   }
   // Read once per column, only where the seabed branch is reached.
   double reliefHere(){if(!reliefReady){relief=r.get();reliefReady=true;}return relief;}
   final BlockPos.MutableBlockPos weatherPosition=new BlockPos.MutableBlockPos();
   @Override public BlockState tryApply(int x,int y,int z){
    String biome=c.getBiome().unwrapKey().map(k->k.identifier().toString()).orElse("");
    boolean alpineEligible=EcotonePolicy.BIOMES.contains(biome);
    boolean shore=landscape&&EcotonePolicy.COASTS.contains(biome);
    boolean forest=landscape&&EcotonePolicy.FOREST_FLOORS.contains(biome);
    boolean woodedMesa=landscape&&EcotonePolicy.WOODED_MESAS.contains(biome);
    boolean cliffEligible=landscape&&EcotonePolicy.CLIFFS.contains(biome);
    boolean seabed=landscape&&EcotonePolicy.SEABEDS.contains(biome);
    if(!alpineEligible&&!shore&&!forest&&!woodedMesa&&!cliffEligible&&!seabed)return null;
    if(!landscape&&y<=c.getSeaLevel()+8)return null;
    if(x!=lastX||z!=lastZ){
     elevation=h.get();climate=t.get();eroded=e.get();
     wetness=landscape?m.get():0;slopeReady=false;reliefReady=false;
     fields=EcotoneModel.sample(seed,x,z,elevation,climate);lastX=x;lastZ=z;
    }
    if(y<elevation-6||y>elevation+2)return null;
    // Under water this is still the slope of the ground. Native outer material
    // conditions continue to determine how deep the substrate rule may apply.
    // Continentalness describes broad land-sea structure; it does not make a
    // native forest an actual beach. The old C<-0.12 shortcut repainted low
    // taiga rooting ground as sand even when its biome remained forest.
    if(shore){
     var cover=LandscapeMaterials.coast(elevation,c.getSeaLevel(),slope(x,z),fields.patch());
     if(cover!=LandscapeMaterials.Cover.NATIVE)return material(cover);
    }
    // 0.9.1: below the shoreline reach, the open seabed gets its own regime: a sediment
    // mosaic on flats, talus on steeper ground, exposed rock on walls and seamount flanks.
    if(seabed&&c.waterHeight()>y&&elevation<=c.getSeaLevel()-LandscapeMaterials.SEABED_MIN_DEPTH){
     var cover=LandscapeMaterials.seabed(elevation,c.getSeaLevel(),slope(x,z),climate,fields.patch(),fields.rockPatch(),reliefHere());
     if(cover!=LandscapeMaterials.Cover.NATIVE)return material(cover);
    }
    if(c.waterHeight()>y)return null;
    // Snow, precipitation and the biome's frozen surface palette must have one
    // authority. A separate H/T mask used to add snow to warm plains and turn
    // parts of snowy native biomes back into green grass. Native precipitation
    // already accounts for the biome and altitude at this exact position.
    weatherPosition.set(x,y,z);
    if(c.getBiome().value().getPrecipitationAt(weatherPosition,c.getSeaLevel())==Biome.Precipitation.SNOW)return null;
    // 0.9.1 audit B2: the cliff rule also covers the low dry band (Y<=sea+8); it was
    // returned before reaching this branch. LandscapeMaterials.cliff keeps sea+4.
    if(cliffEligible&&LandscapeMaterials.cliff(elevation,c.getSeaLevel(),slope(x,z),fields.patch())==LandscapeMaterials.Cover.STONE)
     return Blocks.STONE.defaultBlockState();
    if(y<=c.getSeaLevel()+8)return null;
    if(woodedMesa){
     var cover=LandscapeMaterials.woodedBadlands(elevation,c.getSeaLevel(),slope(x,z),wetness,fields.patch());
     if(cover!=LandscapeMaterials.Cover.NATIVE)return material(cover);
     return null;
    }
    if(forest&&!fields.active()){
     var cover=LandscapeMaterials.forest(elevation,c.getSeaLevel(),slope(x,z),climate,wetness,fields.patch());
     if(cover!=LandscapeMaterials.Cover.NATIVE)return material(cover);
    }
    if(!alpineEligible||!fields.active())return null;
    if(EcotoneModel.rock(fields,elevation,slope(x,z),eroded))return Blocks.STONE.defaultBlockState();
    // Native biome material rules retain their grass, gravel, calcite and other
    // palettes when the local exposed-rock rule has no reason to replace them.
    return null;
   }
  };
 }
 private BlockState material(LandscapeMaterials.Cover cover){
  return (switch(cover){
   case STONE -> Blocks.STONE;
   case GRAVEL -> Blocks.GRAVEL;
   case CLAY -> Blocks.CLAY;
   case BASALT -> Blocks.SMOOTH_BASALT;
   case SAND -> Blocks.SAND;
   case PODZOL -> subsurface?Blocks.DIRT:Blocks.PODZOL;
   case COARSE_DIRT -> subsurface?Blocks.DIRT:Blocks.COARSE_DIRT;
   case GRASS -> subsurface?Blocks.DIRT:Blocks.GRASS_BLOCK;
   case NATIVE -> throw new IllegalArgumentException("Native fallback must remain a null rule result");
  }).defaultBlockState();
 }
}
