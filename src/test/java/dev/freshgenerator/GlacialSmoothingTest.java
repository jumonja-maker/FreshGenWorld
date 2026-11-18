package dev.freshgenerator;

import dev.freshgenerator.core.GlacialIce;
import dev.freshgenerator.ice.GlacialSurface;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded physical suitability must survive floating-point endpoint rounding. */
class GlacialSmoothingTest {
    private static double smooth(Class<?> owner,double a,double b,double v)throws Exception{
        Method method=owner.getDeclaredMethod("smooth",double.class,double.class,double.class);method.setAccessible(true);
        return ((Number)method.invoke(null,a,b,v)).doubleValue();
    }
    @Test void increasingAndDecreasingBandsRemainWithinClosedUnitInterval()throws Exception{
        for(Class<?> owner:new Class<?>[]{GlacialIce.class,GlacialSurface.class})
        for(double[] band:new double[][]{{0,1},{0,45},{.25,-.50},{.50,.90},{-.6,1.8}})
        for(double t:new double[]{-1,0,Math.nextUp(0.),1e-12,1e-7,.25,.5,.75,1-1e-7,1-1e-6,1-1e-11,Math.nextDown(1.),1,2}){
            double actual=smooth(owner,band[0],band[1],band[0]+(band[1]-band[0])*t);
            assertTrue(Double.isFinite(actual)&&actual>=0&&actual<=1,owner.getSimpleName()+" t="+t+" result="+actual);
        }
    }
    @Test void observedNativeMountainInputsCannotCrashLandSiteValidation()throws Exception{
        // Native seed2026111501, x−13316,z4652: the old result was1.0000000000000002.
        double cold=smooth(GlacialSurface.class,.25,-.50,-.49999672174453735);
        double supply=smooth(GlacialSurface.class,0,45,215.0498046875-191.27769470214844);
        assertEquals(1.,cold);
        var site=assertDoesNotThrow(()->new GlacialIce.LandSite(191.27769470214844,1.1291142598451638,0,cold,supply,-.9812769889831543,.5585646629333496));
        assertDoesNotThrow(()->GlacialIce.land(2026111501,-13316,4652,site));
    }
    @Test void exactAndOutsideBandEndpointsKeepTheirOriginalMeaning()throws Exception{
        for(Class<?> owner:new Class<?>[]{GlacialIce.class,GlacialSurface.class}){
            assertEquals(0.,smooth(owner,0,1,-.1));assertEquals(0.,smooth(owner,0,1,0));
            assertEquals(1.,smooth(owner,0,1,1));assertEquals(1.,smooth(owner,0,1,1.1));
            assertEquals(0.,smooth(owner,.25,-.50,.5));assertEquals(1.,smooth(owner,.25,-.50,-.75));
            assertEquals(.5,smooth(owner,0,1,.5));
        }
    }
}
