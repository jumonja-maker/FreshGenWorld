package dev.freshgenerator;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.freshgenerator.forest.NativeForestCount;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeForestCountTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static List<PlacementModifier> nativeModifiers(String id)throws Exception{
        try(var input=NativeForestCountTest.class.getResourceAsStream("/data/minecraft/worldgen/placed_feature/"+id+".json")){
            assertNotNull(input);var resource=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            var result=new ArrayList<PlacementModifier>();for(var entry:resource.getAsJsonArray("placement"))result.add(PlacementModifier.CODEC.parse(JsonOps.INSTANCE,entry).getOrThrow());return result;
        }
    }
    @Test void actualNativeWeightedMeansPreserveRareOpenAndDenseForestCapacities()throws Exception{
        for(var entry:Map.of("trees_plains",.05,"trees_snowy",.1,"trees_taiga",10.1,"trees_jungle",50.1,"trees_savanna",1.1,"trees_meadow",.01).entrySet()){
            var count=NativeForestCount.inspect(nativeModifiers(entry.getKey()));assertNotNull(count,entry.getKey());assertEquals(entry.getValue(),count.mean(),1e-12,entry.getKey());
        }
    }
    @Test void sharedProposalsKeepEveryNativeFilterObjectAndOriginalListUntouched()throws Exception{
        for(String name:List.of("trees_plains","trees_snowy","trees_taiga","trees_meadow")){
            var original=nativeModifiers(name);var first=original.getFirst();var count=NativeForestCount.inspect(original);var changed=count.proposals(original,11);
            assertSame(first,original.getFirst());assertNotSame(first,changed.getFirst());assertEquals(original.size(),changed.size());
            for(int i=1;i<original.size();i++)assertSame(original.get(i),changed.get(i),name+" filter "+i);
        }
    }
    @Test void unknownOrNestedLayoutsPassThroughInsteadOfGuessingCapacity(){
        assertNull(NativeForestCount.inspect(List.of(InSquarePlacement.spread(),CountPlacement.of(4))));
        assertNull(NativeForestCount.inspect(List.of(CountPlacement.of(4),InSquarePlacement.spread(),CountPlacement.of(2))));
        assertNull(NativeForestCount.inspect(List.of()));
    }
}
