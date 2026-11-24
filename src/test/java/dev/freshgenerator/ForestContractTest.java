package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
class ForestContractTest {
 @Test void forestAndClimateResourcesAreUnchangedExceptTheAuditedCaveLayer()throws Exception {
  new CaveAccessContractTest().exactlyTwoExistingResourcesChangeAndEverythingElseIsPreserved();
  // Freeze the historical forest/cave stage; full catalog has its own strict regional contract.
  var p=WorldgenCatalog.compileBeforeBiomeRegions(new VanillaAssets());int n=0;
  try(var z=new ZipFile("dist/FreshGenerator-Fabric-26.3-0.2.4-alpha.1.jar")) {
   for(var e:Collections.list(z.entries())) {
    if(!e.getName().startsWith("data/")||!e.getName().endsWith(".json"))continue;
    if(!CaveAccessCompiler.REPLACED.contains(e.getName()))try(var in=z.getInputStream(e)) {
     var before=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
     assertEquals(before,JsonParser.parseString(ResourceJson.encode(p.get(e.getName()))),e.getName());
    }
    n++;
   }
  }
  assertEquals(334,n);
 }
 @Test void observationsExistBeforeProductionWork()throws Exception {
  var r=JsonParser.parseString(Files.readString(Path.of("research/forest-reference-statistics.json"))).getAsJsonObject();
  assertEquals("3316eca0ef691ee8717fb4421918dd95978b5560",r.get("upstream_commit").getAsString());var d=r.getAsJsonObject("datasets");
  assertEquals(2251,d.getAsJsonObject("lansing").getAsJsonObject("statistics").get("records").getAsInt());
  assertEquals(168,d.getAsJsonObject("hyytiala").getAsJsonObject("statistics").get("records").getAsInt());
  assertEquals(3604,d.getAsJsonObject("bei").getAsJsonObject("statistics").get("records").getAsInt());
 }
 @Test void adapterNeverWritesOrRequestsChunks()throws Exception {
  String s=Files.readString(Path.of("src/main/java/dev/freshgenerator/forest/ForestEnvironment.java"));
  for(String forbidden:List.of(".setBlock(",".getChunk(",".destroyBlock(",".nextInt(","ThreadLocal","static final Map"))assertFalse(s.contains(forbidden),forbidden);
 }
 @Test void sharedForestStateIsAPureMemo()throws Exception {
  // The only state shared between placers (audit 23.11 speed-up) memoizes position functions of one world: keys are
  // distinct per quart (negative coordinates, world border, every Y) and a value is the source's own.
  var keys=new java.util.HashSet<Long>();
  for(int qx:new int[]{-8_000_000,-1,0,1,7_999_999})for(int qz:new int[]{-8_000_000,-1,0,1,7_999_999})for(int qy=-16;qy<=80;qy++)
   assertTrue(keys.add(dev.freshgenerator.forest.ForestWorldMemoAccess.key(qx,qy,qz)),"key "+qx+","+qy+","+qz);
  String s=Files.readString(Path.of("src/main/java/dev/freshgenerator/forest/ForestWorldMemo.java"));
  for(String forbidden:List.of(".setBlock(",".getChunk(",".nextInt(","ThreadLocal"))assertFalse(s.contains(forbidden),forbidden);
 }
 @Test void nativePlacementAndOnePublicWorldAreRetained()throws Exception {
  String s=Files.readString(Path.of("src/main/java/dev/freshgenerator/mixin/ForestPlacementMixin.java"));
  assertTrue(s.contains("if(biomeCheck)"));assertTrue(s.contains("require=2"));assertTrue(s.contains("original.call(feature,level,generator,random,at)"));
  var p=WorldgenCatalog.compile(new VanillaAssets());
  assertEquals(new JsonArray(),p.get(WorldgenCatalog.NORMAL_TAG).getAsJsonObject().get("values"));
  assertEquals(p.get(VanillaAssets.path("world_preset",WorldgenCatalog.WORLD_ID)),p.get(DefaultWorldCompiler.NORMAL));
 }
}
