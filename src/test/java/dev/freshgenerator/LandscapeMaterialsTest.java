package dev.freshgenerator;

import dev.freshgenerator.core.*;
import dev.freshgenerator.pack.*;
import dev.freshgenerator.surface.EcotonePolicy;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LandscapeMaterialsTest {
 @Test void flatModerateAndSteepShoresChooseDifferentSubstrates(){
  assertEquals(LandscapeMaterials.Cover.SAND,LandscapeMaterials.coast(64,63,0,0));
  assertEquals(LandscapeMaterials.Cover.GRAVEL,LandscapeMaterials.coast(64,63,.4,0));
  assertEquals(LandscapeMaterials.Cover.STONE,LandscapeMaterials.coast(64,63,1.2,0));
 }
 @Test void inlandAndDeepOceanRemainNative(){
  for(double height:new double[]{-48,30,50,84,120,280})for(double patch:new double[]{-1,0,1})
   assertEquals(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.coast(height,63,2,patch));
 }
 @Test void coastalMaterialBoundariesFormConnectedPatches(){
  int transitions=0,changesAtDistance=0;
  for(int x=-512;x<512;x++){
   var a=coast(x);if(a!=coast(x+1))transitions++;if(a!=coast(x+97))changesAtDistance++;
  }
  assertTrue(transitions>0);assertTrue(changesAtDistance>transitions*4);
 }
 private static LandscapeMaterials.Cover coast(int x){return LandscapeMaterials.coast(64,63,.72,EcotoneModel.sample(42,x,31,64,0).patch());}
 @Test void gentleContinuousShoreDoesNotPaintIntegerHeightContours(){
  int oldContourColumns=0;
  for(int x=-40;x<=40;x++)for(int z=-40;z<=40;z++){
   double height=gentleHeight(x,z);
   double slope=LandscapeMaterials.continuousSlope(gentleHeight(x-4,z),gentleHeight(x+4,z),
       gentleHeight(x,z-4),gentleHeight(x,z+4),4);
   assertEquals(.05,slope,1e-12);
   assertEquals(LandscapeMaterials.Cover.SAND,LandscapeMaterials.coast(height,63,slope,0));
   double roundedSlope=.5*Math.hypot(Math.floor(gentleHeight(x+1,z))-Math.floor(gentleHeight(x-1,z)),
       Math.floor(gentleHeight(x,z+1))-Math.floor(gentleHeight(x,z-1)));
   if(LandscapeMaterials.coast(height,63,roundedSlope,0)!=LandscapeMaterials.Cover.SAND)oldContourColumns++;
  }
  assertTrue(oldContourColumns>100,"Regression must include block steps that formerly changed the material");
 }
 private static double gentleHeight(int x,int z){return 63.5+.04*x+.03*z;}
 @Test void underwaterSlopeUsesGroundRatherThanLevelWater(){
  double slope=LandscapeMaterials.continuousSlope(57-4*.8,57+4*.8,57,57,4);
  assertEquals(.8,slope,1e-12);
  assertEquals(LandscapeMaterials.Cover.STONE,LandscapeMaterials.coast(57,63,slope,0));
  assertEquals(LandscapeMaterials.Cover.SAND,LandscapeMaterials.coast(57,63,0,0));
 }
 @Test void forestFloorRespondsToClimateRatherThanTheBiomeName(){
  int wetCool=0,dryWarm=0;EnumSet<LandscapeMaterials.Cover> covers=EnumSet.noneOf(LandscapeMaterials.Cover.class);
  for(int x=-1500;x<1500;x+=2){
   double patch=EcotoneModel.sample(42,x,31,90,0).patch();
   var cool=LandscapeMaterials.forest(90,63,.1,-.35,.5,patch);
   var dry=LandscapeMaterials.forest(90,63,.1,.6,-.8,patch);
   if(cool==LandscapeMaterials.Cover.PODZOL)wetCool++;
   if(dry==LandscapeMaterials.Cover.PODZOL)dryWarm++;
   covers.add(cool);covers.add(dry);
  }
  assertTrue(wetCool>dryWarm+100);assertTrue(covers.containsAll(Set.of(LandscapeMaterials.Cover.PODZOL,LandscapeMaterials.Cover.COARSE_DIRT,LandscapeMaterials.Cover.GRASS)));
 }
 @Test void alpineAndShoreForestFloorsKeepExistingPolicy(){
  for(int y:new int[]{60,63,71,144,260})assertEquals(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.forest(y,63,.1,0,.3,0));
 }
 @Test void woodedMesaSoilUsesMoistureAndShelteredGroundInsteadOfFixedY97(){
  assertEquals(LandscapeMaterials.Cover.GRASS,LandscapeMaterials.woodedBadlands(92.56,63,.1,.436,0));
  assertEquals(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.woodedBadlands(92.56,63,.1,-1,0));
  for(double height:new double[]{63,68,71,92,140,220})for(double patch:new double[]{-1,-.5,0,.5,1}){
   assertEquals(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.woodedBadlands(height,63,.8,1,patch));
   if(height<=71)assertEquals(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.woodedBadlands(height,63,0,1,patch));
  }
 }
 @Test void ruggedWoodedMesaRetainsMajorityNativeGeologyAndHasSomeRootablePatches(){
  int nativeCount=0,soilCount=0,total=0;
  for(int x=-512;x<512;x+=4)for(int s=0;s<=24;s++){
   double patch=EcotoneModel.sample(42,x,31,92.56,.617).patch();
   var cover=LandscapeMaterials.woodedBadlands(92.56,63,s*.05,.436,patch);
   if(cover==LandscapeMaterials.Cover.NATIVE)nativeCount++;else soilCount++;
   assertTrue(Set.of(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.Cover.GRASS,LandscapeMaterials.Cover.COARSE_DIRT).contains(cover));
   total++;
  }
  assertTrue(nativeCount>total*.5,"Steep and exposed terracotta must stay native");
  assertTrue(soilCount>total*.05,"Stable mesa ground must offer real rooting opportunities");
 }
 @Test void woodedMesaRootingOpportunityCannotIncreaseWithSlopeOrDecreaseWithMoisture(){
  for(double patch:new double[]{-.75,-.25,0,.25,.75}){
   boolean bare=false;
   for(int i=0;i<=100;i++){
    boolean soil=LandscapeMaterials.woodedBadlands(92,63,i*.01,.4,patch)!=LandscapeMaterials.Cover.NATIVE;
    if(!soil)bare=true;if(bare)assertFalse(soil);
   }
   boolean soilSeen=false;
   for(int i=-100;i<=100;i++){
    boolean soil=LandscapeMaterials.woodedBadlands(92,63,.25,i*.01,patch)!=LandscapeMaterials.Cover.NATIVE;
    if(soil)soilSeen=true;if(soilSeen)assertTrue(soil);
   }
  }
 }
 @Test void mesaAndCoastAdaptationsHaveExplicitDifferentBiomeScopes(){
  assertEquals(Set.of("minecraft:wooded_badlands"),EcotonePolicy.WOODED_MESAS);
  assertFalse(EcotonePolicy.COASTS.contains("minecraft:taiga"));
  assertFalse(EcotonePolicy.COASTS.contains("minecraft:forest"));
  assertTrue(EcotonePolicy.COASTS.containsAll(Set.of("minecraft:beach","minecraft:snowy_beach","minecraft:stony_shore")));
  assertTrue(Collections.disjoint(EcotonePolicy.COASTS,EcotonePolicy.FOREST_FLOORS));
 }
 @Test void nearVerticalGrassyWallsBecomeRockButHillsKeepTheirPalette(){
  for(double patch=-1;patch<=1;patch+=.05){
   assertEquals(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.cliff(90,63,1.0,patch),"45-degree hill keeps grass");
   assertEquals(LandscapeMaterials.Cover.STONE,LandscapeMaterials.cliff(101,63,7.0,patch),"0.9.1 audit wall (about 7 blocks per block)");
   assertEquals(LandscapeMaterials.Cover.NATIVE,LandscapeMaterials.cliff(66,63,7.0,patch),"shoreline keeps the coast policy");
   boolean rock=false;for(double s=0;s<=4;s+=.01){boolean now=LandscapeMaterials.cliff(120,63,s,patch)==LandscapeMaterials.Cover.STONE;if(now)rock=true;if(rock)assertTrue(now,"steeper never regains soil");}
  }
  assertTrue(EcotonePolicy.CLIFFS.containsAll(Set.of("minecraft:pale_garden","minecraft:dappled_forest","minecraft:ocean","minecraft:forest")));
  for(String kept:List.of("minecraft:beach","minecraft:stony_shore","minecraft:badlands","minecraft:wooded_badlands","minecraft:desert",
    "minecraft:snowy_plains","minecraft:frozen_ocean","minecraft:mangrove_swamp","minecraft:swamp"))assertFalse(EcotonePolicy.CLIFFS.contains(kept),kept);
  assertTrue(Collections.disjoint(EcotonePolicy.CLIFFS,EcotonePolicy.ALWAYS_SNOW));
 }
 @Test void invalidGeometryIsRejected(){
  assertThrows(IllegalArgumentException.class,()->LandscapeMaterials.coast(64,63,-1,0));
  assertThrows(IllegalArgumentException.class,()->LandscapeMaterials.forest(90,63,0,Double.NaN,0,0));
  assertThrows(IllegalArgumentException.class,()->LandscapeMaterials.woodedBadlands(92,63,.1,Double.NaN,0));
  assertThrows(IllegalArgumentException.class,()->LandscapeMaterials.continuousSlope(0,0,0,0,0));
  assertThrows(IllegalArgumentException.class,()->LandscapeMaterials.continuousSlope(Double.NaN,0,0,0,4));
 }
 @Test void currentMaterialsKeepHistoricalFallbackAndExplicitlyOptIn()throws Exception{
  var old=WorldgenCatalog.compileTerrain(new VanillaAssets());var now=WorldgenCatalog.compile(new VanillaAssets());
  for(String path:LandscapeSurfaceCompiler.BIOME_REPLACED){
   var before=old.get(path).getAsJsonObject().getAsJsonArray("sequence");var after=now.get(path).getAsJsonObject().getAsJsonArray("sequence");
   assertEquals(before.get(1),after.get(1));assertEquals(2,after.size());
   assertFalse(before.get(0).getAsJsonObject().has("landscape"));
   assertTrue(after.get(0).getAsJsonObject().get("landscape").getAsBoolean());
   assertEquals("freshgenerator:highlands_v2/vegetation",after.get(0).getAsJsonObject().get("moisture").getAsString());
  }
 }
 @Test void woodedMesaSubstrateRunsBeforeNativeY97AndCannotEscapeItsBiomeFloorGuard()throws Exception{
  var old=WorldgenCatalog.compileTerrain(new VanillaAssets());var now=WorldgenCatalog.compile(new VanillaAssets());
  JsonObject original=old.get(LandscapeSurfaceCompiler.SURFACE).deepCopy().getAsJsonObject();
  JsonObject current=now.get(LandscapeSurfaceCompiler.SURFACE).deepCopy().getAsJsonObject();
  JsonObject floor=current.getAsJsonArray("sequence").get(0).getAsJsonObject();
  assertEquals("minecraft:on_floor",floor.get("if_true").getAsString());
  JsonObject wooded=floor.getAsJsonObject("then_run").getAsJsonArray("sequence").get(0).getAsJsonObject();
  assertEquals("minecraft:wooded_badlands",wooded.getAsJsonObject("if_true").get("biome_is").getAsString());
  JsonArray patch=wooded.getAsJsonObject("then_run").getAsJsonArray("sequence");
  assertEquals(2,patch.size());
  assertEquals("freshgenerator:ecotone_surface",patch.get(0).getAsJsonObject().get("type").getAsString());
  assertTrue(patch.get(0).getAsJsonObject().get("landscape").getAsBoolean());
  assertEquals("freshgenerator:highlands_v2/vegetation",patch.get(0).getAsJsonObject().get("moisture").getAsString());
  assertEquals(97,patch.get(1).getAsJsonObject().getAsJsonObject("if_true").getAsJsonObject("anchor").get("absolute").getAsInt());
  // Removing precisely the new wrapper must restore the entire original rule,
  // including the other two badlands, swamp water, floor and subsurface branches.
  wooded.add("then_run",patch.get(1));
  // 0.9.1: the seabed rule sits first in the last (deep-water floor) branch; remove it too.
  var top=current.getAsJsonArray("sequence");var deep=top.get(top.size()-1).getAsJsonObject().getAsJsonObject("then_run").getAsJsonArray("sequence");
  assertEquals("freshgenerator:ecotone_surface",deep.get(0).getAsJsonObject().get("type").getAsString());
  assertFalse(deep.get(0).getAsJsonObject().get("subsurface").getAsBoolean());
  deep.remove(0);
  // Update 19.11: and the under-floor sediment body inserted just before that branch.
  assertEquals("minecraft:under_floor",top.get(top.size()-2).getAsJsonObject().get("if_true").getAsString());
  top.remove(top.size()-2);
  assertEquals(original,current);
 }
 @Test void invalidSecondMaterialDoesNotPartiallyEnableFirst(){
  Map<String,JsonElement> input=new TreeMap<>();
  for(String path:LandscapeSurfaceCompiler.REPLACED)input.put(path,JsonParser.parseString("{\"type\":\"minecraft:sequence\",\"sequence\":[{\"type\":\"freshgenerator:ecotone_surface\"},0]}"));
  input.put(LandscapeSurfaceCompiler.ROOT+"under_biome_surface.json",new JsonPrimitive(0));
  var before=input.toString();assertThrows(RuntimeException.class,()->LandscapeSurfaceCompiler.apply(input));assertEquals(before,input.toString());
 }
}
