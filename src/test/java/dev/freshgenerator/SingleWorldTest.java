package dev.freshgenerator;
import com.google.gson.*;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
class SingleWorldTest {
 private static Map<String,JsonElement> catalog()throws Exception{return WorldgenCatalog.compile(new VanillaAssets());}
 @Test void freshGeneratorReplacesDefaultWithoutAddingAnotherSelectorEntry()throws Exception{var t=catalog().get(WorldgenCatalog.NORMAL_TAG).getAsJsonObject();assertFalse(t.get("replace").getAsBoolean());assertEquals(new JsonArray(),t.get("values"));}
 @Test void nativeNewWorldDefaultUsesTheSameDimensionsAsFreshGenerator()throws Exception{var p=catalog();assertEquals(p.get(VanillaAssets.path("world_preset",WorldgenCatalog.WORLD_ID)),p.get(DefaultWorldCompiler.NORMAL));assertEquals("freshgenerator:highlands_v2",p.get(DefaultWorldCompiler.NORMAL).getAsJsonObject().getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").get("settings").getAsString());assertFalse(p.containsKey("data/minecraft/worldgen/noise_settings/overworld.json"),"Existing vanilla save settings must not be overwritten");}
 @Test void currentWorldIncludesEveryDimensionAndTheLatestTerrainImprovements()throws Exception{var f=catalog();var w=f.get(VanillaAssets.path("world_preset",WorldgenCatalog.WORLD_ID));assertEquals(f.get(VanillaAssets.path("world_preset",WorldgenCatalog.CURRENT_SETTINGS)),w);var d=w.getAsJsonObject().getAsJsonObject("dimensions");assertEquals(Set.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end"),d.keySet());assertEquals(WorldgenCatalog.CURRENT_SETTINGS,d.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").get("settings").getAsString());assertTrue(f.containsKey(CoherentSurfaceCompiler.ROOT+"coherent/height.json"));}
 @Test void onlyAuditedActiveTerrainResourcesChangeAndLegacyResourcesRemainIntact()throws Exception{new EcotoneCompilerTest().onlyThreeExistingResourcesChangeComparedToPublishedTerrainFix();}
 @Test void noOtherSelectionTagReintroducesLegacyModes()throws Exception{var f=catalog();assertEquals(List.of(WorldgenCatalog.NORMAL_TAG),f.keySet().stream().filter(p->p.contains("/tags/worldgen/world_preset/")).toList());String t=f.get(WorldgenCatalog.NORMAL_TAG).toString();for(String n:List.of("balanced","highlands","_v1","_v2"))assertFalse(t.contains(n));}
 @Test void legacyFreshGeneratorNameRemainsTranslated()throws Exception{for(String l:List.of("en_us","ru_ru"))try(var in=getClass().getResourceAsStream("/assets/freshgenerator/lang/"+l+".json")){assertNotNull(in);var j=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();assertEquals(Set.of("generator.freshgenerator.world"),j.keySet());assertEquals("FreshGenerator",j.get("generator.freshgenerator.world").getAsString());}}
 @Test void rarityAndOtherModsRegistriesAreNotReplaced()throws Exception{for(String p:catalog().keySet()){if(OceanStructureTest.REVIEWED.contains(p)||HydrologyCompiler.DECOR.contains(p)||VillageStructureCompiler.SETS.contains(p)||VillageStructureCompiler.FEATURES.contains(p)||VolcanicVentCompiler.FILES.contains(p))continue;assertTrue(p.startsWith("data/freshgenerator/")||p.equals(WorldgenCatalog.NORMAL_TAG)||p.equals(DefaultWorldCompiler.NORMAL),p);for(String r:List.of("biome","structure","structure_set","placed_feature","feature"))assertFalse(p.contains("/worldgen/"+r+"/"),p);}}
 @Test void catalogIsDeterministicAndPublishedMapCannotBeMutated()throws Exception{var f=catalog();assertEquals(f,catalog());assertThrows(UnsupportedOperationException.class,f::clear);}
}
