package dev.freshgenerator;

import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Native picker controls, not terrain or real-world adjacency simulation. */
class NativeBiomeParameterWalkTest {
    private static final int STEPS=65;

    private static List<Pair<Climate.ParameterPoint,String>> nativeEntries()throws Exception{
        List<Pair<Climate.ParameterPoint,ResourceKey<Biome>>> definitions=new ArrayList<>();
        var method=OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes",Consumer.class);
        method.setAccessible(true);
        Consumer<Pair<Climate.ParameterPoint,ResourceKey<Biome>>> consumer=definitions::add;
        method.invoke(new OverworldBiomeBuilder(),consumer);
        return definitions.stream().map(p->Pair.of(p.getFirst(),p.getSecond().identifier().toString())).toList();
    }
    private static long middle(Climate.Parameter p){return p.min()+(p.max()-p.min())/2;}
    private static Climate.TargetPoint midpoint(Climate.ParameterPoint p){return new Climate.TargetPoint(
        middle(p.temperature()),middle(p.humidity()),middle(p.continentalness()),middle(p.erosion()),middle(p.depth()),middle(p.weirdness()));}
    private static long[] coordinates(Climate.TargetPoint p){return new long[]{p.temperature(),p.humidity(),p.continentalness(),p.erosion(),p.depth(),p.weirdness()};}
    private static Climate.TargetPoint interpolate(Climate.TargetPoint a,Climate.TargetPoint b,int step){
        long[] left=coordinates(a),right=coordinates(b),result=new long[6];
        for(int axis=0;axis<6;axis++)result[axis]=Math.round(left[axis]+(right[axis]-left[axis])*(step/(double)(STEPS-1)));
        return new Climate.TargetPoint(result[0],result[1],result[2],result[3],result[4],result[5]);
    }
    private static Map<String,Double> normalized(Climate.TargetPoint p){
        String[] names={"temperature","moisture","continentalness","erosion","depth","weirdness"};
        long[] values=coordinates(p);var out=new LinkedHashMap<String,Double>();
        for(int i=0;i<names.length;i++)out.put(names[i],(double)Climate.unquantizeCoord(values[i]));
        return out;
    }
    private static long fitness(List<Pair<Climate.ParameterPoint,String>> entries,Climate.TargetPoint point,String id){
        long result=Long.MAX_VALUE;
        for(var e:entries)if(e.getSecond().equals(id))result=Math.min(result,e.getFirst().fitness(point));
        return result;
    }
    private static List<Map<String,Object>> compressed(String[] sequence){
        var out=new ArrayList<Map<String,Object>>();int start=0;
        for(int i=1;i<=sequence.length;i++)if(i==sequence.length||!sequence[i].equals(sequence[start])){
            out.add(Map.of("biome",sequence[start],"start_step",start,"end_step",i-1));start=i;
        }
        return out;
    }
    @Test void everyNativeOverworldWitnessAndEveryEndpointPairHasAnExplicitPickerPath()throws Exception{
        var entries=nativeEntries();assertFalse(entries.isEmpty());
        var declared=new TreeSet<String>();entries.forEach(p->declared.add(p.getSecond()));
        assertEquals(56,declared.size(),"Version-pinned actual native inventory changed; update the complete audit instead of dropping IDs");
        var witnessIndex=new Climate.ParameterList<>(List.copyOf(entries));
        var witnesses=new TreeMap<String,Climate.TargetPoint>();
        for(var e:entries){
            String id=e.getSecond();if(witnesses.containsKey(id))continue;
            var point=midpoint(e.getFirst());if(!witnessIndex.findValue(point).equals(id))continue;
            long own=fitness(entries,point,id),other=Long.MAX_VALUE;
            for(var q:entries)if(!q.getSecond().equals(id))other=Math.min(other,q.getFirst().fitness(point));
            // Strict witnesses prevent the native last-leaf cache from changing
            // either endpoint when another pair was queried just before it.
            if(own<other)witnesses.put(id,point);
        }
        var missing=new TreeSet<>(declared);missing.removeAll(witnesses.keySet());
        assertEquals(Set.of(),missing,"Every native ID needs its own history-independent climate witness");
        var forwardIndex=new Climate.ParameterList<>(List.copyOf(entries));
        var reverseIndex=new Climate.ParameterList<>(List.copyOf(entries));
        var names=List.copyOf(declared);var results=new ArrayList<Map<String,Object>>();
        var encountered=new TreeSet<String>();var observedParameterPairs=new TreeSet<String>();int tieSensitiveSteps=0;
        for(int ai=0;ai<names.size();ai++)for(int bi=ai+1;bi<names.size();bi++){
            String a=names.get(ai),b=names.get(bi);var left=witnesses.get(a);var right=witnesses.get(b);
            String[] forward=new String[STEPS],reverse=new String[STEPS];
            for(int step=0;step<STEPS;step++)forward[step]=forwardIndex.findValue(interpolate(left,right,step));
            for(int step=STEPS-1;step>=0;step--)reverse[step]=reverseIndex.findValue(interpolate(left,right,step));
            assertEquals(a,forward[0]);assertEquals(b,forward[STEPS-1]);
            assertEquals(a,reverse[0]);assertEquals(b,reverse[STEPS-1]);
            var differences=new ArrayList<Integer>();
            for(int step=0;step<STEPS;step++){
                assertTrue(declared.contains(forward[step]));assertTrue(declared.contains(reverse[step]));encountered.add(forward[step]);encountered.add(reverse[step]);
                if(step>0&&!forward[step].equals(forward[step-1])){
                    String x=forward[step],y=forward[step-1];observedParameterPairs.add(x.compareTo(y)<0?x+"|"+y:y+"|"+x);
                }
                if(!forward[step].equals(reverse[step])){
                    var target=interpolate(left,right,step);long f=fitness(entries,target,forward[step]),r=fitness(entries,target,reverse[step]);
                    assertEquals(f,r,"Reverse traversal disagreement must be an exact native fitness tie");
                    long minimum=entries.stream().mapToLong(e->e.getFirst().fitness(target)).min().orElseThrow();
                    assertEquals(minimum,f,"Native result must still be a global nearest parameter region");
                    differences.add(step);tieSensitiveSteps++;
                }
            }
            var delta=new LinkedHashMap<String,Double>();
            var first=normalized(left);var last=normalized(right);first.forEach((key,value)->delta.put(key,last.get(key)-value));
            results.add(Map.of("a",a,"b",b,"start",first,"end",last,"endpoint_delta",delta,
                "forward_parameter_regions",compressed(forward),"reverse_parameter_regions",compressed(reverse),"exact_tie_sensitive_steps",differences));
        }
        assertEquals(1540,results.size());assertEquals(declared,encountered);
        StringBuilder table=new StringBuilder();entries.forEach(e->table.append(e.getSecond()).append(' ').append(e.getFirst()).append('\n'));
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(table.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var witnessReport=new TreeMap<String,Object>();witnesses.forEach((id,p)->witnessReport.put(id,normalized(p)));
        var report=new LinkedHashMap<String,Object>();report.put("scope","PARAMETER SPACE ONLY: native 26.3 picker over synthetic interpolated climate vectors. This does NOT prove real spatial adjacency, actual terrain, boundary widths, climate-manifold reachability, or vegetation.");
        report.put("native_builder","net.minecraft.world.level.biome.OverworldBiomeBuilder.addBiomes");report.put("native_parameter_entries",entries.size());report.put("native_parameter_table_fingerprint",fingerprint);
        report.put("declared_biomes",declared);report.put("history_independent_native_witnesses",witnessReport);report.put("unordered_endpoint_pairs",results.size());report.put("steps_per_direction",STEPS);
        report.put("native_queries_both_directions",results.size()*STEPS*2);report.put("exact_tie_sensitive_steps",tieSensitiveSteps);report.put("sampled_parameter_region_pairs",observedParameterPairs);
        report.put("pairs",results);report.put("acceptance","All native endpoint IDs remain reachable; all pair paths were tested forward/backward. Equal-fitness native tie sensitivity is reported separately from a failure.");
        Path output=Path.of("build/reports/native-biome-parameter-walk.json");Files.createDirectories(output.getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
    }
}
