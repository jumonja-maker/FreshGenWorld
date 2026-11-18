package dev.freshgenerator.audit;

import com.google.gson.*;
import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.forest.ForestEnvironment;
import dev.freshgenerator.forest.ForestTelemetry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline evidence from real seeded native functions and optional FULL chunks.
 * This test entrypoint is never included in the playable mod. */
public final class LandscapeWorldAudit implements ModInitializer {
 private static final String P="freshgenerator:highlands_v2/";
 private final SamplerContext ctx=SamplerContext.builder().enableCaches().build();
 private final Map<String,DensitySampler> fields=new HashMap<>();
 private final Map<String,Integer> palette=new LinkedHashMap<>();
 private ServerLevel level;private NoiseBasedChunkGenerator gen;private RandomState state;private BiomeManager biomes;
 private record Site(String name,int x,int z){}
 @Override public void onInitialize(){
  if(!"true".equals(System.getenv("FG_LANDSCAPE_AUDIT")))return;
  ServerLifecycleEvents.SERVER_STARTED.register(server->{try{run(server);}catch(Throwable e){System.err.println("LANDSCAPE_FAIL");e.printStackTrace();}finally{server.halt(false);}});
 }
 private float value(String name,int x,int z){if(!fields.containsKey(name))return 0;float v=fields.get(name).sampleValue(ctx,x,0,z);if(!Float.isFinite(v))throw new AssertionError(name);return v;}
 private float height(int x,int z){return value("coherent/height",x,z);}
 private String biome(int x,int z,float h){return biomes.getBiome(x,(int)Math.ceil(h),z).unwrapKey().orElseThrow().identifier().toString();}
 private double slope(int x,int z){return Math.hypot((height(x+4,z)-height(x-4,z))/8.0,(height(x,z+4)-height(x,z-4))/8.0);}
 private void run(MinecraftServer server)throws Exception{
  level=server.overworld();gen=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();state=level.getChunkSource().randomState();
  if(!gen.generatorSettings().unwrapKey().orElseThrow().identifier().toString().equals("freshgenerator:highlands_v2"))throw new AssertionError("Wrong preset");
  boolean baseline="true".equals(System.getenv("FG_LANDSCAPE_BASELINE"));
  for(String key:List.of("coherent/height","temperature","vegetation","landscape/mountain","landscape/giant","landscape/coast_rock","landscape/continentality")){
   if(baseline&&(key.equals("landscape/giant")||key.equals("landscape/coast_rock")))continue;
   String actual=baseline&&key.equals("landscape/mountain")?"mountain_mask":baseline&&key.equals("landscape/continentality")?"continents":key;
   var f=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+actual))).value();fields.put(key,state.getSampler(f));
  }
  Map<String,Object> cacheCheck=verifyFreshSamplerContexts();
  for(String key:List.of("landscape/form_massif","landscape/form_plateau","landscape/form_ridge")){
   server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).get(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+key)))
       .ifPresent(f->fields.put(key,state.getSampler(f.value())));
  }
  biomes=level.getBiomeManager().withDifferentSource(gen.getBiomeSource().createCachingResolver(state));
  float max=-Float.MAX_VALUE,min=Float.MAX_VALUE;double coastScore=Double.MAX_VALUE,forestScore=Double.MAX_VALUE;
  Site peak=null,coast=null,forest=null,rocky=null,mixed=null;int land=0,giant=0,tall=0;int n=257,step=128,origin=-16384;
  double mixedScore=Double.MAX_VALUE;
  double rockyScore=Double.MAX_VALUE;Map<String,Integer> forms=new TreeMap<>();
  try(var w=Files.newBufferedWriter(Path.of("regional.csv"))){header(w);for(int iz=0;iz<n;iz++)for(int ix=0;ix<n;ix++){
   int x=origin+ix*step,z=origin+iz*step;float h=height(x,z);String b=biome(x,z,h);row(w,x,z,h,b);
   if(h < -60 || h > 318)throw new AssertionError("Height outside supported envelope: "+h);
   if(h>max){max=h;peak=new Site("mountain",x,z);}min=Math.min(min,h);if(h>=63)land++;
   if(value("landscape/giant",x,z)>.5)giant++;if(h>=270)tall++;
   if(value("landscape/mountain",x,z)>.5)for(String f:List.of("massif","plateau","ridge"))if(value("landscape/form_"+f,x,z)>.65)forms.merge(f,1,Integer::sum);
   boolean shore=b.contains("ocean")||b.endsWith("beach")||b.endsWith("stony_shore");
   if(h>59&&h<70&&shore){double score=Math.abs(h-64)+Math.hypot(x,z)/18000;if(score<coastScore){coastScore=score;coast=new Site("coast",x,z);}}
   if(h>62&&h<78&&(shore||value("landscape/continentality",x,z)<-.12)&&slope(x,z)>.45){double score=Math.abs(h-67)/10+Math.hypot(x,z)/18000;if(score<rockyScore){rockyScore=score;rocky=new Site("rocky_coast",x,z);}}
   if(h>68&&h<150&&ForestEnvironment.family(b)!=null){double score=Math.hypot(x,z)/10000+slope(x,z);if(score<forestScore){forestScore=score;forest=new Site("forest",x,z);}}
   if(h>72&&h<140&&ordinaryForest(b)&&value("temperature",x,z)>-.4&&value("temperature",x,z)<.5){
    double score=Math.hypot(x,z);if(score<mixedScore)for(int[] d:new int[][]{{24,0},{-24,0},{0,24},{0,-24}}){
     String other=biome(x+d[0],z+d[1],height(x+d[0],z+d[1]));
     if(ordinaryForest(other)&&!other.equals(b)){mixedScore=score;mixed=new Site("mixed_forest",x,z);break;}
    }
   }
  }}
  if(peak==null||coast==null||forest==null||land==0||land==n*n)throw new AssertionError("Missing representative land/coast/forest");
  List<Site> sites=new ArrayList<>(List.of(peak,coast,forest));if(rocky!=null)sites.add(rocky);if(mixed!=null)sites.add(mixed);List<Object> details=new ArrayList<>();
  if(System.getenv("FG_LANDSCAPE_SITES")!=null)sites=Arrays.asList(new Gson().fromJson(Files.readString(Path.of(System.getenv("FG_LANDSCAPE_SITES"))),Site[].class));
  if("true".equals(System.getenv("FG_LANDSCAPE_FOREST_ONLY")))sites=sites.stream().filter(s->s.name().contains("forest")).toList();
  for(Site site:sites){try(var w=Files.newBufferedWriter(Path.of(site.name()+".csv"))){header(w);for(int z=site.z()-768;z<=site.z()+768;z+=8)for(int x=site.x()-768;x<=site.x()+768;x+=8){float h=height(x,z);row(w,x,z,h,biome(x,z,h));}}
   if("true".equals(System.getenv("FG_LANDSCAPE_FULL")))details.add(full(site));
  }
  var report=new LinkedHashMap<String,Object>();report.put("seed",level.getSeed());report.put("samples",n*n);report.put("side",n);report.put("step",step);report.put("origin",origin);
  report.put("native_cache_equivalence",cacheCheck);
  report.put("min_height",min);report.put("max_height",max);report.put("land_fraction",land/(double)(n*n));report.put("giant_mask_fraction",giant/(double)(n*n));report.put("height_over_270_fraction",tall/(double)(n*n));
  report.put("dominant_mountain_forms",forms);
  report.put("forest_telemetry",ForestTelemetry.snapshot());
  report.put("biomes",palette);report.put("sites",sites);report.put("full_windows",details);report.put("scope","Native function maps plus optional actual FULL blocks; masks are area samples, not per-mountain probabilities; no client/DH rendering.");
  Files.writeString(Path.of("landscape.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("LANDSCAPE_PASS "+new Gson().toJson(report));
 }
 private static boolean ordinaryForest(String id){return Set.of("minecraft:forest","minecraft:birch_forest","minecraft:old_growth_birch_forest",
     "minecraft:taiga","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga").contains(id);}
 private Map<String,Object> verifyFreshSamplerContexts()throws Exception{
  // Independent context per position, shared only between that
  // position's height and reference-temperature reads. Never share mutable caches
  // between workers, dimensions, seeds, or independent placement invocations.
  int[] coordinates={-16385,-1025,-129,-33,-17,-16,-1,0,1,3,4,15,16,127,128,16383};
  List<String> names=List.of("coherent/height","temperature");
  List<DensitySampler> samplers=names.stream().map(fields::get).toList();
  List<Callable<Integer>> tasks=new ArrayList<>();long started=System.nanoTime();
  for(int order=0;order<2;order++){
   final boolean reverse=order!=0;
   tasks.add(()->{int compared=0;
    for(int step=0;step<coordinates.length*coordinates.length;step++){
     int index=reverse?coordinates.length*coordinates.length-1-step:step;
     int x=coordinates[index%coordinates.length],z=coordinates[index/coordinates.length];
     SamplerContext fresh=SamplerContext.builder().enableCaches().build();
     for(int axis=0;axis<samplers.size();axis++){
      DensitySampler sampler=samplers.get(axis);
      float before=sampler.sampleValue(SamplerContext.EMPTY_UNCACHED,x,0,z);
      float after=sampler.sampleValue(fresh,x,0,z);
      if(!Float.isFinite(before)||!Float.isFinite(after)||Float.floatToRawIntBits(before)!=Float.floatToRawIntBits(after))
       throw new AssertionError("Cache changed "+names.get(axis)+" at "+x+","+z+": "+before+" != "+after);
      compared++;
     }
    }
    return compared;
   });
  }
  int comparisons=0;
  try(var executor=Executors.newFixedThreadPool(2)){
   for(var result:executor.invokeAll(tasks))comparisons+=result.get();
  }
  var report=new LinkedHashMap<String,Object>();
  report.put("positions_per_traversal",coordinates.length*coordinates.length);report.put("parallel_traversals",2);
  report.put("functions",names);report.put("float_bit_comparisons",comparisons);report.put("different_float_bits",0);
  report.put("elapsed_ms",(System.nanoTime()-started)/1_000_000.0);
  report.put("scope","EMPTY_UNCACHED versus fresh caches per position; forward and reverse traversals on two threads; Y=0 height and reference temperature");
  System.out.println("LANDSCAPE_CACHE_PASS "+new Gson().toJson(report));return report;
 }
 private static void header(Writer w)throws IOException{w.write("x,z,height,biome,temperature,moisture,mountain,giant,coast_rock,forest_retention,form_massif,form_plateau,form_ridge\n");}
 private void row(Writer w,int x,int z,float h,String b)throws IOException{
  int id=palette.computeIfAbsent(b,k->palette.size());double retention=0;var family=ForestEnvironment.family(b);
  if(family!=null&&h>63){double average=(height(x-16,z)+height(x+16,z)+height(x,z-16)+height(x,z+16))/4.0;
   var site=new ForestEcology.Site(h,63,slope(x,z),h-average,value("temperature",x,z),value("vegetation",x,z),0,0);
   retention=ForestEcology.sample(level.getSeed()^0x6F7265737445434FL,x,z,family,site).retention();}
  w.write(x+","+z+","+h+","+id+","+value("temperature",x,z)+","+value("vegetation",x,z)+","+value("landscape/mountain",x,z)+","+value("landscape/giant",x,z)+","+value("landscape/coast_rock",x,z)+","+retention+","+value("landscape/form_massif",x,z)+","+value("landscape/form_plateau",x,z)+","+value("landscape/form_ridge",x,z)+"\n");
 }
 private Object full(Site s)throws Exception{
  int cx=Math.floorDiv(s.x(),16),cz=Math.floorDiv(s.z(),16),side=48,x0=(cx-1)*16,z0=(cz-1)*16;
  long haloStart=System.nanoTime();
  for(int x=cx-2;x<=cx+2;x++)for(int z=cz-2;z<=cz+2;z++)level.getChunk(x,z);
  double haloMs=(System.nanoTime()-haloStart)/1_000_000.0;long scanStart=System.nanoTime();
  int logColumns=0,leafColumns=0,airBelow=0;Set<String> blocks=new TreeSet<>();
  Map<String,Integer> groundBlocks=new TreeMap<>(),treeBlocks=new TreeMap<>();
  Map<Integer,Integer> canopyHeights=new TreeMap<>(),rootedStemHeights=new TreeMap<>();
  try(var w=Files.newBufferedWriter(Path.of(s.name()+"-blocks.csv"))){w.write("x,z,top_y,block,base_y,leaves,logs,ground_block,canopy_height,rooted_stem_height\n");
   for(int z=z0;z<z0+side;z++)for(int x=x0;x<x0+side;x++){
    var chunk=level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));int base=gen.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,state)-1;
    boolean leaves=false,logs=false;int top=base,leafTop=base;String topBlock="minecraft:air";
    for(int y=Math.min(319,Math.max(base+48,70));y>=Math.max(-63,base-6);y--){var b=chunk.getBlockState(new BlockPos(x,y,z));
     if(b.is(BlockTags.LEAVES)){leaves=true;leafTop=Math.max(leafTop,y);}if(b.is(BlockTags.LOGS))logs=true;
     if(b.is(BlockTags.LOGS))treeBlocks.merge(BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString(),1,Integer::sum);
     if(topBlock.equals("minecraft:air")&&!b.isAir()){top=y;topBlock=BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString();}
     if(y<=base-2&&y>=base-5&&b.isAir())airBelow++;
    }
    String ground=BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(new BlockPos(x,base,z)).getBlock()).toString();groundBlocks.merge(ground,1,Integer::sum);
    int stem=0;while(stem<48&&base+stem+1<320&&chunk.getBlockState(new BlockPos(x,base+stem+1,z)).is(BlockTags.LOGS))stem++;
    if(stem>=2)rootedStemHeights.merge(stem,1,Integer::sum);
    int canopy=leafTop-base;if(leaves&&canopy>0)canopyHeights.merge(canopy,1,Integer::sum);
    if(logs)logColumns++;if(leaves)leafColumns++;blocks.add(topBlock);w.write(x+","+z+","+top+","+topBlock+","+base+","+leaves+","+logs+","+ground+","+canopy+","+stem+"\n");
   }}
  double scanMs=(System.nanoTime()-scanStart)/1_000_000.0;
  System.out.println("LANDSCAPE_FULL_TIMING site="+s.name()+" halo_ms="+haloMs+" column_scan_ms="+scanMs);
  Map<String,Object> report=new LinkedHashMap<>();report.put("site",s);report.put("columns",side*side);report.put("log_columns",logColumns);
  report.put("leaf_columns",leafColumns);report.put("near_surface_air_samples",airBelow);report.put("top_blocks",blocks);
  report.put("full_halo_ms",haloMs);report.put("column_scan_ms",scanMs);report.put("ground_blocks",groundBlocks);report.put("tree_log_blocks",treeBlocks);
  report.put("canopy_height_columns",canopyHeights);report.put("rooted_stem_height_columns",rootedStemHeights);
  return report;
 }
}
