package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.core.ForestGrowth;
import dev.freshgenerator.forest.NativeTreeGrowth;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Actual native tree placements on isolated flat plots. Test-only observer,
 * never packed into the playable mod. Environmental inputs are controlled:
 * this measures architecture, not whole-world recruitment or visual acceptance. */
public final class ForestGrowthAudit implements ModInitializer {
    private static final int REPLICATES=12, SPACING=48, RADIUS=20, SCAN_HEIGHT=40;
    private static final long ECOLOGY_SEED=42L^0x6F7265737445434FL;
    private record Species(String id,ForestEcology.Family family) { }
    private record Treatment(String name,ForestEcology.Site site) { }
    private record Measurement(int logs,int leaves,int minLogY,int maxLogY,int maxLeafY,int totalHeight,
                               int leafHeight,int crownWidth,int crownDepth,int unanchoredLowestLogs,
                               boolean touchesBoundary,List<List<Object>> cells) { }
    private int shutdownTicks=-1;
    @Override public void onInitialize() {
        if(!"true".equals(System.getenv("FG_FOREST_GROWTH_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try {run(server);}catch(Throwable e){System.err.println("FOREST_GROWTH_FAIL");e.printStackTrace();}
            // Let native chunk/light tasks drain through the normal tick loop.
            // Halting directly from SERVER_STARTED can recursively drain the
            // large controlled-plot save queue while the server is stopping.
            finally {shutdownTicks=40;}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(shutdownTicks>0&&--shutdownTicks==0)server.halt(false);
        });
    }
    private void run(MinecraftServer server)throws Exception {
        ServerLevel level=server.overworld();ChunkGenerator generator=level.getChunkSource().getGenerator();
        if(!(generator instanceof FlatLevelSource))throw new AssertionError("Controlled audit requires its isolated flat world");
        var species=List.of(new Species("birch_bees_0002_leaf_litter",ForestEcology.Family.TEMPERATE),
            new Species("spruce_checked",ForestEcology.Family.CONIFER),
            new Species("fancy_oak_bees_0002_leaf_litter",ForestEcology.Family.TEMPERATE));
        var treatments=List.of(new Treatment("original",null),
            new Treatment("moist_sheltered",new ForestEcology.Site(95,63,.12,-1,.25,.45,0,0)),
            new Treatment("dry_exposed",new ForestEcology.Site(165,63,.85,2.5,.25,-.85,0,.5)),
            new Treatment("upper_cold",new ForestEcology.Site(235,63,.60,1.2,-.75,.25,0,.4)),
            new Treatment("open_sheltered",new ForestEcology.Site(95,63,.12,-1,.25,.45,0,1)));
        List<Map<String,Object>> attempts=new ArrayList<>(),groups=new ArrayList<>();
        Map<String,Object> examples=new LinkedHashMap<>();
        var adapter=new NativeTreeGrowth();int plot=0;
        for(int speciesIndex=0;speciesIndex<species.size();speciesIndex++) {
            Species kind=species.get(speciesIndex);
            PlacedFeature original=server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
                .getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,Identifier.parse("minecraft:"+kind.id()))).value();
            for(Treatment treatment:treatments) {
                List<Map<String,Object>> local=new ArrayList<>();String key=kind.id()+"/"+treatment.name();
                for(int replica=0;replica<REPLICATES;replica++,plot++) {
                    // Disjoint plots; same model coordinates and same native RNG per
                    // species/replica across treatments, so only environment changes.
                    int x=4096+24+(plot%12)*SPACING,z=4096+24+(plot/12)*SPACING;
                    for(int cx=(x-RADIUS)>>4;cx<=(x+RADIUS)>>4;cx++)
                        for(int cz=(z-RADIUS)>>4;cz<=(z+RADIUS)>>4;cz++)level.getChunk(cx,cz);
                    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
                    BlockPos origin=new BlockPos(x,y,z);
                    if(!level.getBlockState(origin.below()).is(Blocks.GRASS_BLOCK))
                        throw new AssertionError("Expected unmodified flat grass at "+origin);
                    // Flat generation has no decorations. Confirm the measurement
                    // volume does not contain a tree from a neighboring plot.
                    Measurement before=measure(level,origin,false);
                    if(before.logs()!=0||before.leaves()!=0)throw new AssertionError("Overlapping controlled plots");
                    int modelX=37+replica*113,modelZ=speciesIndex*277;
                    long randomSeed=0x349BC1A8L+replica*0x12D35BL+speciesIndex*0x7A113L;
                    ForestGrowth.Profile profile=treatment.site()==null?null:
                        ForestGrowth.sample(ECOLOGY_SEED,modelX,modelZ,kind.family(),treatment.site());
                    ForestGrowth.Shape shape=profile==null?null:ForestGrowth.shape(ECOLOGY_SEED,modelX,modelZ,profile);
                    PlacedFeature selected=shape==null?original:adapter.adapt(original,shape.sizeBand(),shape.crownBand());
                    boolean placed=selected.place(level,generator,RandomSource.create(randomSeed),origin);
                    boolean keepExample=!examples.containsKey(key);
                    Measurement actual=measure(level,origin,keepExample);
                    if(actual.touchesBoundary())throw new AssertionError("Tree exceeds measurement volume: "+key);
                    if(placed&&(actual.logs()==0||actual.leaves()==0||actual.unanchoredLowestLogs()!=0))
                        throw new AssertionError("Successful placement has missing canopy or unanchored lowest logs: "+key);
                    // Regression scoped to the two audited broadleaf features:
                    // a compact crown must not collapse into one flat leaf strip
                    // below an exposed terminal log. Not a rule for all species.
                    boolean malformedBroadleaf=placed&&kind.family()==ForestEcology.Family.TEMPERATE
                        &&(actual.leafHeight()<2||actual.maxLeafY()<actual.maxLogY());
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("species",kind.id());row.put("treatment",treatment.name());row.put("replica",replica);
                    row.put("random_seed",randomSeed);row.put("model_x",modelX);row.put("model_z",modelZ);
                    row.put("origin",List.of(x,y,z));row.put("site",treatment.site());row.put("requested_profile",profile);
                    row.put("requested_shape",shape);row.put("native_placed",placed);row.put("adapter_changed",selected!=original);
                    row.put("log_blocks",actual.logs());row.put("leaf_blocks",actual.leaves());
                    row.put("log_height",actual.logs()==0?0:actual.maxLogY()-actual.minLogY()+1);
                    row.put("height",actual.totalHeight());row.put("crown_width",actual.crownWidth());row.put("crown_depth",actual.crownDepth());
                    row.put("crown_height",actual.leafHeight());row.put("unanchored_lowest_logs",actual.unanchoredLowestLogs());
                    row.put("highest_log_y",actual.maxLogY());row.put("highest_leaf_y",actual.maxLeafY());
                    row.put("malformed_broadleaf_canopy",malformedBroadleaf);
                    row.put("crown_height_ratio",actual.totalHeight()==0?0:(actual.crownWidth()+actual.crownDepth())/(2.0*actual.totalHeight()));
                    attempts.add(row);local.add(row);
                    if(placed&&keepExample)examples.put(key,Map.of("replica",replica,"origin",List.of(x,y,z),
                        "cells",actual.cells(),"format","relative x,y,z,block; measured logs and leaves only"));
                }
                List<Map<String,Object>> successes=local.stream().filter(v->(boolean)v.get("native_placed")).toList();
                if(successes.isEmpty())throw new AssertionError("No native successes in "+key);
                Map<String,Object> summary=new LinkedHashMap<>();summary.put("species",kind.id());summary.put("treatment",treatment.name());
                summary.put("attempts",local.size());summary.put("placed",successes.size());summary.put("failed",local.size()-successes.size());
                summary.put("malformed_broadleaf_canopies",local.stream().filter(v->(boolean)v.get("malformed_broadleaf_canopy")).count());
                summary.put("failed_with_tree_blocks",local.stream().filter(v->!(boolean)v.get("native_placed")&&
                    ((int)v.get("log_blocks")>0||(int)v.get("leaf_blocks")>0)).count());
                for(String metric:List.of("height","log_height","crown_width","crown_depth","crown_height","crown_height_ratio","log_blocks","leaf_blocks"))
                    summary.put(metric,stats(successes,v->((Number)v.get(metric)).doubleValue()));
                groups.add(summary);System.out.println("FOREST_GROWTH_GROUP "+new Gson().toJson(summary));
            }
        }
        List<Map<String,Object>> directions=new ArrayList<>();
        for(Species kind:species) {
            double moist=mean(groups,kind.id(),"moist_sheltered","height"),dry=mean(groups,kind.id(),"dry_exposed","height"),
                upper=mean(groups,kind.id(),"upper_cold","height");
            directions.add(Map.of("species",kind.id(),"moist_taller_than_dry",moist>dry,"moist_taller_than_upper",moist>upper,
                "open_crown_ratio_exceeds_forest",mean(groups,kind.id(),"open_sheltered","crown_height_ratio")>
                    mean(groups,kind.id(),"moist_sheltered","crown_height_ratio")));
        }
        var report=new LinkedHashMap<String,Object>();report.put("seed",level.getSeed());report.put("replicates",REPLICATES);
        report.put("plot_spacing",SPACING);report.put("scan_radius",RADIUS);report.put("scan_height",SCAN_HEIGHT);
        report.put("groups",groups);report.put("direction_checks",directions);report.put("attempts",attempts);
        report.put("scope","Controlled native placements on isolated flat grass plots. Environment is an input to the growth model; actual geometry is measured from blocks. Same RNG and model coordinates across treatments. Successful-placement metrics exclude failures; every attempt and failure is retained. No retries, natural recruitment, villager/client tests, or claim of real physiological growth.");
        Gson gson=new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(Path.of("forest-growth.json"),gson.toJson(report));
        Files.writeString(Path.of("forest-growth-examples.json"),gson.toJson(examples));
        long malformed=attempts.stream().filter(v->(boolean)v.get("malformed_broadleaf_canopy")).count();
        if(malformed!=0)throw new AssertionError("Malformed broadleaf canopies: "+malformed+"; full measurements preserved");
        System.out.println("FOREST_GROWTH_PASS attempts="+attempts.size()+" groups="+groups.size());
    }
    private static Measurement measure(ServerLevel level,BlockPos origin,boolean capture) {
        int logs=0,leaves=0,minLog=999,maxLog=-999,minLeaf=999,maxLeaf=-999,minX=999,maxX=-999,minZ=999,maxZ=-999;
        boolean boundary=false;List<List<Object>> cells=new ArrayList<>();List<BlockPos> lowestLogs=new ArrayList<>();
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        for(int dz=-RADIUS;dz<=RADIUS;dz++)for(int dx=-RADIUS;dx<=RADIUS;dx++) {
            int x=origin.getX()+dx,z=origin.getZ()+dz;var chunk=level.getChunk(x>>4,z>>4);
            for(int dy=0;dy<SCAN_HEIGHT;dy++) {
                cursor.set(x,origin.getY()+dy,z);var state=chunk.getBlockState(cursor);
                boolean log=state.is(BlockTags.LOGS),leaf=state.is(BlockTags.LEAVES);if(!log&&!leaf)continue;
                boundary|=Math.abs(dx)==RADIUS||Math.abs(dz)==RADIUS||dy==SCAN_HEIGHT-1;
                if(log) {logs++;if(dy<minLog){minLog=dy;lowestLogs.clear();}if(dy==minLog)lowestLogs.add(cursor.immutable());maxLog=Math.max(maxLog,dy);}
                if(leaf) {leaves++;minLeaf=Math.min(minLeaf,dy);maxLeaf=Math.max(maxLeaf,dy);minX=Math.min(minX,dx);maxX=Math.max(maxX,dx);minZ=Math.min(minZ,dz);maxZ=Math.max(maxZ,dz);}
                if(capture)cells.add(List.of(dx,dy,dz,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
            }
        }
        int unsupported=(int)lowestLogs.stream().filter(p->!level.getBlockState(p.below()).isSolid()).count();
        return new Measurement(logs,leaves,minLog,maxLog,maxLeaf,logs+leaves==0?0:Math.max(maxLog,maxLeaf)+1,
            leaves==0?0:maxLeaf-minLeaf+1,leaves==0?0:maxX-minX+1,leaves==0?0:maxZ-minZ+1,unsupported,boundary,cells);
    }
    private static Map<String,Double> stats(List<Map<String,Object>> rows,ToDoubleFunction<Map<String,Object>> value) {
        double[] a=rows.stream().mapToDouble(value).sorted().toArray();double sum=0;for(double v:a)sum+=v;
        return Map.of("min",a[0],"median",(a[(a.length-1)/2]+a[a.length/2])/2,"max",a[a.length-1],"mean",sum/a.length);
    }
    @SuppressWarnings("unchecked")
    private static double mean(List<Map<String,Object>> groups,String species,String treatment,String metric) {
        Map<String,Object> group=groups.stream().filter(g->g.get("species").equals(species)&&g.get("treatment").equals(treatment)).findFirst().orElseThrow();
        return ((Map<String,Double>)group.get(metric)).get("mean");
    }
}
