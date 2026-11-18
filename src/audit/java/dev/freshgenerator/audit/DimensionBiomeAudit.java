package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Audit only. Distinguishes natural underground/Nether/End from a forced utility void world. */
public final class DimensionBiomeAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> CAVES=Set.of("minecraft:deep_dark","minecraft:dripstone_caves","minecraft:lush_caves","minecraft:sulfur_caves");
    private final SamplerContext ctx=SamplerContext.builder().enableCaches().build();
    private int shutdown=-1;
    private record Site(String biome,int x,int y,int z,String method) { }
    @Override public void onInitialize(){if(!"true".equals(System.getenv("FG_DIMENSION_BIOME_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(s->{try{run(s);}catch(Throwable e){System.err.println("DIMENSION_BIOME_FAIL");e.printStackTrace();}finally{shutdown=20;}});
        ServerTickEvents.END_SERVER_TICK.register(s->{if(shutdown>0&&--shutdown==0)s.halt(false);});}
    private static String id(Holder<Biome> b){return b.unwrapKey().orElseThrow().identifier().toString();}
    private Map<String,Site> caves(MinecraftServer server)throws Exception{
        var level=server.overworld();var gen=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();var state=level.getChunkSource().randomState();var resolver=gen.getBiomeSource().createCachingResolver(state);
        var height=state.getSampler(server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse("freshgenerator:highlands_v2/coherent/height"))).value());
        var found=new TreeMap<String,Site>();int samples=0;
        outer:for(int z=-16384;z<16384;z+=256)for(int x=-16384;x<16384;x+=256){float h=height.sampleValue(ctx,x,0,z);for(int y:new int[]{-56,-32,0,32,(int)h-64,(int)h-96}){
            if(y<level.getMinY()||y>=h-40||y>level.getMaxY())continue;String b=id(resolver.getNoiseBiome(Math.floorDiv(x,4),Math.floorDiv(y,4),Math.floorDiv(z,4)));samples++;
            if(CAVES.contains(b))found.putIfAbsent(b,new Site(b,x,y,z,"natural_underground_native_resolver"));
            if(found.keySet().containsAll(CAVES))break outer;
        }}
        System.out.println("DIMENSION_BIOME_CAVE_SCOUT samples="+samples+" found="+found.keySet());return found;
    }
    private Map<String,Site> dimensionSites(ServerLevel level){
        var gen=level.getChunkSource().getGenerator();var resolver=gen.getBiomeSource().createCachingResolver(level.getChunkSource().randomState());var expected=gen.getBiomeSource().possibleBiomes().stream().map(DimensionBiomeAudit::id).collect(java.util.stream.Collectors.toSet());var found=new TreeMap<String,Site>();int samples=0;
        String center=id(resolver.getNoiseBiome(0,16,0));found.put(center,new Site(center,0,64,0,"natural_dimension_native_resolver"));
        outer:for(int z=-8192;z<8192;z+=128)for(int x=-8192;x<8192;x+=128){for(int y:new int[]{32,64,96}){String b=id(resolver.getNoiseBiome(Math.floorDiv(x,4),Math.floorDiv(y,4),Math.floorDiv(z,4)));samples++;found.putIfAbsent(b,new Site(b,x,y,z,"natural_dimension_native_resolver"));if(found.keySet().containsAll(expected))break outer;}}
        System.out.println("DIMENSION_BIOME_SCOUT "+level.dimension().identifier()+" samples="+samples+" found="+found.keySet());return found;
    }
    private Object full(ServerLevel level,Site site)throws Exception{
        int side=32,x0=Math.floorDiv(site.x-16,16)*16,z0=Math.floorDiv(site.z-16,16)*16,y0=level.getMinY(),height=level.getMaxY()-y0+1,plane=side*side;long start=System.nanoTime();
        for(int cz=Math.floorDiv(z0,16)-1;cz<=Math.floorDiv(z0+side-1,16)+1;cz++)for(int cx=Math.floorDiv(x0,16)-1;cx<=Math.floorDiv(x0+side-1,16)+1;cx++)level.getChunk(cx,cz);
        var palette=new ArrayList<String>();var index=new HashMap<String,Integer>();var states=new int[plane*height];var at=new BlockPos.MutableBlockPos();var materialCounts=new TreeMap<String,Integer>();var biomeCounts=new TreeMap<String,Integer>();
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){var chunk=level.getChunk(Math.floorDiv(x0+x,16),Math.floorDiv(z0+z,16));for(int y=y0;y<y0+height;y++){
            at.set(x0+x,y,z0+z);var block=chunk.getBlockState(at);String n=block.toString();int id=index.computeIfAbsent(n,k->{palette.add(k);return palette.size()-1;});states[(y-y0)*plane+z*side+x]=id;
            if(Math.abs(y-site.y)<=16)materialCounts.merge(n,1,Integer::sum);
            if(x%4==0&&z%4==0&&y%4==0&&Math.abs(y-site.y)<=16)biomeCounts.merge(id(level.getNoiseBiome(Math.floorDiv(x0+x,4),Math.floorDiv(y,4),Math.floorDiv(z0+z,4))),1,Integer::sum);
        }}
        String centerActual=id(level.getNoiseBiome(Math.floorDiv(site.x,4),Math.floorDiv(site.y,4),Math.floorDiv(site.z,4)));String file=level.dimension().identifier().getPath()+"-"+site.biome.substring(site.biome.indexOf(':')+1)+"-states.bin.gz";
        var meta=Map.of("shape_xyz",List.of(side,height,side),"origin_xyz",List.of(x0,y0,z0),"palette",palette);byte[] header=G.toJson(meta).getBytes(StandardCharsets.UTF_8);var digest=MessageDigest.getInstance("SHA-256");digest.update(header);
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(file))))){out.writeInt(header.length);out.write(header);for(int id:states){out.writeShort(id);digest.update((byte)(id>>>8));digest.update((byte)id);}}
        var report=new LinkedHashMap<String,Object>();report.put("dimension",level.dimension().identifier().toString());report.put("site",site);report.put("center_actual_stored_biome",centerActual);report.put("center_biome_matches",centerActual.equals(site.biome));report.put("origin_xyz",List.of(x0,y0,z0));report.put("shape_xyz",List.of(side,height,side));report.put("raw_file",file);report.put("raw_state_ids_sha256",HexFormat.of().formatHex(digest.digest()));report.put("biomes_in_probe_band",biomeCounts);report.put("materials_in_probe_band",materialCounts);report.put("probe_band_y",List.of(site.y-16,site.y+16));report.put("milliseconds",(System.nanoTime()-start)/1e6);
        System.out.println("DIMENSION_BIOME_FULL "+site.biome+" center="+centerActual+" ms="+report.get("milliseconds"));return report;
    }
    private void run(MinecraftServer server)throws Exception{
        var windows=new ArrayList<Object>();var missing=new ArrayList<String>();
        if("true".equals(System.getenv("FG_FORCED_VOID"))){var level=server.overworld();String b=id(level.getBiome(new BlockPos(0,64,0)));if(!b.equals("minecraft:the_void"))throw new AssertionError("Forced flat utility world is "+b);windows.add(full(level,new Site(b,0,64,0,"forced_fixed_flat_utility_control_not_normal_geography")));}
        else{
            var found=caves(server);for(String b:CAVES){if(found.containsKey(b))windows.add(full(server.overworld(),found.get(b)));else missing.add(b);}
            if(!"true".equals(System.getenv("FG_OW_CAVES_ONLY")))for(var key:List.of(Level.NETHER,Level.END)){var level=Objects.requireNonNull(server.getLevel(key));var sites=dimensionSites(level);for(var site:sites.values())windows.add(full(level,site));}
        }
        Files.writeString(Path.of("dimension-biomes.json"),G.toJson(Map.of("seed",server.overworld().getSeed(),"windows",windows,"natural_cave_biomes_not_found",missing,"overworld_caves_only","true".equals(System.getenv("FG_OW_CAVES_ONLY")),"scope","Native biome sources and FULL block windows in the explicitly listed dimensions only. Cave controls use buried Y, not surface labels. Any historical Nether/End/void windows remain outside the current Overworld acceptance scope."))+"\n");System.out.println("DIMENSION_BIOME_PASS windows="+windows.size()+" cave_missing="+missing);
    }
}
