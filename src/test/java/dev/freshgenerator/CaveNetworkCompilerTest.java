package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveNetworkCompilerTest {
 static final String P=CaveNetworkCompiler.P,N=CaveNetworkCompiler.N;
 static Map<String,JsonElement> pack;
 /** Historical 0.6-0.8 cave network, compiled on its own: since 0.9.0 the current
  * world uses KarstCaveCompiler instead (see KarstCaveTest). */
 static Map<String,JsonElement> before()throws Exception{
  var old=new TreeMap<>(WorldgenCatalog.compileTerrain(new VanillaAssets()));
  SurfaceOnlyCompiler.apply(old);LandscapeTerrainCompiler.apply(old);LandscapeBiomeCompiler.apply(old);LandscapeSurfaceCompiler.apply(old);
  TerrainRealismCompiler.apply(old);return old;
 }
 @BeforeAll static void compile()throws Exception{var p=before();CaveNetworkCompiler.apply(p);DefaultWorldCompiler.apply(p);pack=p;}
 @Test void onlyCaveDensityAndDiagnosticSettingsChange()throws Exception{
  var old=before();DefaultWorldCompiler.apply(old);
  var changed=new TreeSet<String>();for(var e:old.entrySet())if(!e.getValue().equals(pack.get(e.getKey())))changed.add(e.getKey());
  assertEquals(CaveNetworkCompiler.REPLACED,changed);
  var s=old.get(CaveNetworkCompiler.SETTINGS).deepCopy().getAsJsonObject();s.remove("debug_functions");
  var t=pack.get(CaveNetworkCompiler.SETTINGS).deepCopy().getAsJsonObject();t.remove("debug_functions");assertEquals(s,t);
  for(var e:pack.entrySet())if(!old.containsKey(e.getKey()))assertTrue(e.getKey().contains("/cave_network/"),e.getKey());
 }
 @Test void ordinaryTerrainHasFortyBlocksOfCoverEvenIfCaveFieldIsAlwaysOpen(){
  for(double h:new double[]{27,45,63,80,110,170,280})for(double m:new double[]{0,.20,.35})for(double n:new double[]{-1,0,1}){
   var v=values(h,m,n);assertEquals(0,sample(N+"entrance_gate",v),1e-12);
   for(double y=Math.max(-53,h-39.9);y<h;y+=1){v.put("minecraft:y",y);assertTrue(sample(N+"final_body",v)>0,"Unprotected roof h="+h+" y="+y);}
  }
 }
 @Test void oceanAndLowlandsNeverUseMountainEntranceNoise(){
  for(int h=-48;h<=120;h+=4)for(double m:new double[]{0,.8,1}){
   var v=values(h,m,2);assertEquals(0,sample(N+"entrance_gate",v));
  }
 }
 @Test void persistentMountainAccessCanOpenSurfaceAndStillReachesDeepLevel(){
  var v=values(170,1,1);v.put(N+"access_footprint",-.08);assertEquals(1,sample(N+"entrance_gate",v));
  for(int y=-53;y<170;y++){v.put("minecraft:y",(double)y);assertTrue(sample(N+"final_body",v)<0,"Unexpected artificial plug "+y);}
 }
 @Test void broadMountainChambersCannotCutTheRoofWhenAccessFootprintIsClosed(){
  for(double h:new double[]{150,170,240,300}){
   var v=values(h,1,1);assertEquals(1,sample(N+"entrance_gate",v));
   assertEquals(Math.min(h-40,20),sample(N+"deep_ceiling",v));
   for(int y=(int)Math.floor(h)-39;y<h;y++){
    v.put("minecraft:y",(double)y);assertTrue(sample(N+"final_body",v)>0,"Broad roof breach "+h+"/"+y);
   }
  }
 }
 @Test void accessDoesNotCloseIntoAShallowPocketWhenThreeDimensionalFieldTurnsSolid(){
  var v=values(190,1,1);v.put(N+"access_footprint",-.08);
  for(int y=0;y<190;y++){
   v.put("minecraft:y",(double)y);v.put(N+"body",y==0?-1.:1.);
   assertTrue(sample(N+"final_body",v)<0,"Disconnected access above guaranteed Y0 junction: "+y);
  }
  v.put("minecraft:y",-5.);v.put(N+"body",1.);assertTrue(sample(N+"final_body",v)>0,"Access must stop below junction");
  v.put(P+"cave_access/entrance_noise",-1.);v.put("minecraft:y",100.);
  assertTrue(sample(N+"final_body",v)>0,"Ungated access");
 }
 @Test void accessUsesSameFourBlockGridAndYZeroNoiseSliceAsDeeperLinks(){
  var access=pack.get(path(N+"access_footprint")).getAsJsonObject();
  var body=pack.get(path(N+"body")).getAsJsonObject();
  for(String axis:List.of("cell_size_xz","cell_size_y"))assertEquals(body.get(axis),access.get(axis));
  var thin=access.getAsJsonObject("input");var wide=pack.get(path(N+"links")).getAsJsonObject();
  assertTrue(thin.get("right").getAsDouble()<wide.get("right").getAsDouble());
  for(String side:List.of("left","right")){
   var a=thin.getAsJsonObject("left").getAsJsonObject(side).getAsJsonObject("input");
   var b=wide.getAsJsonObject("left").getAsJsonObject(side).getAsJsonObject("input");
   assertEquals(a.get("noise"),b.get("noise"));assertEquals(a.get("xz_scale"),b.get("xz_scale"));
   assertEquals(0,a.get("y_scale").getAsDouble());
  }
 }
 @Test void bottomIsSealedAndAboveEnvelopeRemainsAir(){
  for(double h:new double[]{26,80,170,280})for(double m:new double[]{0,1}){
   var v=values(h,m,1);
   for(int y=-64;y<-54;y++){v.put("minecraft:y",(double)y);assertTrue(sample(N+"final_body",v)>0);}
   for(int y=(int)Math.ceil(h)+1;y<=319;y++){v.put("minecraft:y",(double)y);assertTrue(sample(N+"final_body",v)<0);}
  }
 }
 @Test void gateFadesWithoutLoweringAccessCeilingAndLeavingFloatingRoofCaps(){
  var v=values(168.0069,1,-.1);v.put(N+"access_footprint",-.08);double last=sample(N+"entrance_gate",v);
  for(int i=1;i<=1000;i++){
   v.put(P+"cave_access/entrance_noise",-.1+i*.0007);double gate=sample(N+"entrance_gate",v);
   assertTrue(gate>=last-1e-9&&gate-last<.02);last=gate;
   assertEquals(168.0069,sample(N+"ceiling",v));
   if(gate>.5)for(int y=21;y<=168;y++){
    v.put("minecraft:y",(double)y);assertTrue(sample(N+"final_body",v)<0,"Floating partial roof at gate "+gate+"/"+y);
   }
  }
 }
 @Test void independentCarversRemainOffAndNativeStructureFitRemainsOutsideCaveMin(){
  assertEquals(1,pack.get(path(SurfaceOnlyCompiler.DISABLED_FUNCTION)).getAsDouble());
  var f=pack.get(SurfaceOnlyCompiler.FINAL_DENSITY).getAsJsonObject();assertEquals("minecraft:beardifier",f.getAsJsonObject("right").get("type").getAsString());
 }
 @Test void repeatedApplicationIsAtomic(){var p=new TreeMap<>(pack);assertThrows(IllegalStateException.class,()->CaveNetworkCompiler.apply(p));assertEquals(pack,p);}
 static Map<String,Double> values(double h,double m,double noise){return new HashMap<>(Map.of(P+"coherent/height",h,P+"landscape/mountain",m,P+"cave_access/entrance_noise",noise,N+"body",-1.,N+"access_footprint",1.,"minecraft:y",0.));}
 static String path(String id){return VanillaAssets.path("density_function",id);}
 static double sample(String id,Map<String,Double> v){return v.containsKey(id)?v.get(id):eval(pack.get(path(id)),v);}
 static double eval(JsonElement n,Map<String,Double> v){
  if(n.isJsonPrimitive())return n.getAsJsonPrimitive().isNumber()?n.getAsDouble():sample(n.getAsString(),v);
  var o=n.getAsJsonObject();String t=o.get("type").getAsString();
  if(t.equals("minecraft:cache"))return eval(o.get("input"),v);
  if(t.equals("minecraft:clamp"))return Math.clamp(eval(o.get("input"),v),o.get("min").getAsDouble(),o.get("max").getAsDouble());
  if(t.equals("minecraft:squeeze")){double x=Math.clamp(eval(o.get("input"),v),-1,1);return x/2-x*x*x/24;}
  double a=eval(o.get("left"),v),b=eval(o.get("right"),v);
  return switch(t){case "minecraft:add"->a+b;case "minecraft:sub"->a-b;case "minecraft:mul"->a*b;case "minecraft:min"->Math.min(a,b);case "minecraft:max"->Math.max(a,b);default->throw new AssertionError(t);};
 }
}
