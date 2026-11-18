package dev.freshgenerator;

import dev.freshgenerator.core.ForestComposition;
import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.core.ForestEcology.Family;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForestCompositionTest {
 private static final String FOREST="minecraft:forest",TAIGA="minecraft:taiga";
 private static ForestEcology.Site site(double moisture,double slope,double temperature) {
  return new ForestEcology.Site(90,63,slope,0,temperature,moisture,0,0);
 }
 private static String select(long seed,int x,String biome,String[] neighbours,ForestEcology.Site site) {
  return ForestComposition.select(seed,x,x*3,biome,neighbours,
      biome.contains("taiga")?Family.CONIFER:Family.TEMPERATE,site);
 }
 @Test void deterministicInEitherOrderAndParallel() {
  var site=site(.3,.1,0);var neighbours=new String[]{TAIGA,TAIGA,FOREST,FOREST};
  var serial=IntStream.range(-256,256).mapToObj(x->select(42,x,FOREST,neighbours,site)).toList();
  var parallel=IntStream.range(-256,256).parallel().mapToObj(x->select(42,x,FOREST,neighbours,site)).toList();
  assertEquals(serial,parallel);
  for(int i=511;i>=0;i--)assertEquals(serial.get(i),select(42,i-256,FOREST,neighbours,site));
 }
 @Test void interiorRetainsNativeChoicesAndDistinctTreeForms() {
  var forms=new HashSet<String>();int nativeCount=0;
  for(int x=-1000;x<1000;x++){String v=select(19,x,FOREST,new String[0],site(.5,.05,.2));if(v==null)nativeCount++;else forms.add(v);}
  assertTrue(nativeCount>700&&nativeCount<1100);
  assertTrue(forms.containsAll(List.of("birch_bees_0002_leaf_litter","oak_bees_0002_leaf_litter","fancy_oak_bees_0002_leaf_litter")));
 }
 @Test void CompatibleEdgeHasConifersWithoutReplacingInteriorForest() {
  int mixed=0,interior=0;var site=site(.4,.1,0);
  for(int x=-1000;x<1000;x++){
   String edge=select(29,x,FOREST,new String[]{TAIGA,TAIGA,TAIGA,TAIGA,TAIGA,TAIGA,TAIGA,TAIGA},site);
   String own=select(29,x,FOREST,new String[]{FOREST,FOREST,FOREST,FOREST},site);
   if(edge!=null&&(edge.equals("pine_checked")||edge.equals("spruce_checked")))mixed++;
   if(own!=null&&(own.equals("pine_checked")||own.equals("spruce_checked")))interior++;
  }
  assertTrue(mixed>600&&mixed<1000,"mixed="+mixed);assertEquals(0,interior);
 }
 @Test void DistantNeighboursHaveWeakerInfluence() {
  int near=0,far=0;var site=site(.4,.1,0);
  for(int x=-2000;x<2000;x++){
   String a=select(7,x,FOREST,new String[]{TAIGA,TAIGA,TAIGA,TAIGA},site);
   String b=select(7,x,FOREST,new String[]{FOREST,FOREST,FOREST,FOREST,TAIGA,TAIGA,TAIGA,TAIGA},site);
   if(a!=null&&a.endsWith("checked"))near++;
   if(b!=null&&b.endsWith("checked"))far++;
  }
  assertTrue(near>2*far&&far>150,"near="+near+" far="+far);
 }
 @Test void ColdAndSpecialBiomesCannotDonateForeignTrees() {
  for(String id:List.of("minecraft:desert","minecraft:jungle","minecraft:dark_forest","minecraft:pale_garden","minecraft:grove","minecraft:mangrove_swamp","mod:forest"))
   assertFalse(ForestComposition.compatible(FOREST,id,site(.2,.1,0)),id);
  assertFalse(ForestComposition.compatible(FOREST,"minecraft:snowy_taiga",site(.2,.1,0)));
  assertFalse(ForestComposition.compatible(FOREST,TAIGA,site(.2,.1,-.6)));
  assertFalse(ForestComposition.compatible(FOREST,TAIGA,new ForestEcology.Site(240,63,.1,0,0,.3,0,0)));
 }
 @Test void MoistShelteredForestHasMoreLargeFormsThanDrySteepForest() {
  int lush=0,harsh=0;
  for(int x=-1000;x<1000;x++){
   if("fancy_oak_bees_0002_leaf_litter".equals(select(42,x,FOREST,new String[0],site(.5,.1,.1))))lush++;
   if("fancy_oak_bees_0002_leaf_litter".equals(select(42,x,FOREST,new String[0],site(-1,1.2,.1))))harsh++;
  }
  assertTrue(lush>200);assertEquals(0,harsh);
 }
 @Test void NormalTaigaDoesNotBecomeGiantTreeForest() {
  for(int x=-1000;x<1000;x++){
   String v=select(42,x,TAIGA,new String[0],site(.5,.1,-.1));
   assertTrue(v==null||v.equals("pine_checked")||v.equals("spruce_checked"));
  }
 }
 @Test void OldGrowthNeighboursContributeOrdinaryConifersWithoutExportingGiantStands() {
  for(String biome:List.of(TAIGA,"minecraft:snowy_taiga",FOREST,"minecraft:birch_forest"))
   for(String donor:List.of("minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga")) {
    String[] neighbours=new String[8];Arrays.fill(neighbours,donor);
    int ordinaryConifers=0;
    for(int x=-1000;x<1000;x++) {
     String v=select(42,x,biome,neighbours,site(.5,.05,-.1));
     assertFalse(v!=null&&v.startsWith("mega_"),biome+" received "+v+" from "+donor);
     if("pine_checked".equals(v)||"spruce_checked".equals(v))ordinaryConifers++;
    }
    assertTrue(ordinaryConifers>100,"The compatible conifer transition was lost for "+biome);
   }
 }
 @Test void NativeOldGrowthRetainsItsGiantFormsAndNativeSelector() {
  for(String biome:List.of("minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga")) {
   int giants=0,nativeCount=0;
   for(int x=-1000;x<1000;x++) {
    String v=select(42,x,biome,new String[0],site(.5,.05,-.1));
    if(v==null)nativeCount++;
    else if(v.startsWith("mega_"))giants++;
   }
   assertTrue(giants>300,"Old-growth architecture was lost for "+biome);
   assertTrue(nativeCount>700&&nativeCount<1100,"Native rare forms were lost for "+biome);
  }
 }
 @Test void UnderstoryIsBoundedAndMoistureSensitive() {
  double wet=0,dry=0;int changes=0;
  for(int x=-400;x<400;x++)for(boolean fern:new boolean[]{false,true}){
   double a=ForestComposition.understory(42,x,77,Family.CONIFER,site(.5,.1,0),fern);
   double b=ForestComposition.understory(42,x,77,Family.CONIFER,site(-1,.1,0),fern);
   assertTrue(a>=0&&a<=1&&b>=0&&b<=a);wet+=a;dry+=b;
   if(ForestComposition.retainUnderstory(42,x,77,a)!=ForestComposition.retainUnderstory(42,x,77,b))changes++;
  }
  assertTrue(wet>dry*1.25);assertTrue(changes>100);
 }
 @Test void UnderstoryNoiseIsLocallyCorrelated() {
  double adjacent=0,distant=0;var site=site(.4,.1,0);
  for(int x=-512;x<512;x++){
   double a=ForestComposition.understory(11,x,49,Family.TEMPERATE,site,false);
   adjacent+=Math.abs(a-ForestComposition.understory(11,x+1,49,Family.TEMPERATE,site,false));
   distant+=Math.abs(a-ForestComposition.understory(11,x+67,49,Family.TEMPERATE,site,false));
  }
  assertTrue(adjacent*5<distant);
 }
}
