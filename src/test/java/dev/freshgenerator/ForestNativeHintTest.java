package dev.freshgenerator;
import com.google.gson.JsonParser;
import dev.freshgenerator.forest.ForestEnvironment;
import dev.freshgenerator.core.ForestComposition;
import dev.freshgenerator.core.ForestEcology.Family;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ForestNativeHintTest {
 @Test void biomeFamiliesIncludeSpecialForestsAndPreserveUnknownVegetation(){
  assertEquals(Family.CONIFER,ForestEnvironment.family("minecraft:grove"));
  for(String biome:new String[]{"savanna","savanna_plateau","windswept_savanna"})
   assertEquals(Family.DRY_WOODLAND,ForestEnvironment.family("minecraft:"+biome));
  for(String biome:new String[]{"plains","dark_forest","pale_garden","cherry_grove","dappled_forest"})
   assertEquals(Family.TEMPERATE,ForestEnvironment.family("minecraft:"+biome));
  for(String biome:new String[]{"bamboo_jungle","mangrove_swamp"})assertEquals(Family.TROPICAL,ForestEnvironment.family("minecraft:"+biome));
  for(String biome:new String[]{"desert","mushroom_fields","frozen_peaks","ocean","lush_caves"})assertNull(ForestEnvironment.family("minecraft:"+biome));
  assertNull(ForestEnvironment.family("other:forest"));
 }
 @Test void allSupportedNativePlacedFeaturesPassTheCheapHint()throws Exception {
  for(String name:ForestEnvironment.FEATURES){
   try(var in=getClass().getResourceAsStream("/data/minecraft/worldgen/placed_feature/"+name+".json")){
    assertNotNull(in,name);String hint=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().get("feature").getAsString();
    assertTrue(ForestEnvironment.hint(hint),name+" "+hint);
   }
  }
 }
 @Test void allUnderstoryFeaturesExistAndPassTheHint()throws Exception {
  for(String name:ForestEnvironment.UNDERSTORY){
   try(var in=getClass().getResourceAsStream("/data/minecraft/worldgen/placed_feature/"+name+".json")){
    assertNotNull(in,name);var root=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
    assertTrue(ForestEnvironment.hint(root.get("feature").getAsString()),name);
    assertTrue(root.getAsJsonArray("placement").toString().contains("minecraft:biome"),name);
   }
  }
 }
 @Test void replacementVariantsRetainNativeSurvivalAndCannotMoveOrMultiplyCandidates()throws Exception {
  for(String name:ForestComposition.VARIANTS){
   try(var in=getClass().getResourceAsStream("/data/minecraft/worldgen/placed_feature/"+name+".json")){
    assertNotNull(in,name);var root=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
    var placement=root.getAsJsonArray("placement");assertEquals(1,placement.size(),name);
    var filter=placement.get(0).getAsJsonObject();assertEquals("minecraft:block_predicate_filter",filter.get("type").getAsString(),name);
    assertEquals("minecraft:would_survive",filter.getAsJsonObject("predicate").get("type").getAsString(),name);
   }
  }
 }
}
