package dev.freshgenerator.forest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.*;

/** A donor palette may change the tree at an already validated source position,
 * never that position or its source-biome identity. Preserve the donor's actual
 * point predicates before its feature can write soil, trunks or foliage. */
public final class NativeForestPalette {
    private NativeForestPalette() { }

    public static PlacedFeature atSourcePosition(PlacedFeature donor) {
        var filters=pointFilters(donor.placement());
        return filters==null||!checkedTrees(donor.feature().value(),hasPredicate(filters),0)
            ?null:new PlacedFeature(donor.feature(),filters);
    }

    /** Unknown layouts decline borrowing rather than guessing which modifiers
     * move/repeat positions or hide additional point conditions. */
    public static List<PlacementModifier> pointFilters(List<PlacementModifier> original) {
        var filters=new ArrayList<PlacementModifier>();
        for(var modifier:original) {
            if(modifier instanceof BlockPredicateFilter||modifier instanceof SurfaceWaterDepthFilter)
                filters.add(modifier);
            else if(!(modifier instanceof CountPlacement||modifier instanceof RarityFilter
                    ||modifier instanceof InSquarePlacement||modifier instanceof HeightmapPlacement
                    ||modifier instanceof BiomeFilter))return null;
        }
        return List.copyOf(filters);
    }

    private static boolean hasPredicate(List<PlacementModifier> filters) {
        return filters.stream().anyMatch(BlockPredicateFilter.class::isInstance);
    }

    /** A valid outer layout with no soil predicate must not turn a raw tree into
     * an unchecked palette. Native selectors retain their original nested
     * PlacedFeatures; check that each tree still has its own point predicate or
     * a native root placer. Other native feature types keep their own rules. */
    private static boolean checkedTrees(Feature feature,boolean checked,int depth) {
        if(depth>4)return false;
        if(feature instanceof TreeFeature tree)return checked||tree.rootPlacer().isPresent();
        if(feature instanceof RandomSelectorFeature selector) {
            for(var choice:selector.features()) {
                var placed=choice.feature().value();
                if(!checkedTrees(placed.feature().value(),checked||hasPredicate(placed.placement()),depth+1))return false;
            }
            var placed=selector.defaultFeature().value();
            return checkedTrees(placed.feature().value(),checked||hasPredicate(placed.placement()),depth+1);
        }
        return true;
    }
}
