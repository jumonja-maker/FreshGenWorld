package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.freshgenerator.ice.GlacialSurface;
import dev.freshgenerator.core.GlacialIce;
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
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Separate headless observer. Scouting uses seeded native biome/density samplers;
 * evidence and figures use finite windows of actual saved FULL block states. */
public final class GlacialWorldAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private record Site(String name,int x,int z,int side) { }
    private record Candidate(int x,int z,double score) { }
    private record Component(int voxels,int footprint,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,
                             int maximumExposedHeight,int chunkCrossingEdges,boolean touchesBoundary) { }
    private ServerLevel level;
    private NoiseBasedChunkGenerator generator;
    private GlacialSurface.Environment environment;
    private BiomeManager biomes;
    private Climate.Sampler climate;
    private int sea,shutdown=-1;

    @Override public void onInitialize() {
        if(!"true".equals(System.getenv("FG_GLACIAL_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try{run(server);}catch(Throwable e){System.err.println("GLACIAL_FAIL");e.printStackTrace();}
            finally{shutdown=40;}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
    private String biome(int x,int z) {
        return biomes.getBiome(x,(int)Math.floor(Math.max(sea,environment.height(x,z))),z)
            .unwrapKey().orElseThrow().identifier().toString();
    }
    private void run(MinecraftServer server)throws Exception {
        level=server.overworld();generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        if(!"freshgenerator:highlands_v2".equals(generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString()))
            throw new AssertionError("Wrong generator");
        var state=level.getChunkSource().randomState();sea=state.surfaceSystem().getSeaLevel();
        biomes=level.getBiomeManager().withDifferentSource(generator.getBiomeSource().createCachingResolver(state));
        environment=new GlacialSurface.Environment(state,state.surfaceSystem(),biomes,generator.generatorSettings().value());
        climate=state.createClimateSampler(SamplerContext.builder().enableCaches().build());
        Path sitesPath=Path.of(Objects.requireNonNull(System.getenv("FG_GLACIAL_SITES")));
        List<Site> sites;
        if(Files.exists(sitesPath))sites=Arrays.asList(G.fromJson(Files.readString(sitesPath),Site[].class));
        else{sites=select();Files.writeString(sitesPath,G.toJson(sites)+"\n");}
        if(sites.size()!=3)throw new AssertionError("Exactly three finite windows required");
        if("true".equals(System.getenv("FG_GLACIAL_DIAGNOSTIC_LAND"))){
            for(Site s:sites)if(s.name().equals("mountain_glacier")){
                int x0=Math.floorDiv(s.x()-s.side()/2,16)*16,z0=Math.floorDiv(s.z()-s.side()/2,16)*16;
                for(int z=z0;z<z0+s.side();z++)for(int x=x0;x<x0+s.side();x++)try{environment.land(x,z);}catch(IllegalArgumentException failure){
                    double h=environment.height(x,z),t=environment.surfaceTemperature(x,z),high=h;
                    for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)high=Math.max(high,environment.height(x+dx*48,z+dz*48));
                    double gx=(environment.height(x+16,z)-environment.height(x-16,z))/32,gz=(environment.height(x,z+16)-environment.height(x,z-16))/32;
                    var values=Map.of("x",x,"z",z,"height",h,"temperature",t,"coldness_unbounded_quintic",smooth(.25,-.50,t),"supply_unbounded_quintic",smooth(0,45,high-h),"slope",Math.hypot(gx,gz),"uphill_height",high,"gradient_x",gx,"gradient_z",gz);
                    Files.writeString(Path.of("glacial-invalid-land.json"),G.toJson(values)+"\n");System.err.println("GLACIAL_INVALID_LAND "+G.toJson(values));throw failure;
                }
            }
            throw new AssertionError("Diagnostic did not reproduce invalid land inputs");
        }
        List<Map<String,Object>> windows=new ArrayList<>();
        for(Site site:sites)windows.add(scan(site));
        Map<String,Object> report=new LinkedHashMap<>();report.put("seed",level.getSeed());report.put("sea_level",sea);
        report.put("surface_climate_contract",verifySurfaceClimate(server,sites));
        report.put("sites",sites);report.put("windows",windows);
        report.put("scope","Seeded native-biome/density scout; actual FULL block windows. Six-neighbour ice components are finite and explicitly marked when truncated. No Minecraft GUI, player, teleport, or writes to an existing player world.");
        Files.writeString(Path.of("glacial-world.json"),G.toJson(report)+"\n");
        if(!"true".equals(System.getenv("FG_GLACIAL_BASELINE")))for(Map<String,Object> w:windows){
            Site site=(Site)w.get("site");
            if(site.name().equals("ocean_icebergs")){
                require(w,"large_iceberg_components",1);require(w,"small_iceberg_components",1);
                require(w,"ice_above_sea",100);require(w,"ice_below_sea",100);require(w,"ice_chunk_crossing_edges",1);
            }else if(site.name().equals("mountain_glacier")){
                require(w,"packed_and_blue_ice",500);require(w,"maximum_column_ice_thickness",8);
                if(((Number)w.get("unsupported_glacier_basal_columns")).intValue()!=0)
                    throw new AssertionError("Unsupported mountain glacier basal column: "+w.get("unsupported_basal_examples"));
            }else if(site.name().equals("warm_control")&&((Number)w.get("predicted_ice_columns")).intValue()!=0)
                throw new AssertionError("Warm control predicted new ice");
        }
        System.out.println("GLACIAL_PASS windows="+windows.size());
    }
    private Map<String,Object> verifySurfaceClimate(MinecraftServer server,List<Site> sites)throws ReflectiveOperationException{
        java.lang.reflect.Method actualTemperature;
        try{actualTemperature=environment.getClass().getMethod("surfaceTemperature",int.class,int.class);}
        catch(NoSuchMethodException historical){
            // The same observer may be loaded with the old production JAR.
            // Absence is explicit unknown coverage, never zero mismatches.
            return Map.of("status","unavailable_in_historical_runtime","checked_positions",0);
        }
        var registry=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        var function=registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,
            Identifier.parse("freshgenerator:highlands_v2/landscape/climate/surface_temperature"))).value();
        var sampler=level.getChunkSource().randomState().getSampler(function);
        var explicitHeight=level.getChunkSource().randomState().getSampler(registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,
            Identifier.parse("freshgenerator:highlands_v2/coherent/height"))).value());
        var context=SamplerContext.builder().enableCaches().build();
        int checked=0,mountains=0;
        // Same continuous metric must be read at both sides of signed chunk
        // boundaries and at the actual measured glacier sites. The routed Y0
        // cave-temperature regression used to disagree for high terrain.
        List<int[]> positions=new ArrayList<>();
        for(int x:new int[]{-16385,-129,-17,-16,-1,0,15,16,127,16383})
            for(int z:new int[]{-16385,-129,-17,-16,-1,0,15,16,127,16383})positions.add(new int[]{x,z});
        for(Site s:sites)for(int dz=-16;dz<=16;dz+=8)for(int dx=-16;dx<=16;dx+=8)positions.add(new int[]{s.x()+dx,s.z()+dz});
        int standardPositions=positions.size();
        int[] far={-29999999,-29999984,29999984,29999999},near={-17,-1,0,16};
        for(int x:far)for(int z:far)positions.add(new int[]{x,z});
        for(int x:far)for(int z:near)positions.add(new int[]{x,z});
        for(int z:far)for(int x:near)positions.add(new int[]{x,z});
        // Far coordinates are sampler-only: no getChunk/getBlockState or FULL
        // generation is performed by this contract verification.
        for(int[] p:positions){
            double height=environment.height(p[0],p[1]);
            double expectedHeight=explicitHeight.sampleValue(context,p[0],0,p[1]);
            double expected=sampler.sampleValue(context,p[0],0,p[1]);
            double actual=((Number)actualTemperature.invoke(environment,p[0],p[1])).doubleValue();
            if(!Double.isFinite(height)||!Double.isFinite(expectedHeight)||!Double.isFinite(expected)||!Double.isFinite(actual))throw new AssertionError("Nonfinite glacier height/climate at "+p[0]+","+p[1]);
            if(Math.abs(height-expectedHeight)>0.000001)throw new AssertionError("Glacier height debug role disagrees with coherent terrain at "+p[0]+","+p[1]);
            if(Math.abs(expected-actual)>0.000001)throw new AssertionError("Glacier climate disagrees with surface at "+p[0]+","+p[1]+": "+actual+" != "+expected);
            if(height>=115)mountains++;
            checked++;
        }
        return Map.of("status","checked","checked_positions",checked,"standard_contract_positions",standardPositions,"far_sampler_only_positions",positions.size()-standardPositions,"nonfinite_positions",0,"mountain_positions",mountains,"mismatches",0,
            "expected","explicit coherent/height and surface_temperature densities","actual","glacier terrain debug-role height and routed climate sampled at floor(H); no second altitude lapse");
    }
    private static void require(Map<String,Object> w,String key,long minimum){
        if(((Number)w.get(key)).longValue()<minimum)throw new AssertionError(key+" below "+minimum+": "+w.get(key)+" at "+w.get("site"));
    }
    private List<Site> select()throws Exception {
        List<Candidate> oceans=new ArrayList<>(),lands=new ArrayList<>();Candidate warm=null;
        Map<String,Integer> sampledBiomes=new TreeMap<>();int coarse=0,deepCold=0,geometricHits=0,coarseOceanHits=0;
        List<Map<String,Object>> coldExamples=new ArrayList<>(),frozenExamples=new ArrayList<>();double coldMinTemperature=99,coldMinHeight=999;
        int scoutStep=Integer.parseInt(System.getenv().getOrDefault("FG_GLACIAL_SCOUT_STEP","256"));
        if(scoutStep<128||scoutStep>2048||scoutStep%128!=0)throw new AssertionError("Unsafe scout step");
        int scoutMin=-64*scoutStep+32,scoutMax=64*scoutStep+32;
        // An offset avoids aliasing the64-block small-berg cell boundaries.
        // A wider region at the same129² point budget also reaches rare cold seas.
        for(int z=scoutMin;z<=scoutMax;z+=scoutStep)for(int x=scoutMin;x<=scoutMax;x+=scoutStep){
            coarse++;double h=environment.height(x,z);String biome=biome(x,z);sampledBiomes.merge(biome,1,Integer::sum);
            if(biome.contains("cold_ocean")||biome.contains("frozen_ocean")){
                double temperature=Climate.unquantizeCoord(climate.sample(Math.floorDiv(x,4),sea/4,Math.floorDiv(z,4)).temperature());
                coldMinTemperature=Math.min(coldMinTemperature,temperature);coldMinHeight=Math.min(coldMinHeight,h);
                if(h<sea-8){deepCold++;if(GlacialIce.ocean(level.getSeed(),x,z,sea,(int)Math.floor(h),1).present())geometricHits++;}
                if(h<sea-8){
                    var actual=environment.ocean(x,z);if(actual.present())coarseOceanHits++;
                    double quality=smooth(-.08,-.64,temperature)*smooth(sea-5,sea-19,h);
                    if(biome.contains("cold_ocean"))quality*=.65;
                    oceans.add(new Candidate(x,z,(actual.present()?(actual.topY()-sea)*10:0)+quality));
                }
                Map<String,Object> example=Map.of("x",x,"z",z,"height",h,"biome",biome,"temperature",temperature,"full_cold_profile",GlacialIce.ocean(level.getSeed(),x,z,sea,(int)Math.floor(h),1),"environment_profile",environment.ocean(x,z));
                if(coldExamples.size()<12)coldExamples.add(example);
                if(biome.contains("frozen_ocean")&&frozenExamples.size()<8)frozenExamples.add(example);
            }
            if(h<sea-8){
                var ice=environment.ocean(x,z);
                if((biome.equals("minecraft:warm_ocean")||biome.equals("minecraft:lukewarm_ocean")||biome.equals("minecraft:deep_lukewarm_ocean"))
                    &&(warm==null||Math.hypot(x,z)<warm.score()))warm=new Candidate(x,z,Math.hypot(x,z));
            }else if(h>=115){
                var ice=environment.land(x,z);if(ice.thickness()>0)lands.add(new Candidate(x,z,ice.thickness()));
            }
        }
        oceans.sort(Comparator.comparingDouble(Candidate::score).reversed());lands.sort(Comparator.comparingDouble(Candidate::score).reversed());
        Candidate ocean=null,land=null;int oceanFine=0,landFine=0;
        // The broad grid catches districts, then fixed local grids find their massifs.
        List<Candidate> oceanDistricts=new ArrayList<>();
        for(Candidate candidate:oceans){
            if(oceanDistricts.stream().anyMatch(other->Math.hypot(other.x()-candidate.x(),other.z()-candidate.z())<256))continue;
            oceanDistricts.add(candidate);if(oceanDistricts.size()==16)break;
        }
        for(Candidate district:oceanDistricts)
            for(int dz=-64;dz<=64;dz+=8)for(int dx=-64;dx<=64;dx+=8){
                int x=district.x()+dx,z=district.z()+dz;oceanFine++;var ice=environment.ocean(x,z);
                if(ice.present()&&(ocean==null||ice.topY()-sea>ocean.score()))ocean=new Candidate(x,z,ice.topY()-sea);
            }
        for(Candidate district:lands.subList(0,Math.min(8,lands.size())))
            for(int dz=-64;dz<=64;dz+=8)for(int dx=-64;dx<=64;dx+=8){
                int x=district.x()+dx,z=district.z()+dz;landFine++;var ice=environment.land(x,z);
                if(ice.thickness()>0&&(land==null||ice.thickness()>land.score()))land=new Candidate(x,z,ice.thickness());
            }
        Map<String,Object> scout=new LinkedHashMap<>();scout.put("coarse_grid",Map.of("min",scoutMin,"max",scoutMax,"step",scoutStep,"samples",coarse));
        scout.put("ocean_fine_samples",oceanFine);scout.put("land_fine_samples",landFine);scout.put("coarse_ocean_ice_hits",coarseOceanHits);scout.put("ocean_refinement_districts",oceanDistricts);
        scout.put("coarse_land_ice_hits",lands.size());scout.put("biomes",sampledBiomes);scout.put("ocean",ocean);scout.put("land",land);scout.put("warm",warm);
        scout.put("cold_ocean_deep_samples",deepCold);scout.put("cold_ocean_geometric_hits_if_full_cold",geometricHits);scout.put("cold_ocean_min_temperature",coldMinTemperature);scout.put("cold_ocean_min_height",coldMinHeight);scout.put("cold_ocean_examples",coldExamples);scout.put("frozen_ocean_examples",frozenExamples);
        Files.writeString(Path.of("glacial-scout.json"),G.toJson(scout)+"\n");
        if(ocean==null||land==null||warm==null)throw new AssertionError("No representative ocean/mountain/warm sites; see glacial-scout.json");
        System.out.println("GLACIAL_SCOUT "+G.toJson(scout));
        return List.of(new Site("ocean_icebergs",ocean.x(),ocean.z(),192),new Site("mountain_glacier",land.x(),land.z(),128),new Site("warm_control",warm.x(),warm.z(),48));
    }
    private static double smooth(double a,double b,double value){double t=Math.clamp((value-a)/(b-a),0,1);return t*t*t*(t*(t*6-15)+10);}
    private Map<String,Object> scan(Site site)throws Exception {
        int side=site.side();if(side<32||side>192||side%16!=0||!site.name().matches("[a-zA-Z0-9_-]+"))throw new AssertionError("Unsafe window");
        boolean ocean=site.name().equals("ocean_icebergs"),mountain=site.name().equals("mountain_glacier");
        int x0=Math.floorDiv(site.x()-side/2,16)*16,z0=Math.floorDiv(site.z()-side/2,16)*16,minY=-64,top=sea+64;
        int[] beds=new int[side*side];int predicted=0,maxPredicted=0;Map<String,Integer> biomeCounts=new TreeMap<>();
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int wx=x0+x,wz=z0+z,bed=(int)Math.floor(environment.height(wx,wz));beds[z*side+x]=bed;
            var oc=environment.ocean(wx,wz);var lc=environment.land(wx,wz);
            if(oc.present()||lc.thickness()>0)predicted++;
            maxPredicted=Math.max(maxPredicted,lc.thickness());top=Math.max(top,bed+lc.thickness()+12);
            biomeCounts.merge(biome(wx,wz),1,Integer::sum);
        }
        // Per-column biome raster (x fastest), so ice steps can be attributed to an actual ID border.
        try(var raster=Files.newBufferedWriter(Path.of(site.name()+"-biomes.txt"))){
            for(int z=0;z<side;z++){StringBuilder row=new StringBuilder();for(int x=0;x<side;x++){if(x>0)row.append(' ');row.append(biome(x0+x,z0+z));}raster.write(row.append('\n').toString());}
        }
        top=Math.min(319,top);int vertical=top-minY+1,plane=side*side;byte[] cells=new byte[plane*vertical];
        long started=System.nanoTime();int chunks=0;
        for(int cx=Math.floorDiv(x0,16)-1;cx<=Math.floorDiv(x0+side-1,16)+1;cx++)for(int cz=Math.floorDiv(z0,16)-1;cz<=Math.floorDiv(z0+side-1,16)+1;cz++){level.getChunk(cx,cz);chunks++;}
        MessageDigest occupancy=MessageDigest.getInstance("SHA-256"),states=MessageDigest.getInstance("SHA-256"),iceDigest=MessageDigest.getInstance("SHA-256");
        Map<String,Long> blocks=new TreeMap<>();long above=0,below=0,packedBlue=0,snow=0,iceEdges=0;
        int maxThickness=0,unsupported=0,profileGrounded=0;List<List<Integer>> unsupportedExamples=new ArrayList<>();
        int[] excessIceHeight=new int[plane];
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();Map<BlockState,byte[]> encoded=new HashMap<>();
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            var chunk=level.getChunk(Math.floorDiv(x0+x,16),Math.floorDiv(z0+z,16));int run=0,lowestGlacial=Integer.MAX_VALUE,highestGlacial=-999,bed=beds[z*side+x];
            int predictedLandThickness=environment.land(x0+x,z0+z).thickness();
            for(int y=minY;y<=top;y++){
                pos.set(x0+x,y,z0+z);BlockState block=chunk.getBlockState(pos);byte kind=kind(block);int i=(y-minY)*plane+z*side+x;cells[i]=kind;
                blocks.merge(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString(),1L,Long::sum);
                states.update(encoded.computeIfAbsent(block,b->(b.toString()+"\n").getBytes(StandardCharsets.UTF_8)));
                boolean ice=kind>=3&&kind<=5;iceDigest.update((byte)(ice?kind:0));
                if(ice){if(y>sea)above++;else if(y<sea)below++;}
                if(kind==4||kind==5){packedBlue++;run++;maxThickness=Math.max(maxThickness,run);if(y>=bed+1&&y<=bed+predictedLandThickness)lowestGlacial=Math.min(lowestGlacial,y);if(y>=bed-5)highestGlacial=Math.max(highestGlacial,y);}
                else run=0;
                if(kind==6||kind==7)snow++;
            }
            excessIceHeight[z*side+x]=Math.max(0,highestGlacial-bed);
            if(mountain&&predictedLandThickness>0&&lowestGlacial!=Integer.MAX_VALUE){
                profileGrounded++;pos.set(x0+x,lowestGlacial-1,z0+z);BlockState support=chunk.getBlockState(pos);
                if(support.isAir()||!support.getFluidState().isEmpty()){
                    unsupported++;if(unsupportedExamples.size()<20)unsupportedExamples.add(List.of(x0+x,lowestGlacial,z0+z));
                }
            }
        }
        occupancy.update(cells);
        int maxMarginJump=0,largeMarginJumps=0;
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int at=z*side+x;
            if(x+1<side){int jump=Math.abs(excessIceHeight[at]-excessIceHeight[at+1]);maxMarginJump=Math.max(maxMarginJump,jump);if(jump>8)largeMarginJumps++;}
            if(z+1<side){int jump=Math.abs(excessIceHeight[at]-excessIceHeight[at+side]);maxMarginJump=Math.max(maxMarginJump,jump);if(jump>8)largeMarginJumps++;}
        }
        for(int y=0;y<vertical;y++)for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int i=y*plane+z*side+x;if(!componentIce(cells[i],ocean))continue;
            if(x+1<side&&Math.floorDiv(x0+x,16)!=Math.floorDiv(x0+x+1,16)&&componentIce(cells[i+1],ocean))iceEdges++;
            if(z+1<side&&Math.floorDiv(z0+z,16)!=Math.floorDiv(z0+z+1,16)&&componentIce(cells[i+side],ocean))iceEdges++;
        }
        List<Component> components=components(cells,side,vertical,x0,minY,z0,ocean);
        long large=ocean?components.stream().filter(c->!c.touchesBoundary()&&c.maximumExposedHeight()>=15&&c.footprint()>=400&&Math.max(c.maxX()-c.minX()+1,c.maxZ()-c.minZ()+1)>=24).count():0;
        long small=ocean?components.stream().filter(c->!c.touchesBoundary()&&c.maximumExposedHeight()>=2&&c.maximumExposedHeight()<15&&c.footprint()<=300&&c.voxels()>=20).count():0;
        String binary=site.name()+"-voxels.bin.gz";
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(binary))))){
            out.writeInt(side);out.writeInt(vertical);out.writeInt(side);out.writeInt(x0);out.writeInt(minY);out.writeInt(z0);out.write(cells);
        }
        Map<String,Object> r=new LinkedHashMap<>();r.put("site",site);r.put("origin",List.of(x0,minY,z0));r.put("side",side);r.put("top_y",top);r.put("sea_level",sea);
        r.put("requested_full_chunks_with_halo",chunks);r.put("scanned_voxels",cells.length);r.put("biomes",biomeCounts);r.put("blocks",blocks);
        r.put("predicted_ice_columns",predicted);r.put("maximum_predicted_land_thickness",maxPredicted);r.put("ice_above_sea",above);r.put("ice_below_sea",below);
        r.put("packed_and_blue_ice",packedBlue);r.put("snow_voxels",snow);r.put("maximum_column_ice_thickness",maxThickness);r.put("ice_chunk_crossing_edges",iceEdges);
        r.put("measured_glacier_basal_columns",profileGrounded);r.put("unsupported_glacier_basal_columns",unsupported);r.put("unsupported_basal_examples",unsupportedExamples);
        r.put("maximum_adjacent_excess_ice_height_jump",maxMarginJump);r.put("adjacent_excess_ice_height_jumps_over_8",largeMarginJumps);
        r.put("excess_ice_height_definition","Maximum packed/blue ice Y minus the sampled terrain bed, clamped at zero; adjacent four-neighbour differences expose abrupt added-ice walls independently of terrain slope. Includes crevasses; no smoothness pass/fail threshold is imposed.");
        r.put("large_iceberg_components",large);r.put("small_iceberg_components",small);r.put("ice_components",components.size());r.put("largest_30_components",components.subList(0,Math.min(30,components.size())));
        r.put("component_definition",ocean?"Six-neighbour packed ice, blue ice and snow blocks; native thin ordinary sea ice excluded. Large: complete component footprint>=400, width>=24, exposed>=15. Small: complete footprint<=300, exposed2..14, volume>=20.":"Six-neighbour packed/blue ice; ordinary snow cover excluded.");
        r.put("voxel_file",binary);r.put("voxel_sha256",HexFormat.of().formatHex(occupancy.digest()));r.put("ice_sha256",HexFormat.of().formatHex(iceDigest.digest()));r.put("block_state_sha256",HexFormat.of().formatHex(states.digest()));
        r.put("voxel_format","gzip; six big-endian int32 sideX,height,sideZ,originX,minY,originZ; x fastest,z next,y last. 0air,1water,2rock/ground,3ice,4packed,5blue,6snowblock,7snowlayer,8other,9lava");
        r.put("milliseconds",(System.nanoTime()-started)/1e6);
        System.out.println("GLACIAL_WINDOW "+site.name()+" packedBlue="+packedBlue+" big="+large+" small="+small+" unsupported="+unsupported);
        return r;
    }
    private static byte kind(BlockState b){
        if(b.isAir())return 0;if(b.getFluidState().is(FluidTags.LAVA))return 9;if(b.getFluidState().is(FluidTags.WATER))return 1;
        if(b.is(Blocks.ICE))return 3;if(b.is(Blocks.PACKED_ICE))return 4;if(b.is(Blocks.BLUE_ICE))return 5;
        if(b.is(Blocks.SNOW_BLOCK))return 6;if(b.is(Blocks.SNOW))return 7;
        if(b.is(Blocks.STONE)||b.is(Blocks.DEEPSLATE)||b.is(Blocks.GRAVEL)||b.is(Blocks.DIRT)||b.is(Blocks.GRASS_BLOCK)||b.is(Blocks.CALCITE)||b.is(Blocks.GRANITE)||b.is(Blocks.ANDESITE)||b.is(Blocks.DIORITE)||b.is(Blocks.BEDROCK)||b.is(Blocks.SAND)||b.is(Blocks.SANDSTONE))return 2;
        return 8;
    }
    private static boolean componentIce(byte kind,boolean ocean){return kind==4||kind==5||(ocean&&kind==6);}
    private List<Component> components(byte[] cells,int side,int vertical,int x0,int minY,int z0,boolean ocean){
        int plane=side*side;boolean[] visited=new boolean[cells.length];int[] queue=new int[cells.length];List<Component> result=new ArrayList<>();
        for(int start=0;start<cells.length;start++){
            if(visited[start]||!componentIce(cells[start],ocean))continue;
            int head=0,tail=0,minX=side,minZ=side,low=vertical,maxX=0,maxZ=0,high=0,crossings=0;boolean boundary=false;BitSet footprint=new BitSet(plane);
            visited[start]=true;queue[tail++]=start;
            while(head<tail){
                int i=queue[head++],y=i/plane,z=i%plane/side,x=i%side;footprint.set(z*side+x);
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);low=Math.min(low,y);high=Math.max(high,y);
                if(x==0||z==0||y==0||x==side-1||z==side-1||y==vertical-1)boundary=true;
                for(int axis=0;axis<6;axis++){
                    int nx=x+(axis==0?1:axis==1?-1:0),nz=z+(axis==2?1:axis==3?-1:0),ny=y+(axis==4?1:axis==5?-1:0);
                    if(nx<0||nz<0||ny<0||nx>=side||nz>=side||ny>=vertical)continue;
                    int ni=ny*plane+nz*side+nx;if(!componentIce(cells[ni],ocean))continue;
                    if(axis==0&&Math.floorDiv(x0+x,16)!=Math.floorDiv(x0+nx,16)||axis==2&&Math.floorDiv(z0+z,16)!=Math.floorDiv(z0+nz,16))crossings++;
                    if(!visited[ni]){visited[ni]=true;queue[tail++]=ni;}
                }
            }
            result.add(new Component(tail,footprint.cardinality(),x0+minX,minY+low,z0+minZ,x0+maxX,minY+high,z0+maxZ,Math.max(0,minY+high-sea),crossings,boundary));
        }
        result.sort(Comparator.comparingInt(Component::voxels).reversed());return result;
    }
}
