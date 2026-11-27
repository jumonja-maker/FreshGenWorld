package dev.freshgenerator;

import dev.freshgenerator.surface.EcotoneSurfaceRule;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Standalone native registry-driven generator. No third-party generator dependency. */
public final class FreshGenerator implements ModInitializer {
    @Override public void onInitialize() {
        Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE,Identifier.parse("freshgenerator:ecotone_surface"),EcotoneSurfaceRule.CODEC);
        Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE,dev.freshgenerator.landform.LandformSurfaceRule.ID,dev.freshgenerator.landform.LandformSurfaceRule.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.hydrology.HydrologyDensity.ID,dev.freshgenerator.hydrology.HydrologyDensity.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.climate.IslandClimateDensity.ID,dev.freshgenerator.climate.IslandClimateDensity.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.climate.LatticeDensity.ID,dev.freshgenerator.climate.LatticeDensity.CODEC);
        // Landforms of the current world (user request 26.11): dunes of the sand seas.
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.landform.DuneDensity.ID,dev.freshgenerator.landform.DuneDensity.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.landform.LandformDensity.ID,dev.freshgenerator.landform.LandformDensity.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.landform.CaveRoomDensity.ID,dev.freshgenerator.landform.CaveRoomDensity.CODEC);
        Registry.register(BuiltInRegistries.FEATURE_TYPE,dev.freshgenerator.hydrology.HydroDecor.ID,dev.freshgenerator.hydrology.HydroDecor.CODEC);
        net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInOverworld(),
            net.minecraft.world.level.levelgen.GenerationStep.Decoration.VEGETAL_DECORATION,
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.PLACED_FEATURE,dev.freshgenerator.hydrology.HydroDecor.ID));
        // Roads between some villages (user request 24.11): after the village pieces, before trees and plants.
        Registry.register(BuiltInRegistries.FEATURE_TYPE,dev.freshgenerator.road.VillageRoads.ID,dev.freshgenerator.road.VillageRoads.CODEC);
        net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInOverworld(),
            net.minecraft.world.level.levelgen.GenerationStep.Decoration.SURFACE_STRUCTURES,
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.PLACED_FEATURE,dev.freshgenerator.road.VillageRoads.ID));
        // Volcanic vents instead of the native surface lava lakes (user request 26.11): cones in deserts, pits in badlands.
        Registry.register(BuiltInRegistries.FEATURE_TYPE,dev.freshgenerator.volcano.VolcanicVents.ID,dev.freshgenerator.volcano.VolcanicVents.CODEC);
        net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.includeByKey(
                net.minecraft.world.level.biome.Biomes.DESERT,net.minecraft.world.level.biome.Biomes.BADLANDS,
                net.minecraft.world.level.biome.Biomes.ERODED_BADLANDS,net.minecraft.world.level.biome.Biomes.WOODED_BADLANDS),
            net.minecraft.world.level.levelgen.GenerationStep.Decoration.LAKES,
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.PLACED_FEATURE,dev.freshgenerator.volcano.VolcanicVents.ID));
        // Broken generation invariants go to the game log, once each (core Diagnostics; code audit 24.11 K-4).
        dev.freshgenerator.core.Diagnostics.sink=LOG::warn;
        LOG.info("One world; regional biome climate, local habitats, drainage network and native snow authority.");
    }
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("FreshGenerator");
}
