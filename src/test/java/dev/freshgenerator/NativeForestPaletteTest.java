package dev.freshgenerator;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.freshgenerator.forest.NativeForestPalette;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeForestPaletteTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static List<PlacementModifier> nativeModifiers(String id)throws Exception{
        try(var input=NativeForestPaletteTest.class.getResourceAsStream("/data/minecraft/worldgen/placed_feature/"+id+".json")){
            assertNotNull(input);var resource=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            var result=new ArrayList<PlacementModifier>();for(var entry:resource.getAsJsonArray("placement"))result.add(PlacementModifier.CODEC.parse(JsonOps.INSTANCE,entry).getOrThrow());return result;
        }
    }
    // These records are never placed: only their native feature kind and root
    // strategy are inspected while preserving the actual modifier objects.
    private static TreeFeature rawTree(){return new TreeFeature(null,null,null,null,Optional.empty(),null,List.of(),false,null);}
    @Test void nativeCherryDonorKeepsItsActualSurvivalAndWaterPredicates()throws Exception{
        var original=nativeModifiers("trees_cherry");var donor=new PlacedFeature(Holder.direct(rawTree()),original);
        var point=NativeForestPalette.atSourcePosition(donor);assertNotNull(point);assertSame(donor.feature(),point.feature());
        assertEquals(2,point.placement().size());
        for(var modifier:original)if(modifier instanceof BlockPredicateFilter||modifier instanceof SurfaceWaterDepthFilter)
            assertTrue(point.placement().stream().anyMatch(value->value==modifier));
        assertTrue(point.placement().stream().noneMatch(value->value instanceof CountPlacement||value instanceof InSquarePlacement||value instanceof HeightmapPlacement||value instanceof BiomeFilter));
        assertEquals(6,original.size());
    }
    @Test void reducedJungleAndConiferVariantsRetainTheirOwnSaplingPredicates()throws Exception{
        for(String name:List.of("jungle_tree","jungle_bush","mega_jungle_tree_checked","spruce_checked","pine_checked")){
            var original=nativeModifiers(name);var point=NativeForestPalette.atSourcePosition(new PlacedFeature(Holder.direct(rawTree()),original));
            assertNotNull(point,name);assertEquals(original.size(),point.placement().size());
            for(int i=0;i<original.size();i++)assertSame(original.get(i),point.placement().get(i),name);
        }
    }
    @Test void uncheckedRawTreesAndUnknownMovingLayoutsDeclineBorrowing()throws Exception{
        assertNull(NativeForestPalette.atSourcePosition(new PlacedFeature(Holder.direct(rawTree()),List.of(CountPlacement.of(2),InSquarePlacement.spread()))));
        PlacementModifier moving=new PlacementModifier(){
            public void modify(PlacementContext context,net.minecraft.util.RandomSource random,net.minecraft.core.BlockPos at,
                               java.util.function.Consumer<net.minecraft.core.BlockPos> consumer){consumer.accept(at.above());}
            public com.mojang.serialization.MapCodec<? extends PlacementModifier> codec(){return com.mojang.serialization.MapCodec.unit(this);}
        };
        var filters=new ArrayList<>(nativeModifiers("cherry_bees_005"));filters.add(moving);
        assertNull(NativeForestPalette.atSourcePosition(new PlacedFeature(Holder.direct(rawTree()),filters)));
    }
    @Test void nativeNestedCheckedSelectorRemainsCheckedWithoutDuplicatingOuterPlacement()throws Exception{
        var nested=new PlacedFeature(Holder.direct(rawTree()),nativeModifiers("jungle_tree"));
        var selector=new RandomSelectorFeature(List.of(),Holder.direct(nested));
        var donor=new PlacedFeature(Holder.direct(selector),nativeModifiers("trees_jungle"));
        var point=NativeForestPalette.atSourcePosition(donor);assertNotNull(point);assertSame(selector,point.feature().value());
        assertEquals(1,point.placement().size());assertInstanceOf(SurfaceWaterDepthFilter.class,point.placement().getFirst());
        var unsafe=new RandomSelectorFeature(List.of(),Holder.direct(new PlacedFeature(Holder.direct(rawTree()),List.of())));
        assertNull(NativeForestPalette.atSourcePosition(new PlacedFeature(Holder.direct(unsafe),nativeModifiers("trees_jungle"))));
    }
}
