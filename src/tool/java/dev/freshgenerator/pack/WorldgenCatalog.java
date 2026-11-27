package dev.freshgenerator.pack;
import com.google.gson.*;
import dev.freshgenerator.core.TerrainProfile;
import java.io.IOException;
import java.util.*;
/** One public world. Old keys are hidden save compatibility, not extra modes. */
public final class WorldgenCatalog {
 public static final String WORLD_ID="freshgenerator:world",CURRENT_SETTINGS="freshgenerator:highlands_v2";
 public static final String NORMAL_TAG="data/minecraft/tags/worldgen/world_preset/normal.json";
 private WorldgenCatalog() { }
 public static Map<String,JsonElement> compile(VanillaAssets vanilla)throws IOException {
  Map<String,JsonElement> out=new TreeMap<>(compileTerrain(vanilla));
  SurfaceOnlyCompiler.apply(out);
  LandscapeTerrainCompiler.apply(out);
  LandscapeBiomeCompiler.apply(out);
  LandscapeSurfaceCompiler.apply(out);
  TerrainRealismCompiler.apply(out);
  BiomeReliefCompiler.apply(out);
  ClimateNicheCompiler.apply(out);
  // 0.9.1: external-audit repairs (giant flanks, coastal walls, biome depth).
  TerrainRepairCompiler.apply(out);
  // 0.9.1 (audit R4): montane forest instead of savanna on the cool side of the snow line.
  HighlandClimateCompiler.apply(out);
  // 0.9.1 (user request): great massifs in the cores of large cold regions.
  SnowMassifCompiler.apply(out);
  // 0.10 (user request 26.11): continuous mountain ranges instead of single mountains in the plains.
  MountainRangeCompiler.apply(out);
  // 0.10 (user request 26.11): wider deserts with dunes, tablelands and inselbergs.
  DesertCompiler.applyRelief(out);
  // 0.9.1: ocean floor provinces, seabed palette and fewer, adapted ocean structures.
  OceanFloorCompiler.apply(out);
  OceanStructureCompiler.apply(out, vanilla);
  // 0.10 (user request 24.11): fewer villages without clusters, roads between some of them.
  VillageStructureCompiler.apply(out, vanilla);
  // 0.10 (user request 26.11): volcanic vents instead of the native surface lava lakes.
  VolcanicVentCompiler.apply(out);
  // 0.9.2: hot dry lowland just below sea level is a dry pan, not 1-2 blocks of water over sand.
  DryLandCompiler.apply(out);
  // 0.10 (user request 26.11): rare volcanoes and impact craters over the finished dry-land height.
  LandformCompiler.apply(out);
  // 0.9.0: karst cave system. CaveNetworkCompiler remains for history/tests only.
  KarstCaveCompiler.apply(out);
  // 0.10 (user request 26.11): deeper caves of varied kinds - levels that come and go, spongework, canyons, great chambers.
  SpeleoCaveCompiler.apply(out);
  // 0.10 (user request 23.11): underground climate follows the surface, meandering climate borders, island climate.
  ClimateShapeCompiler.apply(out);
  DesertCompiler.applyClimate(out);
  // 0.10 (user request 26.11): snow and forest borders as patchy ecotones, not ruled lines.
  EcotoneBorderCompiler.apply(out);
  // 0.10 (user request 22.11): water on land - drainage network, lakes, waterfalls; no native river loops.
  HydrologyCompiler.apply(out);
  // 0.10 (user request 26.11): the world reaches 48 blocks deeper (Y-112).
  DeepWorldCompiler.apply(out, vanilla);
  DefaultWorldCompiler.apply(out);
  return Collections.unmodifiableMap(out);
 }
 /** Original 0.3.6 terrain, before the current cave opt-out. */
 public static Map<String,JsonElement> compileTerrain(VanillaAssets vanilla)throws IOException {
  Map<String,JsonElement> out=new TreeMap<>(compileBeforeMountainDetails(vanilla));
  MountainSurfaceDetailCompiler.apply(out);
  return Collections.unmodifiableMap(out);
 }
 /** Frozen 0.3.5 output for historical tests and the separate surface-detail audit. */
 public static Map<String,JsonElement> compileBeforeMountainDetails(VanillaAssets vanilla)throws IOException {
  Map<String,JsonElement> out=new TreeMap<>(compileBeforeBiomeRegions(vanilla));
  BiomeRegionCompiler.apply(out);
  return Collections.unmodifiableMap(out);
 }
 /** Frozen 0.3.1 compiler stage for historical layer regression tests. Not a world preset. */
 public static Map<String,JsonElement> compileBeforeBiomeRegions(VanillaAssets vanilla)throws IOException {
  Objects.requireNonNull(vanilla);Map<String,JsonElement> out=new TreeMap<>();
  out.putAll(new WorldgenCompiler(vanilla,"balanced_v1",TerrainProfile.BALANCED).compile());
  out.putAll(new WorldgenCompiler(vanilla,"highlands_v1",TerrainProfile.HIGHLANDS).compile());
  out.putAll(new NaturalWorldgenCompiler(vanilla,"balanced_v2",TerrainProfile.BALANCED).compile());
  out.putAll(new NaturalWorldgenCompiler(vanilla,"highlands_v2",TerrainProfile.HIGHLANDS).compile());
  CoherentSurfaceCompiler.apply(out);EcotoneCompiler.apply(out);CaveAccessCompiler.apply(out);
  out.put(VanillaAssets.path("world_preset",WORLD_ID),Objects.requireNonNull(out.get(VanillaAssets.path("world_preset",CURRENT_SETTINGS))).deepCopy());
  JsonArray values=new JsonArray();values.add(WORLD_ID);JsonObject tag=new JsonObject();tag.addProperty("replace",false);tag.add("values",values);out.put(NORMAL_TAG,tag);
  return Collections.unmodifiableMap(out);
 }
}
