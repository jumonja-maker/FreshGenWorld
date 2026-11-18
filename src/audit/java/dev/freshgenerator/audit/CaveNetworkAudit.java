package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Test-only observer. Measures actual FULL voxels, not just a density graph.
 * Every component is finite-window evidence; boundary contacts remain explicit.
 * Two-block clearance is not a claim of walking/navigation without climbing. */
public final class CaveNetworkAudit implements ModInitializer {
    private static final String P="freshgenerator:highlands_v2/";
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final int MIN_Y=-54;
    private record Site(String name,int x,int z,int side) { }
    private record Component(int id,int voxels,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,
                             int maximumCover,int mouthCells,int nonmountainMouthCells,
                             boolean touchesBoundary,int chunkCrossings,int mouthToDeepPathSteps,
                             List<List<Integer>> mouthToDeepPathSamples) { }
    private ServerLevel level;
    private NoiseBasedChunkGenerator generator;
    private DensitySampler height,mountain,finalBody,entranceGate,depthLimit;
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private int shutdown=-1;
    @Override public void onInitialize() {
        if(!"true".equals(System.getenv("FG_CAVE_NETWORK_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try{run(server);}catch(Throwable e){System.err.println("CAVE_NETWORK_FAIL");e.printStackTrace();}
            finally{shutdown=40;}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
    private DensitySampler sampler(MinecraftServer server,String local) {
        return level.getChunkSource().randomState().getSampler(server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
            .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+local))).value());
    }
    private float h(int x,int z){return height.sampleValue(context,x,0,z);}
    private float m(int x,int z){return mountain.sampleValue(context,x,0,z);}
    private void run(MinecraftServer server)throws Exception {
        level=server.overworld();generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        if(!"freshgenerator:highlands_v2".equals(generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString()))
            throw new AssertionError("Wrong generator");
        height=sampler(server,"coherent/height");mountain=sampler(server,"landscape/mountain");
        if(!"true".equals(System.getenv("FG_CAVE_NETWORK_BASELINE"))){finalBody=sampler(server,"cave_network/final_body");depthLimit=sampler(server,"cave_network/depth_limit");}
        String coordinates=Objects.requireNonNull(System.getenv("FG_CAVE_NETWORK_SITES"),"Explicit cave sites JSON required");
        Path coordinatesPath=Path.of(coordinates);List<Site> sites;
        if(Files.exists(coordinatesPath))sites=Arrays.asList(G.fromJson(Files.readString(coordinatesPath),Site[].class));
        else{
            finalBody=sampler(server,"cave_network/final_body");entranceGate=sampler(server,"cave_network/entrance_gate");
            sites=select();Files.writeString(coordinatesPath,G.toJson(sites)+"\n");
        }
        if(sites.isEmpty()||sites.size()>4)throw new AssertionError("One to four bounded windows required");
        List<Map<String,Object>> windows=new ArrayList<>();
        for(Site site:sites)windows.add(scan(site));
        Map<String,Object> report=new LinkedHashMap<>();report.put("seed",level.getSeed());report.put("sites",sites);report.put("windows",windows);
        report.put("scope","Actual FULL blocks, six-neighbour dry air and two-block-clearance components; finite windows with explicit boundary truncation. Fluid volumes are separate. No client, player navigation, or global connectedness claim.");
        Files.writeString(Path.of("cave-network.json"),G.toJson(report)+"\n");
        if(!"true".equals(System.getenv("FG_CAVE_NETWORK_BASELINE"))){
            for(Map<String,Object> window:windows){
                if(((Number)window.get("nonmountain_density_cave_roof_air_voxels_cover_2_to_34")).longValue()!=0)
                    throw new AssertionError("Cave density opens nonmountain near-surface roof: "+window.get("site"));
                for(String key:List.of("air","two_block_clearance")){
                    Map<?,?> metric=(Map<?,?>)window.get(key);
                    if(((Number)metric.get("nonmountain_mouth_cells")).longValue()!=0)throw new AssertionError("Nonmountain lateral mouth: "+window.get("site"));
                }
                Site site=(Site)window.get("site");Map<?,?> clearance=(Map<?,?>)window.get("two_block_clearance");
                if(site.name().equals("mountain_entrance")&&((Number)clearance.get("large_mouth_components_reaching_cover_80_across_chunks")).longValue()<1)
                    throw new AssertionError("No measured two-block-clearance entrance-to-depth path: "+site);
                if(site.name().equals("lowland_control")&&((Number)clearance.get("components_with_mouth")).longValue()!=0)
                    throw new AssertionError("Lowland mouth: "+site);
            }
        }
        System.out.println("CAVE_NETWORK_PASS windows="+windows.size());
    }
    private List<Site> select()throws Exception {
        record Candidate(int x,int z,double score) { }
        List<Candidate> districts=new ArrayList<>();Candidate low=null;
        for(int z=-8192;z<=8192;z+=128)for(int x=-8192;x<=8192;x+=128){
            float elevation=h(x,z),ridge=m(x,z);double distance=Math.hypot(x,z);
            if(elevation>=75&&elevation<105&&ridge<.20f&&(low==null||distance<low.score()))low=new Candidate(x,z,distance);
            float gate=entranceGate.sampleValue(context,x,0,z);
            if(elevation>=120&&ridge>=.35f&&gate>.02)districts.add(new Candidate(x,z,gate*10000-distance*.02));
        }
        districts.sort(Comparator.comparingDouble(Candidate::score).reversed());
        List<Map<String,Object>> scouts=new ArrayList<>();Candidate entrance=null;int attempts=0;
        outer:for(Candidate district:districts){
            if(attempts++>=32)break;
            for(int dz=-64;dz<=64;dz+=8)for(int dx=-64;dx<=64;dx+=8){
                int x=district.x()+dx,z=district.z()+dz;float elevation=h(x,z),ridge=m(x,z);
                double slope=Math.hypot((h(x+4,z)-h(x-4,z))/8.0,(h(x,z+4)-h(x,z-4))/8.0);
                float density=finalBody.sampleValue(context,x,(int)Math.floor(elevation)-3,z);
                if(density<0&&(elevation<120||ridge<.35f))throw new AssertionError("Density opens outside mountain at "+x+","+z);
                if(density<-.01&&elevation>=120&&ridge>=.35f&&slope>.20){
                    scouts.add(Map.of("x",x,"z",z,"height",elevation,"mountain",ridge,"slope",slope,"density_at_surface_minus_3",density));
                    entrance=new Candidate(x,z,0);break outer;
                }
            }
        }
        Files.writeString(Path.of("cave-scout.json"),G.toJson(Map.of("mountain_districts",districts.size(),"searched_districts",attempts,"entrance_candidates",scouts))+"\n");
        if(low==null||entrance==null)throw new AssertionError("No finite lowland/entrance sample found: "+districts.size()+" districts");
        return List.of(new Site("mountain_entrance",entrance.x(),entrance.z(),96),new Site("lowland_control",low.x(),low.z(),96));
    }
    private Map<String,Object> scan(Site site)throws Exception {
        int side=site.side()==0?96:site.side();
        if(side<32||side>160||side%16!=0||!site.name().matches("[a-zA-Z0-9_-]+"))throw new AssertionError("Unsafe window");
        int x0=Math.floorDiv(site.x()-side/2,16)*16,z0=Math.floorDiv(site.z()-side/2,16)*16;
        // Cache an extra column halo so lateral mouths compare to actual neighbouring terrain.
        int stride=side+2;int[] surface=new int[stride*stride];float[] mountains=new float[stride*stride];
        int top=64;
        for(int z=-1;z<=side;z++)for(int x=-1;x<=side;x++){
            int at=(z+1)*stride+x+1;surface[at]=(int)Math.floor(h(x0+x,z0+z));mountains[at]=m(x0+x,z0+z);
            if(x>=0&&z>=0&&x<side&&z<side)top=Math.max(top,surface[at]+8);
        }
        top=Math.min(319,top);int vertical=top-MIN_Y+1,plane=side*side,size=plane*vertical;
        long started=System.nanoTime();int chunks=0;
        for(int cx=x0/16-1;cx<=(x0+side-1)/16+1;cx++)for(int cz=z0/16-1;cz<=(z0+side-1)/16+1;cz++){
            level.getChunk(cx,cz);chunks++;
        }
        byte[] cells=new byte[size];int dry=0,water=0,lava=0,nearSurface=0,nonmountainNearSurface=0,nonmountainRoofAir=0,nonmountainDensityRoofAir=0;
        Map<Integer,Integer> verticalAirRuns=new TreeMap<>();
        List<Map<String,Object>> roofViolations=new ArrayList<>();
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            var chunk=level.getChunk(Math.floorDiv(x0+x,16),Math.floorDiv(z0+z,16));int col=(z+1)*stride+x+1;
            int airRun=0;
            for(int y=MIN_Y;y<=top;y++){
                pos.set(x0+x,y,z0+z);var block=chunk.getBlockState(pos);int i=(y-MIN_Y)*plane+z*side+x;
                byte kind=1;
                if(block.isAir())kind=0;
                else if(!block.getFluidState().isEmpty())kind=(byte)(block.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)?3:2);
                cells[i]=kind;
                if(y<=surface[col]-2){if(kind==0)dry++;if(kind==2)water++;if(kind==3)lava++;}
                if(kind==0&&y<=surface[col]-2)airRun++;
                else if(airRun>0){verticalAirRuns.merge(airRun,1,Integer::sum);airRun=0;}
                if(kind==0&&surface[col]-y>=2&&surface[col]-y<35&&(surface[col]<120||mountains[col]<.35f)){
                    nonmountainRoofAir++;
                    // Native lava lakes/geodes also leave sealed air pockets after
                    // density generation. Keep these in the raw counts, and attribute
                    // a cave-policy failure only where the actual cave field opens.
                    float roofDensity=finalBody==null?Float.NaN:finalBody.sampleValue(SamplerContext.EMPTY_UNCACHED,x0+x,y,z0+z);
                    if(roofDensity<0)nonmountainDensityRoofAir++;
                    if(roofViolations.size()<32){
                        Map<String,Object> bad=new LinkedHashMap<>();bad.put("x",x0+x);bad.put("y",y);bad.put("z",z0+z);bad.put("cached_envelope_y",surface[col]);bad.put("mountain",mountains[col]);bad.put("block",block.toString());
                        bad.put("uncached_height",height.sampleValue(SamplerContext.EMPTY_UNCACHED,x0+x,0,z0+z));
                        bad.put("fresh_context_height",height.sampleValue(SamplerContext.builder().enableCaches().build(),x0+x,0,z0+z));
                        if(finalBody!=null){bad.put("uncached_final_density",finalBody.sampleValue(SamplerContext.EMPTY_UNCACHED,x0+x,y,z0+z));bad.put("uncached_depth_limit",depthLimit.sampleValue(SamplerContext.EMPTY_UNCACHED,x0+x,y,z0+z));}
                        roofViolations.add(bad);
                    }
                }
            }
            if(airRun>0)verticalAirRuns.merge(airRun,1,Integer::sum);
            int yy=surface[col]-3;
            if(yy>=MIN_Y&&yy<=top&&cells[(yy-MIN_Y)*plane+z*side+x]==0){nearSurface++;if(surface[col]<120||mountains[col]<.35f)nonmountainNearSurface++;}
        }
        digest.update(cells);
        String binary=site.name()+"-voxels.bin.gz";
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(binary))))){
            out.writeInt(side);out.writeInt(vertical);out.writeInt(side);out.writeInt(x0);out.writeInt(MIN_Y);out.writeInt(z0);out.write(cells);
        }
        List<Component> air=components(cells,surface,mountains,side,vertical,x0,z0,false);
        List<Component> clearance=components(cells,surface,mountains,side,vertical,x0,z0,true);
        Map<String,Object> r=new LinkedHashMap<>();r.put("site",site);r.put("origin",List.of(x0,MIN_Y,z0));r.put("side",side);r.put("top_y",top);
        r.put("requested_full_chunks_with_halo",chunks);r.put("scanned_voxels",size);r.put("underground_dry_air",dry);r.put("underground_water",water);r.put("underground_lava",lava);
        r.put("air_columns_3_below_envelope",nearSurface);r.put("nonmountain_air_columns_3_below_envelope",nonmountainNearSurface);
        r.put("nonmountain_roof_air_voxels_cover_2_to_34",nonmountainRoofAir);
        r.put("nonmountain_density_cave_roof_air_voxels_cover_2_to_34",nonmountainDensityRoofAir);
        r.put("roof_void_attribution","All FULL air retained; negative final_body marks cave-generated roof breaches. Positive density can be hollowed later by native features; exact feature attribution requires paired baseline blocks and neighbourhood inspection.");
        r.put("roof_violation_examples",roofViolations);
        r.put("vertical_air_run_histogram",verticalAirRuns);
        r.put("air",summary(air,dry));r.put("two_block_clearance",summary(clearance,-1));r.put("voxel_file",binary);r.put("voxel_sha256",HexFormat.of().formatHex(digest.digest()));
        r.put("voxel_format","gzip; 6 big-endian int32 sideX,height,sideZ,originX,minY,originZ; then bytes x fastest,z next,y last; 0 air,1 solid/other,2 nonlava fluid,3 lava");
        r.put("milliseconds",(System.nanoTime()-started)/1e6);
        System.out.println("CAVE_NETWORK_WINDOW "+site.name()+" air="+dry+" components="+air.size()+" max="+(air.isEmpty()?0:air.getFirst().voxels()));
        return r;
    }
    private static Map<String,Object> summary(List<Component> all,int total) {
        long voxels=all.stream().mapToLong(Component::voxels).sum();
        Map<String,Object> r=new LinkedHashMap<>();r.put("components",all.size());r.put("voxels",voxels);
        r.put("largest_component_fraction",voxels==0?0:all.getFirst().voxels()/(double)voxels);
        r.put("components_over_1000",all.stream().filter(v->v.voxels()>=1000).count());
        r.put("components_with_mouth",all.stream().filter(v->v.mouthCells()>0).count());
        r.put("mouth_components_reaching_cover_40",all.stream().filter(v->v.mouthCells()>0&&v.maximumCover()>=40).count());
        r.put("mouth_components_reaching_cover_80",all.stream().filter(v->v.mouthCells()>0&&v.maximumCover()>=80).count());
        r.put("large_mouth_components_reaching_cover_80_across_chunks",all.stream().filter(v->v.mouthCells()>0&&v.maximumCover()>=80&&v.chunkCrossings()>0&&v.voxels()>=10000).count());
        r.put("nonmountain_mouth_cells",all.stream().mapToLong(Component::nonmountainMouthCells).sum());
        r.put("largest_20",all.subList(0,Math.min(20,all.size())));
        return r;
    }
    private List<Component> components(byte[] cells,int[] surface,float[] mountain,int side,int vertical,int x0,int z0,boolean two) {
        int plane=side*side,stride=side+2,size=cells.length;boolean[] visited=new boolean[size];int[] queue=new int[size],parent=new int[size];List<Component> result=new ArrayList<>();
        for(int start=0;start<size;start++){
            if(visited[start]||!eligible(start,cells,surface,side,vertical,two))continue;
            int head=0,tail=0,minX=side,minY=vertical,minZ=side,maxX=0,maxY=0,maxZ=0,cover=0,mouth=0,wrong=0,crossings=0,firstMouth=-1,deepest=start;boolean boundary=false;
            parent[start]=-1;
            visited[start]=true;queue[tail++]=start;
            while(head<tail){
                int i=queue[head++],y=i/plane,z=(i%plane)/side,x=i%side,yy=MIN_Y+y,col=(z+1)*stride+x+1;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
                if(surface[col]-yy>cover){cover=surface[col]-yy;deepest=i;}
                if(x==0||z==0||y==0||x==side-1||z==side-1||y==vertical-1)boundary=true;
                boolean opens=false;
                for(int axis=0;axis<6;axis++){
                    int nx=x+(axis==0?1:axis==1?-1:0),nz=z+(axis==2?1:axis==3?-1:0),ny=y+(axis==4?1:axis==5?-1:0);
                    if(nx<0||nz<0||ny<0||nx>=side||nz>=side||ny>=vertical)continue;
                    int ni=ny*plane+nz*side+nx,ncol=(nz+1)*stride+nx+1;
                    if(cells[ni]==0&&MIN_Y+ny>surface[ncol]-2&&(!two||ny+1<vertical&&cells[ni+plane]==0))opens=true;
                    if(!visited[ni]&&eligible(ni,cells,surface,side,vertical,two)){
                        visited[ni]=true;parent[ni]=i+1;queue[tail++]=ni;
                        if(Math.floorDiv(x0+x,16)!=Math.floorDiv(x0+nx,16)||Math.floorDiv(z0+z,16)!=Math.floorDiv(z0+nz,16))crossings++;
                    }
                }
                if(opens){mouth++;if(firstMouth<0)firstMouth=i;if(surface[col]<120||mountain[col]<.35f)wrong++;}
            }
            List<Integer> path=firstMouth>=0?path(firstMouth,deepest,parent):List.of();List<List<Integer>> samples=new ArrayList<>();
            int step=Math.max(1,path.size()/128);
            for(int i=0;i<path.size();i++)if(i%step==0||i==path.size()-1){int at=path.get(i);samples.add(List.of(x0+at%side,MIN_Y+at/plane,z0+(at%plane)/side));}
            result.add(new Component(result.size(),tail,x0+minX,MIN_Y+minY,z0+minZ,x0+maxX,MIN_Y+maxY,z0+maxZ,cover,mouth,wrong,boundary,crossings,Math.max(0,path.size()-1),samples));
        }
        result.sort(Comparator.comparingInt(Component::voxels).reversed());return result;
    }
    private static List<Integer> path(int start,int end,int[] parent){
        List<Integer> a=new ArrayList<>(),b=new ArrayList<>();
        for(int at=start;at>=0;at=parent[at]-1)a.add(at);
        for(int at=end;at>=0;at=parent[at]-1)b.add(at);
        int ia=a.size()-1,ib=b.size()-1;while(ia>=0&&ib>=0&&a.get(ia).equals(b.get(ib))){ia--;ib--;}
        List<Integer> path=new ArrayList<>(a.subList(0,ia+2));for(int i=ib;i>=0;i--)path.add(b.get(i));return path;
    }
    private static boolean eligible(int i,byte[] cells,int[] surface,int side,int vertical,boolean two) {
        if(cells[i]!=0)return false;
        int plane=side*side,y=i/plane,z=(i%plane)/side,x=i%side;
        if(MIN_Y+y>surface[(z+1)*(side+2)+x+1]-2)return false;
        return !two||y+1<vertical&&cells[i+plane]==0;
    }
}
