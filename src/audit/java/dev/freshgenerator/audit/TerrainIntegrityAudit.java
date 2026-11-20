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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Read-only bounded native FULL-block diagnosis. No runtime helpers, GUI, terrain
 * edits or dependence on current-only cave classes: same observer can run 0.5.2. */
public final class TerrainIntegrityAudit implements ModInitializer {
    private static final String P="freshgenerator:highlands_v2/";
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final int MIN=-64,HEIGHT=384;
    private record Site(String name,int x,int z,int side) { }
    private record Piece(int cells,List<Integer> minimum,List<Integer> maximum,boolean boundary,boolean exteriorAdjacent) { }
    private static final Set<String> ROCK=Set.of("stone","deepslate","granite","diorite","andesite","tuff","calcite","bedrock",
        "dirt","grass_block","coarse_dirt","rooted_dirt","podzol","mycelium","gravel","sand","red_sand","sandstone","red_sandstone",
        "clay","terracotta","mud","packed_mud","smooth_basalt","amethyst_block","budding_amethyst",
        "sculk","sculk_catalyst","moss_block","dripstone_block","basalt","polished_basalt","blackstone",
        // 26.3 sulfur caves palette: native host rock, not decoration (otherwise deepslate
        // lenses inside cinnabar were reported as detached rock).
        "cinnabar","sulfur",
        "infested_stone","infested_deepslate","infested_cobblestone","infested_stone_bricks",
        "infested_mossy_stone_bricks","infested_chiseled_stone_bricks","infested_cracked_stone_bricks");
    private final Map<String,DensitySampler> samplers=new LinkedHashMap<>();
    private final SamplerContext bulkContext=SamplerContext.builder().enableCaches().build();
    private int cacheComparisons,cacheMismatches;
    private final List<Map<String,Object>> cacheExamples=new ArrayList<>();
    private ServerLevel level;private int shutdown=-1;
    @Override public void onInitialize(){
        if(!"true".equals(System.getenv("FG_TERRAIN_INTEGRITY_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{try{run(server);}catch(Throwable e){System.err.println("TERRAIN_INTEGRITY_FAIL");e.printStackTrace();}finally{shutdown=40;}});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
    private float sample(String name,int x,int y,int z){
        var sampler=samplers.get(name);return sampler==null?Float.NaN:sampler.sampleValue(SamplerContext.EMPTY_UNCACHED,x,y,z);
    }
    private float bulk(String name,int x,int y,int z){var s=samplers.get(name);return s==null?Float.NaN:s.sampleValue(bulkContext,x,y,z);}
    private void verifyCache(int x,int z){
        for(var e:samplers.entrySet())for(int y:new int[]{-20,0,128,256}){
            float expected=e.getValue().sampleValue(SamplerContext.EMPTY_UNCACHED,x,y,z),actual=e.getValue().sampleValue(bulkContext,x,y,z);cacheComparisons++;
            if(Float.floatToIntBits(expected)!=Float.floatToIntBits(actual)){
                cacheMismatches++;if(cacheExamples.size()<24)cacheExamples.add(Map.of("field",e.getKey(),"x",x,"y",y,"z",z,"uncached",expected,"cached",actual));
            }
        }
    }
    private void run(MinecraftServer server)throws Exception {
        level=server.overworld();var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        if(!"freshgenerator:highlands_v2".equals(generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString()))throw new AssertionError("Wrong generator");
        var registry=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        for(String key:List.of("coherent/height","coherent/surface_density","landscape/mountain","cave_network/mountain_gate",
                "cave_network/entrance_gate","cave_network/body","cave_network/ceiling","cave_network/depth_limit","cave_network/final_body"))
            registry.get(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+key))).ifPresent(v->samplers.put(key,level.getChunkSource().randomState().getSampler(v.value())));
        if(!samplers.containsKey("coherent/height"))throw new AssertionError("Missing coherent height");
        List<Site> sites=List.of(new Site("north_shelves",140,-210,96),new Site("south_walls",180,528,128),new Site("west_ravine",-112,728,96));
        if(System.getenv("FG_TERRAIN_INTEGRITY_SITES")!=null)sites=Arrays.asList(G.fromJson(Files.readString(Path.of(System.getenv("FG_TERRAIN_INTEGRITY_SITES"))),Site[].class));
        Map<String,Object> report=new LinkedHashMap<>();report.put("seed",level.getSeed());report.put("sites",sites);report.put("available_density_fields",samplers.keySet());
        report.put("regional",regional());List<Map<String,Object>> windows=new ArrayList<>();
        for(Site site:sites)windows.add(window(site));report.put("windows",windows);
        report.put("cache_bit_comparisons_total",cacheComparisons);report.put("cache_bit_mismatches_total",cacheMismatches);report.put("cache_mismatch_examples_total",cacheExamples);
        report.put("scope","Actual FULL blocks in finite 96/128-block windows. Natural rock/soil excludes trees, leaves, snow and ice. Boundary-touching solid components are unknown, not counted floating. Exterior air is seeded from top and side air above the coherent surface; no player walkability claim. Raw states and all density failures are retained.");
        Files.writeString(Path.of("terrain-integrity.json"),G.toJson(report)+"\n");
        if(cacheMismatches!=0)throw new AssertionError("Native density cache differs from uncached reference: "+cacheMismatches);
        System.out.println("TERRAIN_INTEGRITY_PASS windows="+windows.size());
    }
    private Map<String,Object> regional()throws Exception {
        int count=0,different=0;float maximum=0;List<Map<String,Object>> examples=new ArrayList<>();
        try(var out=Files.newBufferedWriter(Path.of("regional-density.csv"))){
            out.write("x,z,height_y0,height_y128,height_y256,mountain,mountain_gate,entrance_gate,ceiling,body_at_h_minus8,limit_at_h_minus8,final_at_h_minus8,final_at_y_minus20\n");
            for(int z=-400;z<=1000;z+=8)for(int x=-512;x<=512;x+=8){
                float h=bulk("coherent/height",x,0,z),h128=bulk("coherent/height",x,128,z),h256=bulk("coherent/height",x,256,z);
                if(!Float.isFinite(h)||!Float.isFinite(h128)||!Float.isFinite(h256))throw new AssertionError("Nonfinite actual native height");
                float delta=Math.max(Math.abs(h-h128),Math.abs(h-h256));maximum=Math.max(maximum,delta);if(delta>1e-5){different++;if(examples.size()<24)examples.add(Map.of("x",x,"z",z,"height_y0",h,"height_y128",h128,"height_y256",h256));}
                int y=(int)Math.floor(h)-8;
                out.write(x+","+z+","+h+","+h128+","+h256+","+bulk("landscape/mountain",x,0,z)+","+bulk("cave_network/mountain_gate",x,0,z)+","+bulk("cave_network/entrance_gate",x,0,z)+","+bulk("cave_network/ceiling",x,0,z)+","+bulk("cave_network/body",x,y,z)+","+bulk("cave_network/depth_limit",x,y,z)+","+bulk("cave_network/final_body",x,y,z)+","+bulk("cave_network/final_body",x,-20,z)+"\n");
                if(count%177==0)verifyCache(x,z);count++;
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("samples",count);result.put("step",8);result.put("bounds_xz",List.of(-512,-400,512,1000));result.put("height_y_dependent_columns",different);result.put("maximum_height_y_dependence",maximum);result.put("examples",examples);result.put("cache_bit_comparisons",cacheComparisons);result.put("cache_bit_mismatches",cacheMismatches);result.put("cache_mismatch_examples",cacheExamples);result.put("sampling_mode","Bulk cached at every grid/FULL column; uncached bit references for every available field at Y -20,0,128,256 on each 177th regional point and each 128th FULL column. Earlier immutable attempts retain complete 22704-point uncached height CSVs for separate all-point offline parity checks.");return result;
    }
    private static byte kind(BlockState block,String name){
        if(block.isAir())return 0;
        if(block.getFluidState().is(FluidTags.WATER))return 3;if(block.getFluidState().is(FluidTags.LAVA))return 4;
        String local=name.substring(name.indexOf(':')+1);
        if(ROCK.contains(local)||local.endsWith("_ore")||local.endsWith("_terracotta"))return 1;
        return 2;
    }
    private Map<String,Object> window(Site site)throws Exception {
        int side=site.side();if(side<32||side>128||side%16!=0||!site.name().matches("[a-zA-Z0-9_-]+"))throw new AssertionError("Unsafe finite site");
        int x0=Math.floorDiv(site.x()-side/2,16)*16,z0=Math.floorDiv(site.z()-side/2,16)*16,plane=side*side,size=plane*HEIGHT;
        long started=System.nanoTime();int requested=0;
        List<int[]> requests=new ArrayList<>();
        for(int z=Math.floorDiv(z0,16)-1;z<=Math.floorDiv(z0+side-1,16)+1;z++)for(int x=Math.floorDiv(x0,16)-1;x<=Math.floorDiv(x0+side-1,16)+1;x++)requests.add(new int[]{x,z});
        boolean reverse="true".equals(System.getenv("FG_TERRAIN_REVERSE_CHUNKS"));
        if(reverse)Collections.reverse(requests);
        for(int[] request:requests){level.getChunk(request[0],request[1]);requested++;}
        byte[] cells=new byte[size];char[] ids=new char[size];float[] surface=new float[plane],surface128=new float[plane];
        int[] highest=new int[plane],roof=new int[plane],exteriorRoof=new int[plane],maxAirRun=new int[plane],thinRun=new int[plane],needleRun=new int[plane];Arrays.fill(highest,MIN-1);
        List<String> palette=new ArrayList<>();Map<BlockState,Integer> stateIds=new IdentityHashMap<>();Map<BlockState,Byte> kinds=new IdentityHashMap<>();
        var at=new BlockPos.MutableBlockPos();long air=0,natural=0;
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int col=z*side+x;surface[col]=bulk("coherent/height",x0+x,0,z0+z);surface128[col]=bulk("coherent/height",x0+x,128,z0+z);
            if(col%128==0)verifyCache(x0+x,z0+z);
            var chunk=level.getChunk(Math.floorDiv(x0+x,16),Math.floorDiv(z0+z,16));int run=0;
            for(int y=MIN;y<MIN+HEIGHT;y++){
                at.set(x0+x,y,z0+z);var b=chunk.getBlockState(at);int i=(y-MIN)*plane+col;
                int id=stateIds.computeIfAbsent(b,s->{int n=palette.size();palette.add(s.toString());return n;});if(id>65535)throw new AssertionError("Palette overflow");ids[i]=(char)id;
                byte type=kinds.computeIfAbsent(b,s->kind(s,BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()));cells[i]=type;
                if(type==1){highest[col]=y;natural++;}if(type==0)air++;
                int cover=(int)Math.floor(surface[col])-y;
                if(type==0&&cover>=1&&cover<=40)roof[col]++;
                if(type==0&&cover>=1&&cover<=64){run++;maxAirRun[col]=Math.max(maxAirRun[col],run);}else run=0;
            }
        }
        boolean[] exterior=exterior(cells,surface,side);long extNear=0;int maxPenetration=0,thin=0,needles=0;
        for(int z=1;z<side-1;z++)for(int x=1;x<side-1;x++){
            int col=z*side+x,runThin=0,runNeedle=0;
            for(int yy=0;yy<HEIGHT;yy++){
                int i=yy*plane+col,cover=(int)Math.floor(surface[col])-(yy+MIN);
                if(exterior[i]&&cover>=1){maxPenetration=Math.max(maxPenetration,cover);if(cover<=40){exteriorRoof[col]++;extNear++;}}
                boolean inBand=cover>=-8&&cover<=64&&cells[i]==1;
                boolean thinCell=inBand&&((cells[i-1]==0&&cells[i+1]==0)||(cells[i-side]==0&&cells[i+side]==0))
                    &&(exterior[i-1]||exterior[i+1]||exterior[i-side]||exterior[i+side]);
                int adjacent=(cells[i-1]==0?1:0)+(cells[i+1]==0?1:0)+(cells[i-side]==0?1:0)+(cells[i+side]==0?1:0);
                boolean needle=inBand&&adjacent>=3;
                if(thinCell){thin++;runThin++;thinRun[col]=Math.max(thinRun[col],runThin);}else runThin=0;
                if(needle){needles++;runNeedle++;needleRun[col]=Math.max(needleRun[col],runNeedle);}else runNeedle=0;
            }
        }
        List<Piece> detached=new ArrayList<>(),unknown=new ArrayList<>();long grounded=solidPieces(cells,exterior,side,x0,z0,detached,unknown);
        List<Piece> cuts=airCuts(cells,exterior,surface,side,x0,z0);
        try(var out=Files.newBufferedWriter(Path.of(site.name()+"-columns.csv"))){
            out.write("x,z,height_y0,height_y128,highest_natural_y,roof_air_cover1_40,exterior_roof_air_cover1_40,max_air_run_cover1_64,thin_wall_vertical_run,needle_vertical_run\n");
            for(int z=0;z<side;z++)for(int x=0;x<side;x++){int i=z*side+x;out.write((x0+x)+","+(z0+z)+","+surface[i]+","+surface128[i]+","+highest[i]+","+roof[i]+","+exteriorRoof[i]+","+maxAirRun[i]+","+thinRun[i]+","+needleRun[i]+"\n");}
        }
        byte[] material=new byte[size*2];ByteBuffer bytes=ByteBuffer.wrap(material);for(char id:ids)bytes.putChar(id);
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("shape_xyz",List.of(side,HEIGHT,side));metadata.put("origin_xyz",List.of(x0,MIN,z0));metadata.put("palette",palette);
        metadata.put("encoding","int32BE JSON-byte-length, UTF8 JSON, uint8 occupancy, uint16BE palette ids; x fastest,z next,y last. occupancy0air,1naturalrock/soil,2other,3water,4lava");
        byte[] header=G.toJson(metadata).getBytes(StandardCharsets.UTF_8);String raw=site.name()+"-voxels.bin.gz";
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(raw))))){out.writeInt(header.length);out.write(header);out.write(cells);out.write(material);}
        var digest=MessageDigest.getInstance("SHA-256");digest.update(G.toJson(palette).getBytes(StandardCharsets.UTF_8));digest.update(material);
        Map<String,Object> r=new LinkedHashMap<>();r.put("site",site);r.put("origin_xyz",List.of(x0,MIN,z0));r.put("requested_full_chunks_with_halo",requested);r.put("scanned_voxels",size);
        r.put("chunk_request_order",reverse?"reverse_row_major":"row_major");r.put("height_chunk_edge_gradients",edgeGradients(surface,side,x0,z0));
        r.put("natural_blocks",natural);r.put("air_blocks",air);r.put("grounded_natural_components",grounded);
        r.put("detached_components",detached.size());r.put("detached_natural_blocks",detached.stream().mapToLong(Piece::cells).sum());r.put("detached_largest20",detached.subList(0,Math.min(20,detached.size())));
        r.put("exterior_adjacent_detached_components_over8",detached.stream().filter(v->v.exteriorAdjacent()&&v.cells()>=8).count());
        r.put("exterior_adjacent_detached_blocks_over8",detached.stream().filter(v->v.exteriorAdjacent()&&v.cells()>=8).mapToLong(Piece::cells).sum());
        r.put("boundary_unknown_components",unknown.size());r.put("boundary_unknown_largest10",unknown.subList(0,Math.min(10,unknown.size())));
        r.put("roof_air_cover1_40",Arrays.stream(roof).asLongStream().sum());r.put("exterior_roof_air_cover1_40",extNear);r.put("maximum_exterior_air_cover",maxPenetration);
        r.put("thin_wall_blocks",thin);r.put("needle_blocks",needles);r.put("maximum_thin_wall_vertical_run",Arrays.stream(thinRun).max().orElse(0));r.put("maximum_needle_vertical_run",Arrays.stream(needleRun).max().orElse(0));
        r.put("thin_wall_columns_run_over8",Arrays.stream(thinRun).filter(v->v>=8).count());r.put("needle_columns_run_over5",Arrays.stream(needleRun).filter(v->v>=5).count());
        r.put("maximum_near_surface_vertical_air_run",Arrays.stream(maxAirRun).max().orElse(0));r.put("exterior_roof_cut_components",cuts.size());r.put("exterior_roof_cuts_largest20",cuts.subList(0,Math.min(20,cuts.size())));
        r.put("voxel_file",raw);r.put("material_sha256",HexFormat.of().formatHex(digest.digest()));r.put("milliseconds",(System.nanoTime()-started)/1e6);
        System.out.println("TERRAIN_INTEGRITY_WINDOW "+site.name()+" detached="+detached.size()+" exteriorRoof="+extNear+" thin="+thin+" maxNeedle="+Arrays.stream(needleRun).max().orElse(0));return r;
    }
    private static Map<String,Object> edgeGradients(float[] surface,int side,int x0,int z0){
        List<Float> edges=new ArrayList<>(),interior=new ArrayList<>();
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int i=z*side+x;
            if(x>0)(Math.floorMod(x0+x,16)==0?edges:interior).add(Math.abs(surface[i]-surface[i-1]));
            if(z>0)(Math.floorMod(z0+z,16)==0?edges:interior).add(Math.abs(surface[i]-surface[i-side]));
        }
        return Map.of("chunk_edges",gradientStatistics(edges),"interior",gradientStatistics(interior),
            "interpretation","Adjacent-column coherent height deltas: chunk edges are compared with all interior pairs; natural cliffs are allowed, and a large delta alone is not a seam.");
    }
    private static Map<String,Object> gradientStatistics(List<Float> values){
        Collections.sort(values);double sum=0;int above8=0;for(float value:values){sum+=value;if(value>8)above8++;}
        return Map.of("pairs",values.size(),"mean",sum/values.size(),"p90",values.get((values.size()-1)*90/100),
            "p99",values.get((values.size()-1)*99/100),"maximum",values.get(values.size()-1),"above8",above8);
    }
    private static boolean[] exterior(byte[] cells,float[] surface,int side){
        int plane=side*side;boolean[] out=new boolean[cells.length];int[] queue=new int[cells.length];int head=0,tail=0;
        for(int y=0;y<HEIGHT;y++)for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            if(x!=0&&z!=0&&x!=side-1&&z!=side-1&&y!=HEIGHT-1)continue;
            int i=y*plane+z*side+x;if(cells[i]==0&&(y==HEIGHT-1||y+MIN>=surface[z*side+x]-1)){out[i]=true;queue[tail++]=i;}
        }
        while(head<tail){int i=queue[head++],y=i/plane,z=(i%plane)/side,x=i%side;
            for(int axis=0;axis<6;axis++){int ni=neighbour(i,x,y,z,axis,side,plane);if(ni>=0&&!out[ni]&&cells[ni]==0){out[ni]=true;queue[tail++]=ni;}}
        }
        return out;
    }
    private static long solidPieces(byte[] cells,boolean[] exterior,int side,int x0,int z0,List<Piece> detached,List<Piece> unknown){
        int plane=side*side;boolean[] seen=new boolean[cells.length];int[] queue=new int[cells.length];long grounded=0;
        for(int start=0;start<cells.length;start++)if(cells[start]==1&&!seen[start]){
            int head=0,tail=0,minX=side,minZ=side,minY=HEIGHT,maxX=0,maxZ=0,maxY=0;boolean boundary=false,bottom=false,sky=false;seen[start]=true;queue[tail++]=start;
            while(head<tail){int i=queue[head++],y=i/plane,z=(i%plane)/side,x=i%side;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                if(y==0)bottom=true;if(x==0||z==0||x==side-1||z==side-1||y==HEIGHT-1)boundary=true;
                for(int axis=0;axis<6;axis++){int ni=neighbour(i,x,y,z,axis,side,plane);if(ni<0)continue;if(exterior[ni])sky=true;if(cells[ni]==1&&!seen[ni]){seen[ni]=true;queue[tail++]=ni;}}
            }
            if(bottom){grounded++;continue;}
            var piece=new Piece(tail,List.of(x0+minX,MIN+minY,z0+minZ),List.of(x0+maxX,MIN+maxY,z0+maxZ),boundary,sky);if(boundary)unknown.add(piece);else detached.add(piece);
        }
        detached.sort(Comparator.comparingInt(Piece::cells).reversed());unknown.sort(Comparator.comparingInt(Piece::cells).reversed());return grounded;
    }
    private static List<Piece> airCuts(byte[] cells,boolean[] exterior,float[] surface,int side,int x0,int z0){
        int plane=side*side;boolean[] seen=new boolean[cells.length];int[] queue=new int[cells.length];List<Piece> pieces=new ArrayList<>();
        for(int start=0;start<cells.length;start++)if(!seen[start]&&cut(start,cells,exterior,surface,plane)){
            int head=0,tail=0,minX=side,minZ=side,minY=HEIGHT,maxX=0,maxZ=0,maxY=0;boolean boundary=false;seen[start]=true;queue[tail++]=start;
            while(head<tail){int i=queue[head++],y=i/plane,z=(i%plane)/side,x=i%side;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                if(x==0||z==0||x==side-1||z==side-1)boundary=true;
                for(int axis=0;axis<6;axis++){int ni=neighbour(i,x,y,z,axis,side,plane);if(ni>=0&&!seen[ni]&&cut(ni,cells,exterior,surface,plane)){seen[ni]=true;queue[tail++]=ni;}}
            }
            pieces.add(new Piece(tail,List.of(x0+minX,MIN+minY,z0+minZ),List.of(x0+maxX,MIN+maxY,z0+maxZ),boundary,true));
        }
        pieces.sort(Comparator.comparingInt(Piece::cells).reversed());return pieces;
    }
    private static boolean cut(int i,byte[] cells,boolean[] exterior,float[] surface,int plane){int cover=(int)Math.floor(surface[i%plane])-(i/plane+MIN);return cells[i]==0&&exterior[i]&&cover>=1&&cover<=40;}
    private static int neighbour(int i,int x,int y,int z,int axis,int side,int plane){return switch(axis){case 0->x+1<side?i+1:-1;case 1->x>0?i-1:-1;case 2->z+1<side?i+side:-1;case 3->z>0?i-side:-1;case 4->y+1<HEIGHT?i+plane:-1;default->y>0?i-plane:-1;};}
}
