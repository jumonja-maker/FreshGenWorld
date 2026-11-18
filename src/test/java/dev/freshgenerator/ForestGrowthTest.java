package dev.freshgenerator;

import dev.freshgenerator.core.ForestEcology;
import dev.freshgenerator.core.ForestGrowth;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ForestGrowthTest {
    private static final ForestEcology.Family FAMILY=ForestEcology.Family.TEMPERATE;
    private static ForestEcology.Site site(double h,double slope,double convexity,double t,double m,double water,double edge){
        return new ForestEcology.Site(h,63,slope,convexity,t,m,water,edge);
    }
    private static final ForestEcology.Site GOOD=site(90,.10,-1.2,.35,.45,0,0);
    @Test void fertileShelteredTreesAreTallerThanDryShallowExposedTrees(){
        double good=0,poor=0;
        for(int x=-600;x<600;x+=3){
            good+=ForestGrowth.sample(42,x,57,FAMILY,GOOD).heightScale();
            poor+=ForestGrowth.sample(42,x,57,FAMILY,site(145,1.15,2.5,.35,-.9,0,0)).heightScale();
        }
        assertTrue(good>poor*1.65,"Site condition must be visible in tree stature");
    }
    @Test void openGroundProducesBroaderLowerCrownsThanTheSameClosedStand(){
        double openHeight=0,closedHeight=0,openCrown=0,closedCrown=0;
        for(int x=-600;x<600;x+=3){
            var closed=ForestGrowth.sample(19,x,57,FAMILY,GOOD);
            var open=ForestGrowth.sample(19,x,57,FAMILY,site(90,.10,-1.2,.35,.45,0,1));
            openHeight+=open.heightScale();closedHeight+=closed.heightScale();
            openCrown+=open.crownSpread();closedCrown+=closed.crownSpread();
        }
        assertTrue(openCrown>closedCrown*1.5);assertTrue(closedHeight>openHeight);
    }
    @Test void wetLowGroundDoesNotAlwaysProduceTheTallestTrees(){
        for(var family:ForestEcology.Family.values()){
            var drained=ForestGrowth.sample(51,123,456,family,site(90,.1,0,.35,.8,0,0));
            var saturated=ForestGrowth.sample(51,123,456,family,site(64,.1,0,.35,.8,1,0));
            assertTrue(drained.productivity()>saturated.productivity()*2);
            assertTrue(drained.heightScale()>saturated.heightScale());
        }
    }
    @Test void upperTreelineReducesSizeWithinTheSameTreeFamily(){
        for(var family:ForestEcology.Family.values()){
            var low=ForestGrowth.sample(23,100,200,family,site(100,.1,0,.2,.45,0,0));
            var high=ForestGrowth.sample(23,100,200,family,site(265,.1,0,.2,.45,0,0));
            assertTrue(low.heightScale()>high.heightScale()*1.5);
        }
    }
    @Test void ecologicalFieldsVaryGraduallyWithoutChunkBoundaries(){
        double adjacent=0,distant=0;
        for(int x=-512;x<512;x++){
            double a=ForestGrowth.sample(7,x,31,FAMILY,GOOD).heightScale();
            adjacent+=Math.abs(a-ForestGrowth.sample(7,x+1,31,FAMILY,GOOD).heightScale());
            distant+=Math.abs(a-ForestGrowth.sample(7,x+71,31,FAMILY,GOOD).heightScale());
        }
        assertTrue(distant>adjacent*10);
    }
    @Test void growthAndBandSelectionAreIndependentOfOrderAndThreads(){
        var serial=IntStream.range(-800,800).mapToObj(x->ForestGrowth.shape(42,x,31,ForestGrowth.sample(42,x,31,FAMILY,GOOD))).toList();
        var parallel=IntStream.range(-800,800).parallel().mapToObj(x->ForestGrowth.shape(42,x,31,ForestGrowth.sample(42,x,31,FAMILY,GOOD))).toList();
        assertEquals(serial,parallel);
        for(int i=1599;i>=0;i--){int x=i-800;assertEquals(serial.get(i),ForestGrowth.shape(42,x,31,ForestGrowth.sample(42,x,31,FAMILY,GOOD)));}
    }
    @Test void quantizationPreservesExpectedContinuousHeightAcrossBands(){
        for(double value:new double[]{.6,.7,.8,.91,1,1.12,1.25,1.4,1.5}){
            double sum=0;Set<Integer> bands=new HashSet<>();
            var profile=new ForestGrowth.Profile(value,1.3,.5,.2,0,.5);
            for(int x=0;x<10000;x++){
                var shape=ForestGrowth.shape(19,x,31,profile);sum+=ForestGrowth.scale(shape.sizeBand());bands.add(shape.sizeBand());
                assertTrue(shape.crownBand()==1||shape.crownBand()==2);
            }
            assertEquals(value,sum/10000,.006);assertTrue(bands.size()<=2);
        }
    }
    @Test void ClosedStandsContainSubordinateTreesInsteadOfEveryTreeBeingTall(){
        int shortTrees=0,tallTrees=0;
        var profile=new ForestGrowth.Profile(1.4,.5,.9,0,.9,.6);
        for(int x=0;x<10000;x++){
            int band=ForestGrowth.shape(73,x,31,profile).sizeBand();
            if(band<=2)shortTrees++;if(band>=3)tallTrees++;
        }
        assertTrue(shortTrees>1000&&shortTrees<2500);assertTrue(tallTrees>7000);
    }
    @Test void extremeFiniteSitesProduceBoundedShapes(){
        for(var family:ForestEcology.Family.values())for(int h:new int[]{63,100,200,310})for(double wet:new double[]{-2,0,2}){
            var p=ForestGrowth.sample(11,h,19,family,site(h,2.1,5,-1.2,wet,1,1));
            assertTrue(p.heightScale()>=.6&&p.heightScale()<=1.5);
            assertTrue(p.crownSpread()>=0&&p.crownSpread()<=2);
            assertTrue(p.productivity()>=0&&p.productivity()<=1);
            assertNotNull(ForestGrowth.shape(11,h,19,p));
        }
    }
    @Test void invalidProfilesAndBandsFailBeforeNativePlacement(){
        assertThrows(IllegalArgumentException.class,()->ForestGrowth.shape(1,0,0,new ForestGrowth.Profile(Double.NaN,1,0,0,0,0)));
        assertThrows(IllegalArgumentException.class,()->ForestGrowth.shape(1,0,0,new ForestGrowth.Profile(1,3,0,0,0,0)));
        assertThrows(IllegalArgumentException.class,()->new ForestGrowth.Shape(-1,0));
        assertThrows(IllegalArgumentException.class,()->ForestGrowth.scale(5));
    }
}
