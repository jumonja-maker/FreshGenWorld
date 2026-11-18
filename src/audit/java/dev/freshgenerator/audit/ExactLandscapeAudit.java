package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Audit-only fixed screenshot coordinates. No scout, no mutation, no access to user saves. */
public final class ExactLandscapeAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final int X0=-128,Z0=-224,SIDE=512;
    private static final Set<String> SOIL=Set.of("grass_block","dirt","coarse_dirt","rooted_dirt","podzol","mycelium","moss_block","mud","packed_mud","clay","sand","red_sand","gravel","snow_block","stone","deepslate","granite","diorite","andesite","calcite","tuff","sandstone","red_sandstone","terracotta","dripstone_block");
    private ServerLevel level;private BiomeManager biomes;private DensitySampler height;
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private int shutdown=-1;
    private record Probe(String name,int x,int z,int side) { }
    private void loadHalo(int x0,int z0,int side){var chunks=new ArrayList<int[]>();for(int cz=Math.floorDiv(z0,16)-1;cz<=Math.floorDiv(z0+side-1,16)+1;cz++)for(int cx=Math.floorDiv(x0,16)-1;cx<=Math.floorDiv(x0+side-1,16)+1;cx++)chunks.add(new int[]{cx,cz});if("true".equals(System.getenv("FG_EXACT_REVERSE_CHUNKS")))Collections.reverse(chunks);for(var c:chunks)level.getChunk(c[0],c[1]);}
    @Override public void onInitialize(){
        if(!"true".equals(System.getenv("FG_EXACT_LANDSCAPE_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{try{run(server);}catch(Throwable e){System.err.println("EXACT_LANDSCAPE_FAIL");e.printStackTrace();}finally{shutdown=20;}});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
    private static String id(BlockState s){return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();}
    private static boolean soil(BlockState s){String n=id(s).substring(10);return SOIL.contains(n)||n.endsWith("_ore")||n.endsWith("_terracotta");}
    private float h(int x,int z){return height.sampleValue(context,x,0,z);}
    private static String biomeId(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b){return b.unwrapKey().orElseThrow().identifier().toString();}
    private void run(MinecraftServer server)throws Exception{
        level=server.overworld();var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        String settings=generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString();
        if(!settings.equals("freshgenerator:highlands_v2"))throw new AssertionError("Wrong generator "+settings);
        var state=level.getChunkSource().randomState();var registry=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        height=state.getSampler(registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse("freshgenerator:highlands_v2/coherent/height"))).value());
        biomes=level.getBiomeManager().withDifferentSource(generator.getBiomeSource().createCachingResolver(state));
        String probesFile=System.getenv("FG_EXACT_PROBES");
        if(probesFile!=null){
            var probes=G.fromJson(Files.readString(Path.of(probesFile)),Probe[].class);if(probes.length==0||probes.length>8)throw new AssertionError("Unbounded probe list");
            var windows=new ArrayList<Object>();
            for(var p:probes){if(p.side<16||p.side>128||p.side%16!=0)throw new AssertionError("Invalid probe side");
                loadHalo(p.x,p.z,p.side);
                windows.add(window(p.name,p.x,p.z,p.side));
            }
            Files.writeString(Path.of("exact-landscape.json"),G.toJson(Map.of("seed",level.getSeed(),"settings",settings,"windows",windows,"mode","fixed_probes"))+"\n");System.out.println("EXACT_LANDSCAPE_PASS probes="+windows.size());return;
        }
        int parity=0;for(int z:new int[]{Z0,-131,148,Z0+SIDE-1})for(int x:new int[]{X0,-47,77,X0+SIDE-1}){
            float cached=h(x,z),direct=height.sampleValue(SamplerContext.EMPTY_UNCACHED,x,0,z);
            if(Float.floatToRawIntBits(cached)!=Float.floatToRawIntBits(direct))throw new AssertionError("Height cache parity "+x+","+z);parity++;
        }
        if(!"true".equals(System.getenv("FG_EXACT_FULL_SURFACE"))){
            var fields=new LinkedHashMap<String,DensitySampler>();
            for(String name:List.of("temperature","landscape/climate/reference_temperature","landscape/climate/surface_temperature","vegetation","continents","erosion","ridges"))
                fields.put(name,state.getSampler(registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse("freshgenerator:highlands_v2/"+name))).value()));
            try(var out=Files.newBufferedWriter(Path.of("exact-climate.csv"))){
                out.write("x,z,height,temperature,reference_temperature,surface_temperature,moisture,continentalness,erosion,weirdness,biome_ground,biome_h32,grass_rgb,foliage_rgb\n");
                for(int z=Z0;z<Z0+SIDE;z+=4)for(int x=X0;x<X0+SIDE;x+=4){
                    float H=h(x,z);StringBuilder row=new StringBuilder().append(x).append(',').append(z).append(',').append(H);
                    for(var f:fields.values())row.append(',').append(f.sampleValue(context,x,0,z));
                    var b=biomes.getBiome(x,(int)Math.floor(H),z);row.append(',').append(biomeId(b)).append(',').append(biomeId(biomes.getBiome(x,(int)Math.floor(H)+32,z))).append(',').append(b.value().getGrassColor(x,z)).append(',').append(b.value().getFoliageColor());out.write(row.append('\n').toString());
                }
            }
            var windows=new ArrayList<Object>();
            if(!"true".equals(System.getenv("FG_EXACT_CLIMATE_ONLY"))){
                for(int[] spec:new int[][]{{-64,-144,32},{-48,-112,32},{0,96,96}}){
                    for(int cz=Math.floorDiv(spec[1],16)-1;cz<=Math.floorDiv(spec[1]+spec[2]-1,16)+1;cz++)for(int cx=Math.floorDiv(spec[0],16)-1;cx<=Math.floorDiv(spec[0]+spec[2]-1,16)+1;cx++)level.getChunk(cx,cz);
                }
                windows.add(window("ground-west",-64,-144,32));windows.add(window("ground-north",-48,-112,32));windows.add(window("snow-grove",0,96,96));
            }
            Files.writeString(Path.of("exact-landscape.json"),G.toJson(Map.of("seed",level.getSeed(),"settings",settings,"climate_origin_xz",List.of(X0,Z0),"climate_side",SIDE,"climate_step",4,"height_cache_bit_comparisons",parity,"windows",windows))+"\n");
            System.out.println("EXACT_LANDSCAPE_PASS climate_columns="+(SIDE/4)*(SIDE/4)+" windows="+windows.size());return;
        }
        // FULL halo settles native features before reading the core. This is the isolated lab world.
        int loaded=0;for(int cz=Z0/16-1;cz<=(Z0+SIDE-1)/16+1;cz++)for(int cx=X0/16-1;cx<=(X0+SIDE-1)/16+1;cx++){
            level.getChunk(cx,cz);if(++loaded%64==0)System.out.println("EXACT_LANDSCAPE_CHUNKS "+loaded);
        }
        var biomeCounts=new TreeMap<String,Integer>();var materialCounts=new TreeMap<String,Integer>();
        var at=new BlockPos.MutableBlockPos();
        try(var out=Files.newBufferedWriter(Path.of("exact-surface.csv"))){
            out.write("x,z,height,ground_y,ground,top_y,top,snow_layer_y,snow_layers,snow_block,surface_biome,stored_quart_biome,grass_rgb,foliage_rgb,log_blocks,leaf_blocks,max_log_y,max_leaf_y,root_y,root_species,stem_length\n");
            for(int z=Z0;z<Z0+SIDE;z++)for(int x=X0;x<X0+SIDE;x++){
                float H=h(x,z);var chunk=level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
                int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z)-1,ground=-65,snowY=-65,layers=0,logs=0,leaves=0,maxLog=-65,maxLeaf=-65,rootY=-65,stem=0;String groundId="minecraft:air",rootSpecies="none";
                at.set(x,Math.clamp(top,-64,319),z);String topId=id(chunk.getBlockState(at));
                for(int y=Math.min(319,Math.max(top,(int)Math.ceil(H)+40));y>=Math.max(-64,(int)Math.floor(H)-24);y--){
                    at.set(x,y,z);var b=chunk.getBlockState(at);String name=id(b);
                    if(ground==-65&&soil(b)){ground=y;groundId=name;}
                    if(name.equals("minecraft:snow")&&snowY==-65){snowY=y;layers=Integer.parseInt(b.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS).toString());}
                    if(b.is(BlockTags.LEAVES)){leaves++;maxLeaf=Math.max(maxLeaf,y);}
                    if(b.is(BlockTags.LOGS)){
                        logs++;maxLog=Math.max(maxLog,y);var below=chunk.getBlockState(at.below());
                        if(soil(below)&&!below.is(BlockTags.LEAVES)&&!below.is(BlockTags.LOGS)){
                            int n=0;while(n<64&&y+n<320&&chunk.getBlockState(at.offset(0,n,0)).is(BlockTags.LOGS))n++;
                            if(n>stem){rootY=y;stem=n;rootSpecies=name;}
                        }
                    }
                }
                int by=ground==-65?(int)Math.floor(H):ground;var surface=biomes.getBiome(x,by,z);String biome=biomeId(surface),stored=biomeId(level.getNoiseBiome(Math.floorDiv(x,4),Math.floorDiv(by,4),Math.floorDiv(z,4)));
                biomeCounts.merge(biome,1,Integer::sum);materialCounts.merge(groundId,1,Integer::sum);
                out.write(x+","+z+","+H+","+ground+","+groundId+","+top+","+topId+","+snowY+","+layers+","+groundId.equals("minecraft:snow_block")+","+biome+","+stored+","+surface.value().getGrassColor(x,z)+","+surface.value().getFoliageColor()+","+logs+","+leaves+","+maxLog+","+maxLeaf+","+rootY+","+rootSpecies+","+stem+"\n");
            }
        }
        var windows=new ArrayList<Object>();windows.add(window("ground-west",-64,-144,32));windows.add(window("ground-north",-48,-112,32));windows.add(window("snow-grove",0,128,96));
        var result=new LinkedHashMap<String,Object>();result.put("seed",level.getSeed());result.put("settings",settings);result.put("surface_origin_xz",List.of(X0,Z0));result.put("surface_side",SIDE);result.put("surface_columns",SIDE*SIDE);result.put("full_chunks_including_halo",loaded);result.put("height_cache_bit_comparisons",parity);result.put("surface_biomes",biomeCounts);result.put("ground_materials",materialCounts);result.put("windows",windows);
        result.put("scope","Fixed screenshot area in a fresh isolated server. Raw states and actual stored quart biomes; no screenshot acceptance claim. Stem clusters are spatially grouped rooted log columns, not exact tree individuals. Ground scan bounded H−24..max(top,H+40); unknown ground stays −65. Structures controlled by runner.");
        Files.writeString(Path.of("exact-landscape.json"),G.toJson(result)+"\n");System.out.println("EXACT_LANDSCAPE_PASS columns="+SIDE*SIDE);
    }
    private Object window(String name,int x0,int z0,int side)throws Exception{
        int plane=side*side;var palette=new ArrayList<String>();var index=new HashMap<String,Integer>();var meta=new LinkedHashMap<String,Object>();
        var states=new int[plane*384];var at=new BlockPos.MutableBlockPos();
        int snowLayers=0,nativeRainSnowLayers=0;var snowExamples=new ArrayList<Object>();var surfaceBiomes=new TreeMap<String,Integer>();var seaBiomes=new TreeMap<String,Integer>();
        try(var columns=Files.newBufferedWriter(Path.of(name+"-columns.csv"))){
        columns.write("x,z,height,actual_ground_y,actual_ground,water_top_y,zoom_biome_ground,stored_biome_ground,biome_h,biome_sea,biome_sea32\n");
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int actualGround=-65,waterTop=-65;String ground="minecraft:air";
            var chunk=level.getChunk(Math.floorDiv(x0+x,16),Math.floorDiv(z0+z,16));for(int y=-64;y<320;y++){
                at.set(x0+x,y,z0+z);var block=chunk.getBlockState(at);String s=block.toString();int id=index.computeIfAbsent(s,k->{palette.add(k);return palette.size()-1;});states[(y+64)*plane+z*side+x]=id;
                if(soil(block)){actualGround=y;ground=id(block);}
                if(id(block).equals("minecraft:water"))waterTop=y;
                if(id(block).equals("minecraft:snow")){snowLayers++;var nativeBiome=level.getBiome(at);var precipitation=nativeBiome.value().getPrecipitationAt(at,level.getSeaLevel());if(precipitation!=net.minecraft.world.level.biome.Biome.Precipitation.SNOW){nativeRainSnowLayers++;if(snowExamples.size()<16)snowExamples.add(Map.of("x",x0+x,"y",y,"z",z0+z,"biome",biomeId(nativeBiome),"native_precipitation",precipitation.toString()));}}
            }
            int xx=x0+x,zz=z0+z;float H=h(xx,zz);String actual=biomeId(level.getBiome(new BlockPos(xx,Math.max(-64,actualGround),zz))),stored=biomeId(level.getNoiseBiome(Math.floorDiv(xx,4),Math.floorDiv(Math.max(-64,actualGround),4),Math.floorDiv(zz,4))),atH=biomeId(biomes.getBiome(xx,(int)Math.ceil(H)-1,zz)),atSea=biomeId(biomes.getBiome(xx,level.getSeaLevel(),zz)),aboveSea=biomeId(biomes.getBiome(xx,level.getSeaLevel()+32,zz));
            surfaceBiomes.merge(actual,1,Integer::sum);seaBiomes.merge(atSea,1,Integer::sum);
            columns.write(xx+","+zz+","+H+","+actualGround+","+ground+","+waterTop+","+actual+","+stored+","+atH+","+atSea+","+aboveSea+"\n");
        }
        }
        meta.put("shape_xyz",List.of(side,384,side));meta.put("origin_xyz",List.of(x0,-64,z0));meta.put("palette",palette);byte[] header=G.toJson(meta).getBytes(StandardCharsets.UTF_8);var digest=MessageDigest.getInstance("SHA-256");digest.update(header);
        String file=name+"-states.bin.gz";try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(file))))){out.writeInt(header.length);out.write(header);for(int id:states){out.writeShort(id);digest.update((byte)(id>>>8));digest.update((byte)id);}}
        var report=new LinkedHashMap<String,Object>();report.put("name",name);report.put("origin_xyz",List.of(x0,-64,z0));report.put("side",side);report.put("raw_file",file);report.put("raw_state_ids_sha256",HexFormat.of().formatHex(digest.digest()));report.put("snow_layer_blocks",snowLayers);report.put("snow_layers_in_native_rain_biomes",nativeRainSnowLayers);report.put("snow_native_precipitation_mismatch_examples",snowExamples);report.put("actual_surface_biomes",surfaceBiomes);report.put("biomes_at_sea_level",seaBiomes);
        System.out.println("EXACT_LANDSCAPE_WINDOW "+name+" snow_layers="+snowLayers+" native_rain_snow_layers="+nativeRainSnowLayers);return report;
    }
}
