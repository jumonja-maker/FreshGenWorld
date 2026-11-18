package dev.freshgenerator;

import dev.freshgenerator.village.VillageFarmSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VillageFarmSupportTest {
    @Test void shallowWaterBelowCropsGetsAnchoredWithoutMovingSurface() {
        assertEquals(5, VillageFarmSupport.supportDepth(true, 100, d -> d == 6, d -> d < 6));
    }
    @Test void alreadySupportedCropsNeedNoChanges() {
        assertEquals(0, VillageFarmSupport.supportDepth(true, 100, d -> true, d -> false));
    }
    @Test void irrigationAndOpenWaterColumnsAreNeverFilled() {
        assertEquals(0, VillageFarmSupport.supportDepth(false, 100,
            d -> { fail("Must not inspect a column outside the crop stencil"); return false; }, d -> true));
    }
    @Test void LavaOrAnyOtherProtectedMaterialAbortsBeforeWriting() {
        assertEquals(0, VillageFarmSupport.supportDepth(true, 100, d -> d == 7, d -> d != 3));
    }
    @Test void DeepOpenColumnsDoNotReceiveHangingPillars() {
        assertEquals(0, VillageFarmSupport.supportDepth(true, 100, d -> d == 17, d -> true));
        assertEquals(15, VillageFarmSupport.supportDepth(true, 100, d -> d == 16, d -> true));
    }
    @Test void worldBottomBoundsAreRespected() {
        assertEquals(0, VillageFarmSupport.supportDepth(true, 2,
            d -> { assertTrue(d <= 2); return d == 3; }, d -> true));
    }
    @Test void onlyNativeVillageTemplatesAreEligible() {
        assertTrue(VillageFarmSupport.eligibleTemplate("minecraft:village/savanna/streets/straight_11"));
        assertFalse(VillageFarmSupport.eligibleTemplate("other:village/savanna/streets/straight_11"));
        assertFalse(VillageFarmSupport.eligibleTemplate("minecraft:pillager_outpost/feature_cage1"));
        assertFalse(VillageFarmSupport.eligibleTemplate(null));
    }
}
