package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Native registry, controlled climate reachability and nested geographic rasters.
 * No FULL chunk requests: biome resolver maps are not material/vegetation acceptance.
 */
public final class GlobalGeographyAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final String P="freshgenerator:highlands_v2/";
    private static final List<String> FIELDS=List.of("coherent/height","temperature","landscape/climate/surface_temperature","vegetation","continents","erosion","ridges","landscape/climate/regional_vegetation","regions/original_vegetation","landscape/climate/local_vegetation","landscape/climate/terrain_moisture","landscape/climate/continental_drying","landscape/mountain","landscape/valley_floor");
    private final Map<String,DensitySampler> fields=new LinkedHashMap<>();
    private final SamplerContext context=SamplerContext.builder().enableCaches().build();
    private Climate.ParameterList<Holder<Biome>> parameters;private BiomeResolver resolver;private Climate.Sampler climate;private ServerLevel level;private int shutdown=-1;
    private final Map<String,List<Map<String,Object>>> sites=new TreeMap<>();
    private record Cell(int x,int z,float[] fields,String biome,Climate.TargetPoint target) { }
    @Override public void onInitialize(){if(!"true".equals(System.getenv("FG_GLOBAL_GEOGRAPHY_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(s->{try{run(s);}catch(Throwable e){System.err.println("GLOBAL_GEOGRAPHY_FAIL");e.printStackTrace();}finally{shutdown=20;}});
        ServerTickEvents.END_SERVER_TICK.register(s->{if(shutdown>0&&--shutdown==0)s.halt(false);});}
    private static String id(Holder<Biome> b){return b.unwrapKey().orElseThrow().identifier().toString();}
    private static double u(long v){return Climate.unquantizeCoord(v);}
    private static long mid(Climate.Parameter p){return p.min()+(p.max()-p.min())/2;}
    private static List<Double> range(Climate.Parameter p){return List.of(u(p.min()),u(p.max()));}
    private static Map<String,Object> target(Climate.TargetPoint t){return Map.of("temperature",u(t.temperature()),"moisture",u(t.humidity()),"continentalness",u(t.continentalness()),"erosion",u(t.erosion()),"depth",u(t.depth()),"weirdness",u(t.weirdness()));}
    @SuppressWarnings("unchecked") private static Climate.ParameterList<Holder<Biome>> parameters(MultiNoiseBiomeSource source)throws Exception{var m=MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");m.setAccessible(true);return (Climate.ParameterList<Holder<Biome>>)m.invoke(source);}
    private static Map<String,Object> runtime(){var r=new LinkedHashMap<String,Object>();r.put("heap_used_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());r.put("heap_peak_pool_sum_bytes",ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum());r.put("process_cpu_seconds",ProcessHandle.current().info().totalCpuDuration().map(d->d.toNanos()/1e9).orElse(-1.));return r;}
    private float value(String field,int x,int z){return fields.get(field).sampleValue(context,x,0,z);}
    private Cell cell(int x,int z){float[] values=new float[FIELDS.size()];for(int i=0;i<values.length;i++)values[i]=value(FIELDS.get(i),x,z);int y=(int)Math.ceil(values[0])-1,qx=Math.floorDiv(x,4),qy=Math.floorDiv(y,4),qz=Math.floorDiv(z,4);return new Cell(x,z,values,id(resolver.getNoiseBiome(qx,qy,qz)),climate.sample(qx,qy,qz));}
    private Map<String,Object> site(Cell c,String map,int step){var r=new LinkedHashMap<String,Object>();r.put("seed",level.getSeed());r.put("x",c.x);r.put("z",c.z);r.put("biome",c.biome);r.put("height",c.fields[0]);r.put("temperature",c.fields[1]);r.put("moisture",c.fields[3]);r.put("source_map",map);r.put("sample_step_blocks",step);return r;}
    private Map<String,Object> map(String name,int x0,int z0,int side,int step)throws Exception{
        long start=System.nanoTime();var before=runtime();var count=new TreeMap<String,Integer>();Cell[] cells=new Cell[side*side];
        try(var out=Files.newBufferedWriter(Path.of(name+".csv"))){out.write("x,z,"+String.join(",",FIELDS)+",biome,climate_temperature,climate_moisture,climate_continentalness,climate_erosion,climate_depth,climate_weirdness\n");
            for(int iz=0;iz<side;iz++)for(int ix=0;ix<side;ix++){
                Cell c=cell(x0+ix*step,z0+iz*step);cells[iz*side+ix]=c;count.merge(c.biome,1,Integer::sum);StringBuilder row=new StringBuilder().append(c.x).append(',').append(c.z);for(float f:c.fields)row.append(',').append(f);row.append(',').append(c.biome);for(long v:new long[]{c.target.temperature(),c.target.humidity(),c.target.continentalness(),c.target.erosion(),c.target.depth(),c.target.weirdness()})row.append(',').append(u(v));out.write(row.append('\n').toString());
            }
        }
        var best=new TreeMap<String,Cell>();var bestScores=new TreeMap<String,Integer>();
        for(int iz=1;iz<side-1;iz++)for(int ix=1;ix<side-1;ix++){Cell c=cells[iz*side+ix];int score=0;for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)if(cells[(iz+dz)*side+ix+dx].biome.equals(c.biome))score++;if(score>bestScores.getOrDefault(c.biome,-1)){best.put(c.biome,c);bestScores.put(c.biome,score);}}
        for(var e:best.entrySet()){var item=site(e.getValue(),name,step);item.put("same_biome_3x3_samples",bestScores.get(e.getKey()));sites.computeIfAbsent(e.getKey(),k->new ArrayList<>()).add(item);}
        // Preserve sampled adjacency separately by scale; a 512-block pair is NOT a direct biome border.
        var edges=new TreeMap<String,Map<String,Object>>();
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){Cell a=cells[z*side+x];for(int next:new int[]{x+1<side?z*side+x+1:-1,z+1<side?(z+1)*side+x:-1})if(next>=0){Cell b=cells[next];if(a.biome.equals(b.biome))continue;String key=a.biome.compareTo(b.biome)<0?a.biome+"|"+b.biome:b.biome+"|"+a.biome;var edge=edges.computeIfAbsent(key,k->new LinkedHashMap<>(Map.of("sampled_contacts",0,"example_a",site(a,name,step),"example_b",site(b,name,step),"maximum_delta_height",0.,"maximum_delta_temperature",0.,"maximum_delta_moisture",0.)));edge.put("sampled_contacts",(int)edge.get("sampled_contacts")+1);for(var metric:Map.of("height",0,"temperature",1,"moisture",3).entrySet()){String metricKey="maximum_delta_"+metric.getKey();edge.put(metricKey,Math.max((double)edge.get(metricKey),Math.abs(a.fields[metric.getValue()]-b.fields[metric.getValue()])));}}}
        var result=new LinkedHashMap<String,Object>();result.put("name",name);result.put("origin_xz",List.of(x0,z0));result.put("side_samples",side);result.put("step_blocks",step);result.put("columns",cells.length);result.put("biome_counts",count);result.put("sampled_adjacency",edges);result.put("milliseconds",(System.nanoTime()-start)/1e6);result.put("runtime_before",before);result.put("runtime_after",runtime());Files.writeString(Path.of(name+"-summary.json"),G.toJson(result)+"\n");
        System.out.println("GLOBAL_GEOGRAPHY_MAP "+name+" samples="+cells.length+" types="+count.size()+" ms="+result.get("milliseconds"));return result;
    }
    /** Little-endian: JSON header line, then float32 arrays H,T,M,C,E,W (row-major, z outer),
     * uint16 biome palette indices and uint8 precipitation ordinals (NONE, RAIN, SNOW). */
    private Map<String,Object> compactMap(String name,int x0,int z0,int side,int step)throws Exception{
        long start=System.nanoTime();int n=side*side;float[][] f=new float[6][n];short[] b=new short[n];byte[] pr=new byte[n];
        var palette=new ArrayList<String>();var index=new HashMap<String,Integer>();var height=fields.get("coherent/height");
        var pos=new net.minecraft.core.BlockPos.MutableBlockPos();int sea=level.getSeaLevel();
        for(int iz=0;iz<side;iz++)for(int ix=0;ix<side;ix++){int x=x0+ix*step,z=z0+iz*step,i=iz*side+ix;
            float h=height.sampleValue(context,x,0,z);int y=(int)Math.ceil(h)-1,qx=Math.floorDiv(x,4),qy=Math.floorDiv(y,4),qz=Math.floorDiv(z,4);
            Holder<Biome> holder=resolver.getNoiseBiome(qx,qy,qz);String id=id(holder);
            b[i]=(short)(int)index.computeIfAbsent(id,s->{palette.add(s);return palette.size()-1;});
            var t=climate.sample(qx,qy,qz);f[0][i]=h;f[1][i]=(float)u(t.temperature());f[2][i]=(float)u(t.humidity());
            f[3][i]=(float)u(t.continentalness());f[4][i]=(float)u(t.erosion());f[5][i]=(float)u(t.weirdness());
            pos.set(x,Math.max(y+1,sea),z);pr[i]=(byte)holder.value().getPrecipitationAt(pos,sea).ordinal();}
        var header=new LinkedHashMap<String,Object>();header.put("name",name);header.put("seed",level.getSeed());header.put("origin_xz",List.of(x0,z0));
        header.put("side",side);header.put("step",step);header.put("fields",List.of("H","T","M","C","E","W"));header.put("palette",palette);header.put("precipitation",List.of("NONE","RAIN","SNOW"));
        try(var out=new java.io.BufferedOutputStream(Files.newOutputStream(Path.of(name+".bin")))){
            out.write((new Gson().toJson(header)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var buf=java.nio.ByteBuffer.allocate(n*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(float[] a:f){buf.clear();for(float v:a)buf.putFloat(v);out.write(buf.array(),0,n*4);}
            var sb=java.nio.ByteBuffer.allocate(n*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(short s:b)sb.putShort(s);out.write(sb.array());out.write(pr);}
        header.put("milliseconds",(System.nanoTime()-start)/1e6);System.out.println("GLOBAL_GEOGRAPHY_COMPACT "+name+" samples="+n+" types="+palette.size()+" ms="+header.get("milliseconds"));return header;
    }
    private void inventory(MinecraftServer server)throws Exception{
        List<String> all=server.registryAccess().lookupOrThrow(Registries.BIOME).listElements().map(GlobalGeographyAudit::id).sorted().toList();
        var entries=new ArrayList<Object>();var declared=new TreeSet<String>();var reachable=new TreeMap<String,Object>();int tested=0;
        for(var e:parameters.values()){
            var p=e.getFirst();String b=id(e.getSecond());declared.add(b);entries.add(Map.of("biome",b,"temperature",range(p.temperature()),"moisture",range(p.humidity()),"continentalness",range(p.continentalness()),"erosion",range(p.erosion()),"depth",range(p.depth()),"weirdness",range(p.weirdness()),"offset",u(p.offset())));
            var t=new Climate.TargetPoint(mid(p.temperature()),mid(p.humidity()),mid(p.continentalness()),mid(p.erosion()),mid(p.depth()),mid(p.weirdness()));String chosen=id(parameters.findValue(t));tested++;reachable.putIfAbsent(chosen,Map.of("target",target(t),"method","native_parameter_box_midpoint","chosen",chosen,"source_box_biome",b));
        }
        var random=new Random(0x67656f6772617068L);
        for(var e:parameters.values())if(!reachable.containsKey(id(e.getSecond()))){var p=e.getFirst();var axes=List.of(p.temperature(),p.humidity(),p.continentalness(),p.erosion(),p.depth(),p.weirdness());for(int trial=0;trial<128&&!reachable.containsKey(id(e.getSecond()));trial++){long[] v=new long[6];for(int i=0;i<6;i++){var a=axes.get(i);v[i]=a.min()+Math.round((a.max()-a.min())*random.nextDouble());}var t=new Climate.TargetPoint(v[0],v[1],v[2],v[3],v[4],v[5]);String chosen=id(parameters.findValue(t));tested++;reachable.putIfAbsent(chosen,Map.of("target",target(t),"method","native_parameter_box_seeded_interior_search","chosen",chosen,"source_box_biome",id(e.getSecond())));}}
        var missing=new TreeSet<>(declared);missing.removeAll(reachable.keySet());var dimensionIDs=new TreeMap<String,Object>();for(ServerLevel l:server.getAllLevels())dimensionIDs.put(l.dimension().identifier().toString(),l.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().map(GlobalGeographyAudit::id).sorted().toList());
        var out=new LinkedHashMap<String,Object>();out.put("registered_biomes",all);out.put("dimension_possible_biomes",dimensionIDs);out.put("overworld_parameter_biomes",declared);out.put("native_parameter_entries",entries);out.put("controlled_reachable",reachable);out.put("controlled_targets_tested",tested);out.put("declared_without_control_witness",missing);out.put("scope","Registry/native table inventory; controlled synthetic climate reachability is NOT natural-world frequency or FULL decoration evidence.");Files.writeString(Path.of("native-biome-inventory.json"),G.toJson(out)+"\n");System.out.println("GLOBAL_GEOGRAPHY_INVENTORY registered="+all.size()+" OW="+declared.size()+" controlled="+reachable.size());
    }
    private void exactFitness()throws Exception{
        var results=new ArrayList<Object>();for(int[] c:new int[][]{{40,120},{40,56},{40,184},{-24,120},{104,120},{136,140},{72,140},{200,140},{136,76},{136,204},{120,-80}}){Cell at=cell(c[0],c[1]);var byID=new TreeMap<String,Long>();for(var p:parameters.values())byID.merge(id(p.getSecond()),p.getFirst().fitness(at.target),Math::min);var nearest=byID.entrySet().stream().sorted(Map.Entry.comparingByValue()).limit(8).map(e->Map.of("biome",e.getKey(),"fitness_quantized",e.getValue())).toList();var row=new LinkedHashMap<String,Object>(site(at,"exact_fitness",4));row.put("climate",target(at.target));for(int i=0;i<FIELDS.size();i++)row.put(FIELDS.get(i),at.fields[i]);row.put("nearest_biome_fitness",nearest);results.add(row);}Files.writeString(Path.of("exact-fitness.json"),G.toJson(results)+"\n");
    }
    private List<Object> refineSites(Path input)throws Exception{
        var all=com.google.gson.JsonParser.parseString(Files.readString(input)).getAsJsonObject();var selected=new ArrayList<Object>();var diagnostics=new TreeMap<String,Object>();
        for(var entry:all.entrySet()){
            String wanted=entry.getKey();var candidates=new ArrayList<Map<String,Object>>();Map<String,Object> best=null;double bestScore=-1;
            for(var element:entry.getValue().getAsJsonArray()){
                var site=element.getAsJsonObject();int x=Math.floorDiv(site.get("x").getAsInt(),16)*16,z=Math.floorDiv(site.get("z").getAsInt(),16)*16;int hits32=0,hits64=0,hits96=0,n32=0,n64=0,n96=0;double meanH=0;
                for(int dz=-48;dz<48;dz+=4)for(int dx=-48;dx<48;dx+=4){int xx=x+dx,zz=z+dz;float H=value("coherent/height",xx,zz);String actual=id(resolver.getNoiseBiome(Math.floorDiv(xx,4),Math.floorDiv((int)Math.ceil(H)-1,4),Math.floorDiv(zz,4)));boolean hit=actual.equals(wanted);n96++;if(hit)hits96++;meanH+=H;if(dx>=-32&&dx<32&&dz>=-32&&dz<32){n64++;if(hit)hits64++;}if(dx>=-16&&dx<16&&dz>=-16&&dz<16){n32++;if(hit)hits32++;}}
                double f32=hits32/(double)n32,f64=hits64/(double)n64,f96=hits96/(double)n96,score=f64+.05*f32+.01*f96;
                var c=new LinkedHashMap<String,Object>();c.put("seed",level.getSeed());c.put("biome",wanted);c.put("x",x);c.put("z",z);c.put("source_map",site.get("source_map").getAsString());c.put("fraction_core32",f32);c.put("fraction_core64",f64);c.put("fraction_core96",f96);c.put("mean_height96",meanH/n96);candidates.add(c);if(score>bestScore){bestScore=score;best=c;}
            }
            if(best==null)continue;int side=(double)best.get("fraction_core64")<.65&&(double)best.get("fraction_core32")>.80?32:64;
            var chosen=new LinkedHashMap<String,Object>();chosen.put("name",wanted.substring(wanted.indexOf(':')+1));chosen.put("x",best.get("x"));chosen.put("z",best.get("z"));chosen.put("side",side);chosen.put("expectedBiome",wanted);selected.add(chosen);diagnostics.put(wanted,Map.of("selected",best,"all_candidates",candidates,"chosen_side",side));
        }
        Files.writeString(Path.of("refined-sites.json"),G.toJson(selected)+"\n");Files.writeString(Path.of("refined-sites-diagnostics.json"),G.toJson(Map.of("seed",level.getSeed(),"sample_step",4,"candidates",diagnostics,"scope","Native resolver footprint sampled at4blocks. Chooses strongest measured interior from existing candidates; small/shore patches may remain mixed and are explicitly reported. No FULL chunks generated."))+"\n");return selected;
    }
    /** Point probe: zoomed (block-level, BiomeManager) biome at the surface, fields, and
     * optional vertical profile of the active cave body. No chunks are generated. */
    private void probePoints(NoiseBasedChunkGenerator gen,net.minecraft.world.level.levelgen.RandomState state,Path input)throws Exception{
        var manager=level.getBiomeManager().withDifferentSource(gen.getBiomeSource().createCachingResolver(state));
        var registry=level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
        DensitySampler body=state.getSampler(registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+(registry.get(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+"karst/final_body"))).isPresent()?"karst/final_body":"cave_network/final_body")))).value());
        var points=com.google.gson.JsonParser.parseString(Files.readString(input)).getAsJsonArray();var out=new ArrayList<Object>();
        for(var e:points){var p=e.getAsJsonObject();int x=p.get("x").getAsInt(),z=p.get("z").getAsInt();
            float h=value("coherent/height",x,z);int y=(int)Math.ceil(h)-1;
            var row=new LinkedHashMap<String,Object>();row.put("x",x);row.put("z",z);row.put("H",h);
            row.put("biome_block",id(manager.getBiome(new net.minecraft.core.BlockPos(x,y,z))));
            row.put("biome_noise",id(resolver.getNoiseBiome(Math.floorDiv(x,4),Math.floorDiv(y,4),Math.floorDiv(z,4))));
            for(String f:List.of("temperature","landscape/climate/surface_temperature","vegetation","continents","erosion","ridges"))row.put(f,value(f,x,z));
            // Optional extra Y-independent density fields (diagnostics only, e.g. landscape components).
            String extra=System.getenv("FG_GEO_EXTRA_FIELDS");
            if(extra!=null)for(String f:extra.split(",")){var key=ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+f.trim()));
                registry.get(key).ifPresent(fn->row.put(f.trim(),state.getSampler(fn.value()).sampleValue(context,x,0,z)));}
            // Optional 3-D samples of named fields at an explicit Y (diagnostics only).
            if(p.has("y")&&p.has("fields")){int yy=p.get("y").getAsInt();var values=new LinkedHashMap<String,Object>();
                for(var f:p.getAsJsonArray("fields")){String name=f.getAsString();
                    registry.get(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+name)))
                        .ifPresent(fn->values.put(name,state.getSampler(fn.value()).sampleValue(context,x,yy,z)));}
                row.put("y",yy);row.put("values",values);}
            var t=climate.sample(Math.floorDiv(x,4),Math.floorDiv(y,4),Math.floorDiv(z,4));row.put("climate",target(t));
            if(p.has("y0")){int y0=p.get("y0").getAsInt(),y1=p.get("y1").getAsInt();int negative=0,run=0,best=0;
                for(int yy=y0;yy<=y1;yy++){double d=body.sampleValue(context,x,yy,z);if(d<0){negative++;run++;best=Math.max(best,run);}else run=0;}
                row.put("profile_levels",y1-y0+1);row.put("negative_levels",negative);row.put("longest_negative_run",best);}
            out.add(row);}
        Files.writeString(Path.of("points.json"),G.toJson(out)+"\n");
    }
    private void run(MinecraftServer server)throws Exception{
        level=server.overworld();var gen=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();if(!gen.generatorSettings().unwrapKey().orElseThrow().identifier().toString().equals("freshgenerator:highlands_v2"))throw new AssertionError("Wrong generator");var state=level.getChunkSource().randomState();var registry=server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);for(String name:FIELDS)fields.put(name,state.getSampler(registry.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION,Identifier.parse(P+name))).value()));climate=state.createClimateSampler(context);parameters=parameters((MultiNoiseBiomeSource)gen.getBiomeSource());resolver=MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.copyOf(parameters.values()))).createResolver(climate);inventory(server);exactFitness();
        String locate=System.getenv("FG_GEO_LOCATE");
        if(locate!=null){
            // Diagnostics: nearest generated structures of one id around given origins ("id;x,z;x,z").
            String[] parts=locate.split(";");var structures=level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var holder=structures.getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse(parts[0])));
            var found=new ArrayList<Object>();
            for(int i=1;i<parts.length;i++){String[] xz=parts[i].split(",");
                var hit=level.getChunkSource().getGenerator().findNearestMapStructure(level,net.minecraft.core.HolderSet.direct(holder),
                    new net.minecraft.core.BlockPos(Integer.parseInt(xz[0]),64,Integer.parseInt(xz[1])),64,false);
                if(hit!=null)found.add(Map.of("x",hit.getFirst().getX(),"z",hit.getFirst().getZ(),"from",parts[i]));}
            Files.writeString(Path.of("located.json"),G.toJson(Map.of("structure",parts[0],"found",found))+"\n");
            Files.writeString(Path.of("global-geography.json"),G.toJson(Map.of("seed",level.getSeed(),"maps",List.of()))+"\n");
            System.out.println("GLOBAL_GEOGRAPHY_PASS locate");return;
        }
        String pointsPath=System.getenv("FG_GEO_POINTS");
        if(pointsPath!=null){probePoints(gen,state,Path.of(pointsPath));Files.writeString(Path.of("global-geography.json"),G.toJson(Map.of("seed",level.getSeed(),"maps",List.of()))+"\n");System.out.println("GLOBAL_GEOGRAPHY_PASS points");return;}
        // Compact rasters for biome-map audits: "name:x0:z0:side:step;..." (surface quart biome,
        // lookup climate and native precipitation per sample), written as <name>.bin.
        String compact=System.getenv("FG_GEO_MAPS");
        if(compact!=null){var done=new ArrayList<Object>();for(String spec:compact.split(";")){String[] q=spec.split(":");
                done.add(compactMap(q[0],Integer.parseInt(q[1]),Integer.parseInt(q[2]),Integer.parseInt(q[3]),Integer.parseInt(q[4])));}
            Files.writeString(Path.of("global-geography.json"),G.toJson(Map.of("seed",level.getSeed(),"maps",List.of(),"compact_maps",done))+"\n");
            System.out.println("GLOBAL_GEOGRAPHY_PASS compact");return;}
        String refinement=System.getenv("FG_GEO_REFINE_SITES");if(refinement!=null){var selected=refineSites(Path.of(refinement));Files.writeString(Path.of("global-geography.json"),G.toJson(Map.of("seed",level.getSeed(),"maps",List.of(),"refined_sites",selected.size(),"runtime_final",runtime()))+"\n");System.out.println("GLOBAL_GEOGRAPHY_PASS refined="+selected.size());return;}
        var maps=new ArrayList<Object>();maps.add(map("coarse",-65536,-65536,256,512));maps.add(map("regional",-3072,-3072,192,32));maps.add(map("local-origin",-256,-256,128,4));
        // A second fixed distant raster prevents the origin alone defining local topology.
        int sx=(int)Math.floorMod(level.getSeed(),128)*128-8192,sz=(int)Math.floorMod(level.getSeed()>>>16,128)*128-8192;maps.add(map("local-distant",sx,sz,128,4));
        String contexts=System.getenv("FG_GEO_CONTEXT_BIOMES");if(contexts!=null)for(String biome:contexts.split(",")){var candidates=sites.get(biome);if(candidates==null||candidates.isEmpty())continue;var selected=candidates.getFirst();int cx=((Number)selected.get("x")).intValue(),cz=((Number)selected.get("z")).intValue();maps.add(map("context-"+biome.substring(biome.indexOf(':')+1),cx-1024,cz-1024,128,16));}
        Files.writeString(Path.of("natural-biome-sites.json"),G.toJson(sites)+"\n");if("true".equals(System.getenv("FG_GEO_REFINE_AFTER_MAPS")))refineSites(Path.of("natural-biome-sites.json"));Files.writeString(Path.of("global-geography.json"),G.toJson(Map.of("seed",level.getSeed(),"maps",maps,"runtime_final",runtime(),"scope","Native resolver rasters, not FULL block maps; sampled boundary lengths/areas resolve only at recorded step. No climate model changes or user save access."))+"\n");System.out.println("GLOBAL_GEOGRAPHY_PASS seed="+level.getSeed()+" found="+sites.size());
    }
}
