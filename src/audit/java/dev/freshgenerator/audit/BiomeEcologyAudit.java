package dev.freshgenerator.audit;

import com.google.gson.*;
import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.core.ForestHabitat;
import dev.freshgenerator.forest.ForestTelemetry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Audit only: native climate scout and real FULL-block vegetation samples.
 * Tree populations are rooted stem clusters, not exact individual-tree counts.
 * Fixed world-coordinate samples can be replayed on an earlier accepted JAR. */
public final class BiomeEcologyAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final String P="freshgenerator:highlands_v2/";
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private final Map<String,DensitySampler> fields=new LinkedHashMap<>();
    private final Map<Long,Float> heightCache=new HashMap<>();
    private ServerLevel level;private BiomeManager biomes;private int shutdown=-1,cacheChecks;
    private record Site(String name,int x,int z,int side,String expectedBiome) { }
    private record Candidate(int x,int z,String biome,double score) { }
    private static final List<String> GROUPS=List.of("temperate_forest","plains","jungle","savanna","taiga",
        "swamp","mangrove","dark_forest","cherry","pale_garden","alpine","desert","coast","badlands","bamboo_jungle");
    private static final Set<String> ROOT_SUPPORT=Set.of("grass_block","dirt","coarse_dirt","rooted_dirt","podzol",
        "moss_block","mud","packed_mud","mangrove_roots","muddy_mangrove_roots","mycelium","sand","red_sand",
        "clay","snow_block","stone","gravel");
    @Override public void onInitialize(){
        if(!"true".equals(System.getenv("FG_BIOME_ECOLOGY_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{try{run(server);}catch(Throwable e){System.err.println("BIOME_ECOLOGY_FAIL");e.printStackTrace();}finally{shutdown=40;}});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
    private float v(String name,int x,int z){return fields.get(name).sampleValue(context,x,0,z);}
    private float h(int x,int z){long key=((long)x<<32)|(z&0xffffffffL);Float old=heightCache.get(key);if(old!=null)return old;
        float value=v("coherent/height",x,z);if(!Float.isFinite(value))throw new AssertionError("Nonfinite height");
        if(heightCache.size()>131072)heightCache.clear();heightCache.put(key,value);return value;}
    private String biome(int x,int z){return biomes.getBiome(x,(int)Math.floor(h(x,z)),z).unwrapKey().orElseThrow().identifier().toString();}
    private double slope(int x,int z){return Math.hypot((h(x+4,z)-h(x-4,z))/8.0,(h(x,z+4)-h(x,z-4))/8.0);}
    private static String group(String id){return switch(id){
        case "minecraft:forest","minecraft:birch_forest","minecraft:old_growth_birch_forest"->"temperate_forest";
        case "minecraft:plains","minecraft:sunflower_plains","minecraft:meadow"->"plains";
        case "minecraft:jungle","minecraft:sparse_jungle"->"jungle";
        case "minecraft:bamboo_jungle"->"bamboo_jungle";
        case "minecraft:savanna","minecraft:savanna_plateau","minecraft:windswept_savanna"->"savanna";
        case "minecraft:taiga","minecraft:snowy_taiga","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga"->"taiga";
        case "minecraft:swamp"->"swamp";case "minecraft:mangrove_swamp"->"mangrove";
        case "minecraft:dark_forest"->"dark_forest";case "minecraft:cherry_grove"->"cherry";case "minecraft:pale_garden"->"pale_garden";
        case "minecraft:jagged_peaks","minecraft:frozen_peaks","minecraft:stony_peaks","minecraft:snowy_slopes","minecraft:grove"->"alpine";
        case "minecraft:desert"->"desert";
        case "minecraft:beach","minecraft:snowy_beach","minecraft:stony_shore"->"coast";
        case "minecraft:badlands","minecraft:wooded_badlands","minecraft:eroded_badlands"->"badlands";
        default->"other";};}
    private void run(MinecraftServer server)throws Exception{
        level=server.overworld();var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        if(!generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString().equals("freshgenerator:highlands_v2"))throw new AssertionError("Wrong generator");
        var state=level.getChunkSource().randomState();var registry=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        for(String name:List.of("coherent/height","temperature","vegetation","landscape/mountain","landscape/giant"))
            fields.put(name,state.getSampler(registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+name))).value()));
        biomes=level.getBiomeManager().withDifferentSource(generator.getBiomeSource().createCachingResolver(state));
        Path sitesPath=Path.of(Objects.requireNonNull(System.getenv("FG_BIOME_ECOLOGY_SITES")));
        List<Site> sites;Map<String,Object> scout=new LinkedHashMap<>();
        if(Files.exists(sitesPath)&&"true".equals(System.getenv("FG_ECOLOGY_SKIP_SCOUT"))){
            sites=Arrays.asList(G.fromJson(Files.readString(sitesPath),Site[].class));
            scout.put("skipped",true);scout.put("reason","Explicit immutable site manifest supplied; this FULL run does not claim a regional search.");
        }else{
            List<Site> currentSites=select(scout);
            Files.writeString(Path.of("current-selected-sites.json"),G.toJson(currentSites)+"\n");
            if(Files.exists(sitesPath))sites=Arrays.asList(G.fromJson(Files.readString(sitesPath),Site[].class));
            else{sites=currentSites;Files.writeString(sitesPath,G.toJson(sites)+"\n");}
        }
        if(sites.isEmpty()||sites.size()>18)throw new AssertionError("Unbounded site list");
        for(int z=-17;z<=17;z+=17)for(int x=-17;x<=17;x+=17)verify(x,z);
        List<Object> windows=new ArrayList<>();
        if(!"true".equals(System.getenv("FG_ECOLOGY_SCOUT_ONLY")))for(Site site:sites)windows.add(full(site));
        var report=new LinkedHashMap<String,Object>();report.put("seed",level.getSeed());report.put("sites",sites);report.put("scout",scout);
        report.put("audit_native_counts_control","true".equals(System.getenv("FG_ECOTONE_NATIVE_COUNTS_CONTROL")));
        report.put("audit_forced_palette",System.getenv("FG_ECOTONE_FORCE_PALETTE"));
        report.put("windows",windows);report.put("cache_bit_comparisons",cacheChecks);report.put("cache_mismatches",0);
        report.put("scope","Fixed native FULL terrain/vegetation windows, no structures or user saves. Counts are rooted stem clusters and canopy-covered columns, not fitted real-world stems per hectare or exact native tree individuals. Site selection is a bounded targeted biome scout, not a random world sample; absent groups stay explicit. No client visual or gameplay assessment.");
        Files.writeString(Path.of("biome-ecology.json"),G.toJson(report)+"\n");System.out.println("BIOME_ECOLOGY_PASS windows="+windows.size()+" cacheChecks="+cacheChecks);
    }
    private void verify(int x,int z){for(var field:fields.entrySet()){
        float a=field.getValue().sampleValue(SamplerContext.EMPTY_UNCACHED,x,0,z),b=field.getValue().sampleValue(context,x,0,z);cacheChecks++;
        if(!Float.isFinite(a)||Float.floatToRawIntBits(a)!=Float.floatToRawIntBits(b))throw new AssertionError("Cached climate mismatch "+field.getKey()+" "+x+","+z);
    }}
    private List<Site> select(Map<String,Object> report)throws Exception{
        Map<String,List<Candidate>> candidates=new TreeMap<>();Map<String,Integer> counts=new TreeMap<>();int points=0;
        try(var csv=Files.newBufferedWriter(Path.of("ecology-regional.csv"))){
            csv.write("x,z,height,biome,group,temperature,moisture,mountain,giant\n");
            // Offset coarse cells away from chunk/biome-grid boundaries; all coordinates are absolute.
            for(int z=-32736;z<=32800;z+=512)for(int x=-32736;x<=32800;x+=512){
                float height=h(x,z);String biome=biome(x,z),group=group(biome);points++;counts.merge(biome,1,Integer::sum);
                csv.write(x+","+z+","+height+","+biome+","+group+","+v("temperature",x,z)+","+v("vegetation",x,z)+","+v("landscape/mountain",x,z)+","+v("landscape/giant",x,z)+"\n");
                if(!GROUPS.contains(group)||height<61)continue;
                double score=Math.hypot(x,z)/50000.0+slope(x,z)*.6;
                if(group.equals("alpine"))score-=Math.min(1,height/300.0);
                var list=candidates.computeIfAbsent(group,k->new ArrayList<>());list.add(new Candidate(x,z,biome,score));
                if(points%1021==0)verify(x,z);
            }
        }
        List<Site> sites=new ArrayList<>();List<String> absent=new ArrayList<>();
        for(String wanted:GROUPS){
            var list=candidates.getOrDefault(wanted,List.of()).stream().sorted(Comparator.comparingDouble(Candidate::score)).limit(10).toList();
            Candidate best=null;double bestScore=Double.MAX_VALUE;
            for(var c:list)for(int dz=-64;dz<=64;dz+=32)for(int dx=-64;dx<=64;dx+=32){
                int x=c.x()+dx,z=c.z()+dz;String id=biome(x,z);if(!group(id).equals(wanted)||h(x,z)<61)continue;
                int mismatch=0;for(int oz:new int[]{-20,0,20})for(int ox:new int[]{-20,0,20})if(!group(biome(x+ox,z+oz)).equals(wanted))mismatch++;
                double score=mismatch*10+slope(x,z)+Math.hypot(x,z)/50000.0;
                if(score<bestScore){bestScore=score;best=new Candidate(x,z,id,score);}
            }
            if(best==null){absent.add(wanted);continue;}
            int side=Set.of("plains","savanna","desert","badlands").contains(wanted)?64:48;
            sites.add(new Site(wanted,best.x(),best.z(),side,best.biome()));
        }
        report.put("points",points);report.put("coarse_step",512);report.put("biome_counts",counts);report.put("absent_groups",absent);
        report.put("bounded_refinement","At most ten candidates per group, 25 centres each, nine local group labels per centre; no unbounded search for rare biomes.");
        return sites;
    }
    private Map<String,Object> full(Site site)throws Exception{
        var telemetryBefore=ForestTelemetry.snapshot();
        long bypassesBefore=NativeEcotoneControls.bypasses(),forcedBefore=NativeEcotoneControls.forcedCalls();
        long paleOutBefore=NativeEcotoneControls.paleOut(),paleInBefore=NativeEcotoneControls.paleIn();
        int side=site.side();if(side<32||side>96||side%16!=0)throw new AssertionError("Unsafe window");
        int x0=Math.floorDiv(site.x()-side/2,16)*16,z0=Math.floorDiv(site.z()-side/2,16)*16,plane=side*side,size=plane*384;
        long started=System.nanoTime();
        for(int cx=x0/16-1;cx<=(x0+side-1)/16+1;cx++)for(int cz=z0/16-1;cz<=(z0+side-1)/16+1;cz++)level.getChunk(cx,cz);
        List<String> palette=new ArrayList<>();List<Map<String,Boolean>> paletteTraits=new ArrayList<>();
        Map<String,Integer> paletteIndex=new HashMap<>();int[] ids=new int[size];
        Map<String,Integer> biomeCounts=new TreeMap<>(),groundCounts=new TreeMap<>(),logSpecies=new TreeMap<>();
        Map<Integer,Integer> canopyHeights=new TreeMap<>(),stemHeights=new TreeMap<>();Map<Integer,Integer> terrainHeights=new TreeMap<>();
        boolean[] roots=new boolean[plane];int leafColumns=0,logColumns=0,bambooColumns=0,leafBlocks=0,logBlocks=0,shrubs=0,waterColumns=0;
        double minH=999,maxH=-999,sumH=0,sumSlope=0,sumConcavity=0;var at=new BlockPos.MutableBlockPos();
        try(var csv=Files.newBufferedWriter(Path.of(site.name()+"-columns.csv"))){
            csv.write("x,z,height,slope,convexity16,biome,ground,canopy_height,rooted_stem_height,logs,leaves,bamboo,temperature,moisture,forest_edge,shore,forest_retention,forest_stand,forest_opening,forest_stress\n");
            for(int z=0;z<side;z++)for(int x=0;x<side;x++){
                int xx=x0+x,zz=z0+z,col=z*side+x;float height=h(xx,zz);int base=(int)Math.ceil(height)-1;
                String biome=biome(xx,zz);biomeCounts.merge(biome,1,Integer::sum);double slope=slope(xx,zz),convexity=height-(h(xx-16,zz)+h(xx+16,zz)+h(xx,zz-16)+h(xx,zz+16))/4.;
                minH=Math.min(minH,height);maxH=Math.max(maxH,height);sumH+=height;sumSlope+=slope;sumConcavity+=convexity;terrainHeights.merge(base,1,Integer::sum);
                var forestSite=forestSite(xx,zz,height,slope,convexity);
                var family=ForestHabitat.familyForBiome(biome);var habitat=ForestHabitat.forBiome(biome);
                var ecology=family==null?null:ForestEcology.sample(level.getSeed()^0x6F7265737445434FL,xx,zz,family,forestSite,habitat);
                var chunk=level.getChunk(Math.floorDiv(xx,16),Math.floorDiv(zz,16));boolean logs=false,leaves=false,bamboo=false;int canopy=0,bestStem=0;
                for(int y=-64;y<=319;y++){
                    at.set(xx,y,zz);var block=chunk.getBlockState(at);String name=block.toString();Integer id=paletteIndex.get(name);
                    if(id==null){
                        id=palette.size();palette.add(name);paletteIndex.put(name,id);
                        String blockId=BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
                        paletteTraits.add(Map.of("logs",block.is(BlockTags.LOGS),"leaves",block.is(BlockTags.LEAVES),
                            "fluid",!block.getFluidState().isEmpty(),"air",block.isAir(),
                            "root_support",ROOT_SUPPORT.contains(blockId.substring(blockId.indexOf(':')+1))));
                    }
                    if(id>65535)throw new AssertionError("State palette overflow");ids[(y+64)*plane+col]=id;
                    if(y<base-12||y>base+64)continue;
                    if(block.is(BlockTags.LOGS)){logs=true;logBlocks++;logSpecies.merge(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString(),1,Integer::sum);}
                    if(block.is(BlockTags.LEAVES)){leaves=true;leafBlocks++;canopy=Math.max(canopy,y-base);}
                    if(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString().equals("minecraft:bamboo"))bamboo=true;
                    if(y<base-8||y>base+12||!block.is(BlockTags.LOGS))continue;
                    var below=chunk.getBlockState(at.below());String belowId=BuiltInRegistries.BLOCK.getKey(below.getBlock()).toString();
                    if(below.is(BlockTags.LOGS)||!ROOT_SUPPORT.contains(belowId.substring(belowId.indexOf(':')+1)))continue;
                    int stem=0;while(stem<64&&y+stem<320&&chunk.getBlockState(at.offset(0,stem,0)).is(BlockTags.LOGS))stem++;
                    bestStem=Math.max(bestStem,stem);
                }
                at.set(xx,Math.clamp(base,-64,319),zz);String ground=BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(at).getBlock()).toString();groundCounts.merge(ground,1,Integer::sum);
                if(!chunk.getBlockState(at).getFluidState().isEmpty())waterColumns++;
                if(logs)logColumns++;if(leaves){leafColumns++;canopyHeights.merge(canopy,1,Integer::sum);}if(bamboo)bambooColumns++;
                if(bestStem>=2){roots[col]=true;stemHeights.merge(bestStem,1,Integer::sum);}else if(bestStem==1)shrubs++;
                csv.write(xx+","+zz+","+height+","+slope+","+convexity+","+biome+","+ground+","+canopy+","+bestStem+","+logs+","+leaves+","+bamboo+","+
                    forestSite.temperature()+","+forestSite.moisture()+","+forestSite.edge()+","+forestSite.shore()+","+
                    (ecology==null?",,,":ecology.retention()+","+ecology.stand()+","+ecology.opening()+","+ecology.stress())+"\n");
            }
        }
        int clusters=0,clipped=0;boolean[] seen=new boolean[plane];ArrayDeque<Integer> q=new ArrayDeque<>();
        for(int start=0;start<plane;start++)if(roots[start]&&!seen[start]){clusters++;boolean edge=false;seen[start]=true;q.add(start);
            while(!q.isEmpty()){int i=q.removeFirst(),x=i%side,z=i/side;edge|=x==0||z==0||x==side-1||z==side-1;
                for(int j:new int[]{x>0?i-1:-1,x+1<side?i+1:-1,z>0?i-side:-1,z+1<side?i+side:-1})if(j>=0&&roots[j]&&!seen[j]){seen[j]=true;q.addLast(j);}}
            if(edge)clipped++;
        }
        var meta=new LinkedHashMap<String,Object>();meta.put("shape_xyz",List.of(side,384,side));meta.put("origin_xyz",List.of(x0,-64,z0));meta.put("palette",palette);meta.put("palette_traits",paletteTraits);
        byte[] header=G.toJson(meta).getBytes(StandardCharsets.UTF_8);var digest=MessageDigest.getInstance("SHA-256");digest.update(header);
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(site.name()+"-states.bin.gz"))))){out.writeInt(header.length);out.write(header);for(int id:ids){out.writeShort(id);digest.update((byte)(id>>>8));digest.update((byte)id);}}
        var r=new LinkedHashMap<String,Object>();r.put("site",site);r.put("columns",plane);r.put("height_min",minH);r.put("height_max",maxH);r.put("height_mean",sumH/plane);
        r.put("mean_slope",sumSlope/plane);r.put("mean_convexity16",sumConcavity/plane);r.put("biomes",biomeCounts);r.put("ground",groundCounts);
        boolean fixedRegression="*".equals(site.expectedBiome());
        r.put("fixed_coordinate_regression",fixedRegression);
        r.put("expected_biome_columns",fixedRegression?null:biomeCounts.getOrDefault(site.expectedBiome(),0));
        r.put("expected_biome_fraction",fixedRegression?null:biomeCounts.getOrDefault(site.expectedBiome(),0)/(double)plane);
        if(!fixedRegression&&!biomeCounts.containsKey(site.expectedBiome()))throw new AssertionError("Requested native biome absent from FULL window: "+site);
        r.put("leaf_columns",leafColumns);r.put("canopy_fraction",leafColumns/(double)plane);r.put("log_columns",logColumns);r.put("bamboo_columns",bambooColumns);r.put("water_at_envelope_columns",waterColumns);
        r.put("rooted_stem_clusters",clusters);r.put("boundary_stem_clusters",clipped);r.put("rooted_stem_clusters_per_1024_columns",clusters*1024.0/plane);r.put("single_log_shrub_columns",shrubs);
        r.put("leaf_blocks",leafBlocks);r.put("log_blocks",logBlocks);r.put("log_species",logSpecies);r.put("canopy_height_histogram",canopyHeights);r.put("rooted_stem_length_histogram",stemHeights);r.put("terrain_height_histogram",terrainHeights);
        r.put("raw_state_ids_sha256",HexFormat.of().formatHex(digest.digest()));r.put("raw_file",site.name()+"-states.bin.gz");r.put("milliseconds",(System.nanoTime()-started)/1e6);
        var telemetry=new TreeMap<String,Long>();for(var entry:ForestTelemetry.snapshot().entrySet())telemetry.put(entry.getKey(),entry.getValue()-telemetryBefore.getOrDefault(entry.getKey(),0L));
        r.put("forest_telemetry_delta",telemetry);
        r.put("audit_count_bypasses",NativeEcotoneControls.bypasses()-bypassesBefore);
        r.put("audit_forced_palette_calls",NativeEcotoneControls.forcedCalls()-forcedBefore);
        r.put("audit_pale_palette_out_rejections",NativeEcotoneControls.paleOut()-paleOutBefore);
        r.put("audit_pale_palette_in_rejections",NativeEcotoneControls.paleIn()-paleInBefore);
        r.put("forest_telemetry_scope","Native generation provoked by this FULL request including its generation halo; not an exact count confined to the saved crop.");
        System.out.println("BIOME_ECOLOGY_WINDOW "+site.name()+" stems="+clusters+" canopy="+leafColumns/(double)plane);return r;
    }
    /** Same immutable local inputs as ForestEnvironment; no generated vegetation
     * participates, so this explanatory profile cannot feed back into placement. */
    private ForestEcology.Site forestSite(int x,int z,float height,double slope,double convexity){
        double edge=0,shore=0;
        for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            int nx=x+16*direction[0],nz=z+16*direction[1];float nearHeight=h(nx,nz);String near=biomeAtCeiling(nx,nz,nearHeight);
            if(nearHeight<level.getSeaLevel()+1&&(near.contains("ocean")||near.equals("minecraft:river")||near.equals("minecraft:frozen_river")))shore+=.25;
            if(!wooded(near))edge+=.15;
            int fx=x+32*direction[0],fz=z+32*direction[1];if(!wooded(biomeAtCeiling(fx,fz,h(fx,fz))))edge+=.10;
        }
        return new ForestEcology.Site(height,level.getSeaLevel(),slope,convexity,v("temperature",x,z),v("vegetation",x,z),shore,edge);
    }
    private String biomeAtCeiling(int x,int z,float height){return biomes.getBiome(x,(int)Math.ceil(height),z).unwrapKey().orElseThrow().identifier().toString();}
    private static boolean wooded(String id){
        var habitat=ForestHabitat.forBiome(id);
        return habitat!=null&&(!habitat.open()||habitat==ForestHabitat.OPEN_GROVE)&&!id.equals("minecraft:windswept_hills")&&!id.equals("minecraft:windswept_gravelly_hills");
    }
}
