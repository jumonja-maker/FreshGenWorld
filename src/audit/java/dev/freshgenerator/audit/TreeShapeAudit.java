package dev.freshgenerator.audit;

import com.google.gson.*;
import dev.freshgenerator.forest.NativeTreeGrowth;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import java.nio.file.*;
import java.util.*;

/** Controlled block geometry only. Does not prove ecological recruitment. */
public final class TreeShapeAudit implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static final int RADIUS=24,SPACING=64,HEIGHT=64;
    private int shutdown=-1;
    @Override public void onInitialize(){
        if(!"true".equals(System.getenv("FG_TREE_SHAPE_AUDIT")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{try{
            var level=server.overworld();var gen=level.getChunkSource().getGenerator();
            if(!(gen instanceof FlatLevelSource))throw new AssertionError("Isolated flat world required");
            var adapter=new NativeTreeGrowth(level.registryAccess());List<Map<String,Object>> rows=new ArrayList<>();int plot=0;
            for(String species:List.of("dark_oak_checked","pale_oak_checked","cherry_checked","mangrove_checked","jungle_tree","acacia_checked","spruce_checked")){
                PlacedFeature original=server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(
                    ResourceKey.create(Registries.PLACED_FEATURE,Identifier.parse("minecraft:"+species))).value();
                int successes=0;
                for(int size:new int[]{0,2,4})for(int crown:new int[]{0,2})for(int replica=0;replica<2;replica++,plot++){
                    int x=8192+32+(plot%12)*SPACING,z=8192+32+(plot/12)*SPACING;
                    for(int cx=(x-RADIUS)>>4;cx<=(x+RADIUS)>>4;cx++)for(int cz=(z-RADIUS)>>4;cz<=(z+RADIUS)>>4;cz++)level.getChunk(cx,cz);
                    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);var origin=new BlockPos(x,y,z);
                    var selected=adapter.adapt(original,size,crown);
                    boolean placed=selected.place(level,gen,RandomSource.create(0x3289A21L+replica*31L+species.hashCode()),origin);
                    Map<String,Object> row=measure(level,origin);row.put("species",species);row.put("size_band",size);row.put("crown_band",crown);row.put("replica",replica);
                    row.put("adapter_changed",selected!=original);row.put("placed",placed);row.put("origin",List.of(x,y,z));
                    if(placed){successes++;if((int)row.get("logs")==0||(int)row.get("leaves")==0||(boolean)row.get("touches_boundary"))throw new AssertionError("Invalid tree geometry "+species+" "+size+"/"+crown+" "+G.toJson(row));}
                    rows.add(row);
                }
                if(successes==0)throw new AssertionError("No native successes for "+species);
            }
            Map<String,Object> report=new LinkedHashMap<>();report.put("seed",level.getSeed());report.put("attempts",rows);report.put("count",rows.size());
            report.put("scope","84 disjoint flat native tree plots; fixed native RNG per species/replica across size and crown bands. Explicit geometry extremes test codec-preserving adaptation, not habitat profiles or real-world tree density. Native failed placements retained; no retries. Lowest-log direct supports include native mangrove roots. No client visuals.");
            Files.writeString(Path.of("tree-shapes.json"),G.toJson(report)+"\n");System.out.println("TREE_SHAPE_PASS attempts="+rows.size());
        }catch(Throwable e){System.err.println("TREE_SHAPE_FAIL");e.printStackTrace();}finally{shutdown=40;}});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
    private Map<String,Object> measure(ServerLevel level,BlockPos origin){
        int logs=0,leaves=0,roots=0,minLog=999,maxLog=-999,minLeaf=999,maxLeaf=-999,minX=999,maxX=-999,minZ=999,maxZ=-999;boolean boundary=false;
        List<List<Object>> blocks=new ArrayList<>();var at=new BlockPos.MutableBlockPos();List<BlockPos> lowest=new ArrayList<>();
        for(int dz=-RADIUS;dz<=RADIUS;dz++)for(int dx=-RADIUS;dx<=RADIUS;dx++)for(int dy=-12;dy<HEIGHT;dy++){
            at.set(origin.getX()+dx,origin.getY()+dy,origin.getZ()+dz);var state=level.getBlockState(at);
            String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();boolean log=state.is(BlockTags.LOGS),leaf=state.is(BlockTags.LEAVES),root=id.endsWith("mangrove_roots");
            if(!log&&!leaf&&!root)continue;boundary|=Math.abs(dx)==RADIUS||Math.abs(dz)==RADIUS||dy==HEIGHT-1||dy==-12;
            if(log){logs++;if(dy<minLog){minLog=dy;lowest.clear();}if(dy==minLog)lowest.add(at.immutable());maxLog=Math.max(maxLog,dy);}
            if(leaf){leaves++;minLeaf=Math.min(minLeaf,dy);maxLeaf=Math.max(maxLeaf,dy);minX=Math.min(minX,dx);maxX=Math.max(maxX,dx);minZ=Math.min(minZ,dz);maxZ=Math.max(maxZ,dz);}
            if(root)roots++;blocks.add(List.of(dx,dy,dz,id));
        }
        int unsupported=0;for(var pos:lowest){var below=level.getBlockState(pos.below());String id=BuiltInRegistries.BLOCK.getKey(below.getBlock()).toString();if(!below.isSolid()&&!id.endsWith("mangrove_roots")&&!below.is(BlockTags.LOGS))unsupported++;}
        Map<String,Object> out=new LinkedHashMap<>();out.put("logs",logs);out.put("leaves",leaves);out.put("roots",roots);out.put("height",logs+leaves==0?0:Math.max(maxLog,maxLeaf)+1);
        out.put("log_height",logs==0?0:maxLog-minLog+1);out.put("canopy_height",leaves==0?0:maxLeaf-minLeaf+1);out.put("canopy_width",leaves==0?0:maxX-minX+1);out.put("canopy_depth",leaves==0?0:maxZ-minZ+1);
        out.put("highest_log",logs==0?null:maxLog);out.put("highest_leaf",leaves==0?null:maxLeaf);out.put("directly_unsupported_lowest_logs",unsupported);out.put("touches_boundary",boundary);out.put("blocks",blocks);return out;
    }
}
