package dev.freshgenerator.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.*;
import java.util.*;

/** Audit-only CPU/heap/time around native FULL requests, excluding exports/startup.
 * A bounded local benchmark on the user's shared PC, not an isolated laboratory.
 */
public final class NativeGenerationBenchmark implements ModInitializer {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private record Site(String name,int x,int z,int side) { }
    private int shutdown=-1;
    @Override public void onInitialize(){
        if(!"true".equals(System.getenv("FG_NATIVE_GENERATION_BENCHMARK")))return;
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try {
                ServerLevel level=server.overworld();
                var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
                if(!generator.generatorSettings().unwrapKey().orElseThrow().identifier().toString().equals("freshgenerator:highlands_v2"))throw new AssertionError("Wrong generator");
                Site site=G.fromJson(Files.readString(Path.of(Objects.requireNonNull(System.getenv("FG_BENCHMARK_SITE")))),Site.class);
                if(site.side()!=32)throw new AssertionError("One bounded32-block core required");
                int x0=Math.floorDiv(site.x()-16,16),z0=Math.floorDiv(site.z()-16,16);
                // A separate signed-coordinate patch warms common native/JIT paths.
                // These chunks are never included in the timed target or its halo.
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)level.getChunk(x0+256+dx,z0+256+dz);
                int already=0;for(int z=z0-1;z<=z0+2;z++)for(int x=x0-1;x<=x0+2;x++)if(level.hasChunk(x,z))already++;
                if(already!=0)throw new AssertionError("Target FULL chunks already loaded: "+already);
                for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)pool.resetPeakUsage();
                long beforeHeap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                double cpu0=ProcessHandle.current().info().totalCpuDuration().map(d->d.toNanos()/1e9).orElse(-1.);
                long start=System.nanoTime();int requested=0;
                for(int z=z0-1;z<=z0+2;z++)for(int x=x0-1;x<=x0+2;x++){level.getChunk(x,z);requested++;}
                double milliseconds=(System.nanoTime()-start)/1e6;
                double cpu1=ProcessHandle.current().info().totalCpuDuration().map(d->d.toNanos()/1e9).orElse(-1.);
                long afterHeap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                long peak=ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();
                var result=new LinkedHashMap<String,Object>();result.put("seed",level.getSeed());result.put("site",site);
                result.put("requested_full_chunks",requested);result.put("preloaded_target_chunks",already);result.put("warmup_chunks",4);
                result.put("generation_milliseconds",milliseconds);result.put("milliseconds_per_requested_full_chunk",milliseconds/requested);
                result.put("process_cpu_seconds",cpu0>=0&&cpu1>=0?cpu1-cpu0:-1.);result.put("heap_before_bytes",beforeHeap);
                result.put("heap_after_bytes",afterHeap);result.put("phase_heap_pool_peak_sum_bytes",peak);
                result.put("scope","Native synchronous FULL requests and their asynchronous dependencies; startup, 4 warmup chunks, CSV/block export and save/reload are excluded. Additional native dependency chunks may be generated, so per-request figure is not isolated per-chunk CPU. Heap-pool peak sum is not process RSS. Shared Windows host, no user process terminated; paired measurements are indicative.");
                Files.writeString(Path.of("native-generation-benchmark.json"),G.toJson(result)+"\n");
                System.out.println("NATIVE_GENERATION_BENCHMARK_PASS chunks="+requested+" ms="+milliseconds);
            }catch(Throwable failure){System.err.println("NATIVE_GENERATION_BENCHMARK_FAIL");failure.printStackTrace();}
            finally{shutdown=20;}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{if(shutdown>0&&--shutdown==0)server.halt(false);});
    }
}
