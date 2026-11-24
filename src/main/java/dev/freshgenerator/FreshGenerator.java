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
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.hydrology.HydrologyDensity.ID,dev.freshgenerator.hydrology.HydrologyDensity.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.climate.IslandClimateDensity.ID,dev.freshgenerator.climate.IslandClimateDensity.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,dev.freshgenerator.climate.LatticeDensity.ID,dev.freshgenerator.climate.LatticeDensity.CODEC);
        Registry.register(BuiltInRegistries.FEATURE_TYPE,dev.freshgenerator.hydrology.HydroDecor.ID,dev.freshgenerator.hydrology.HydroDecor.CODEC);
        net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInOverworld(),
            net.minecraft.world.level.levelgen.GenerationStep.Decoration.VEGETAL_DECORATION,
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.PLACED_FEATURE,dev.freshgenerator.hydrology.HydroDecor.ID));
        // Broken generation invariants go to the game log, once each (core Diagnostics; code audit 24.11 K-4).
        dev.freshgenerator.core.Diagnostics.sink=LOG::warn;
        LOG.info("One world; regional biome climate, local habitats, drainage network and native snow authority.");
    }
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("FreshGenerator");
}
