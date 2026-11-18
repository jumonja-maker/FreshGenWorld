package dev.freshgenerator;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PublishedRuntimeSafetyTest {
 @Test void publishedRuntimeClassesStayIdenticalOutsideExplicitlyReviewedAdapters()throws Exception {
  Set<String> reviewed = Set.of(
    "dev/freshgenerator/FreshGenerator.class",
    "dev/freshgenerator/mixin/CaveCarverMixin.class",
    "dev/freshgenerator/core/ForestEcology.class",
    "dev/freshgenerator/core/ForestEcology$Family.class",
    "dev/freshgenerator/forest/ForestEnvironment.class",
    "dev/freshgenerator/forest/ForestTelemetry.class",
    "dev/freshgenerator/core/EcotoneModel.class",
    "dev/freshgenerator/core/EcotoneModel$Fields.class",
    "dev/freshgenerator/surface/EcotonePolicy.class",
    "dev/freshgenerator/surface/EcotoneSurfaceRule.class",
    "dev/freshgenerator/surface/EcotoneSurfaceRule$1.class",
    "dev/freshgenerator/mixin/ForestPlacementMixin.class",
    "dev/freshgenerator/village/VillageTerrainModel.class");
  Set<String> removed = Set.of("dev/freshgenerator/surface/SnowTransitions.class",
      "dev/freshgenerator/mixin/BiomeSnowMixin.class");
  try (var zip = new ZipFile("dist/FreshGenerator-Fabric-26.3-0.3.6-alpha.1.jar")) {
   int classes = 0, reviewedChanges = 0, reviewedRemovals = 0;
   for (var entry : Collections.list(zip.entries())) {
    if (!entry.getName().endsWith(".class")) continue;
    Path path = Path.of("build/classes/java/main", entry.getName());
    if (!Files.exists(path)) path = Path.of("build/classes/java/core", entry.getName());
    classes++;
    if (removed.contains(entry.getName())) {
     assertFalse(Files.exists(path), "Independent snow override must not remain packaged");
     reviewedRemovals++;
     continue;
    }
    try (var in = zip.getInputStream(entry)) {
     byte[] published = in.readAllBytes(), current = Files.readAllBytes(path);
     // Landscape adapters and neighboring village-footprint priority are reviewed;
     // unrelated pre-water runtime stays intact.
     if (reviewed.contains(entry.getName())) {
      assertFalse(Arrays.equals(published, current), "The reviewed adapter change must be present");
      reviewedChanges++;
     } else assertArrayEquals(published, current, entry.getName());
    }
   }
   assertEquals(37, classes);
   assertEquals(reviewed.size(), reviewedChanges);
   assertEquals(removed.size(), reviewedRemovals);
  }
 }
}
