package dev.freshgenerator;

import dev.freshgenerator.village.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VillageTerrainModelTest {
    private static VillageTerrainModel.Site house(int x,int y){return new VillageTerrainModel.Site(x,0,x+8,8,y,true);}
    @Test void emptyPlanIsExactIdentity(){var m=new VillageTerrainModel(List.of());var c=m.column(-17,32,78,63);assertEquals(78,c.height());assertEquals(0,c.influence());assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(c.density(-0.0,78)));}
    @Test void noInfluenceBeyondTwentyFourBlocks(){var m=new VillageTerrainModel(List.of(house(0,80)));for(int x=32;x<100;x++){var c=m.column(x,4,78,63);assertEquals(0,c.influence());assertEquals(-0.25,c.density(-0.25,65));}}
    @Test void rigidGroundIsFlatButDistinctHousesKeepTheirOwnHeight(){var m=new VillageTerrainModel(List.of(house(0,80),house(40,91)));for(int x=0;x<=8;x++)for(int z=0;z<=8;z++){assertEquals(80,m.column(x,z,75+x,63).height());assertEquals(91,m.column(x+40,z,75+x,63).height());}}
    @Test void neighboringHouseApronCannotLiftTheNativeHouseFootprint(){
        // Seed42 village(-467,411): adjacent native pieces at a four-block
        // height difference previously lifted three measured columns to130.
        var lower=new VillageTerrainModel.Site(-7488,6561,-7480,6568,128,true);
        var upper=new VillageTerrainModel.Site(-7479,6562,-7472,6576,132,true);
        var m=new VillageTerrainModel(List.of(lower,upper));
        for(int z=6562;z<=6568;z++){
            assertEquals(128,m.column(-7480,z,140,63).height());
            assertEquals(132,m.column(-7479,z,140,63).height());
        }
    }
    @Test void trueOverlappingFootprintsAndSharedOutsideApronsStillBlend(){
        var overlap=new VillageTerrainModel(List.of(house(0,80),house(8,84)));
        assertEquals(82,overlap.column(8,4,90,63).height());
        var adjacent=new VillageTerrainModel(List.of(house(0,80),house(10,84)));
        assertEquals(82,adjacent.column(9,4,90,63).height());
        assertEquals(80,adjacent.column(-1,4,90,63).height());
    }
    @Test void roadsRetainTheSlope(){var m=new VillageTerrainModel(List.of(new VillageTerrainModel.Site(0,0,24,4,75,false)));for(int x=0;x<24;x++){var c=m.column(x,2,75+x*.3,63);assertEquals(75+x*.3,c.height());assertEquals(1,c.influence());}}
    @Test void protectedRoofClosesNoiseHolesAndLaterCarvers(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,76,63);for(int y=56;y<80;y++){assertTrue(c.density(-0.4,y)>0);assertFalse(c.permitsCarving(y));}}
    @Test void houseHeadroomIsNotBuried(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,92,63);for(int y=80;y<96;y++)assertTrue(c.density(.4,y)<0);}
    @Test void deepCavesAreBitwiseUnchanged(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,72,63);for(int y=-64;y<=32;y++){assertEquals(-.123456789,c.density(-.123456789,y));assertTrue(c.permitsCarving(y));}}
    @Test void highSkyRemainsUnchanged(){var c=new VillageTerrainModel(List.of(house(0,80))).column(4,4,72,63);assertEquals(-.7,c.density(-.7,100));assertTrue(c.permitsCarving(100));}
    @Test void raisedPlotAtTheWaterEdgeGetsAShortBankNotAWall(){
        // 0.9.1 audit A09: 7x7 plot at 80, original ground 62/63/64, sea 63.
        var m=new VillageTerrainModel(List.of(new VillageTerrainModel.Site(0,0,6,6,80,true)));
        for(double base:new double[]{62,63,64}){
            double previous=m.column(7,3,base,63).height();assertEquals(80,previous,1e-9);
            for(int x=8;x<=18;x++){double h=m.column(x,3,base,63).height();
                assertTrue(previous-h<=6.5,"step "+(previous-h)+" at x="+x+" base="+base);assertTrue(h<=previous+1e-9);previous=h;}
            if(base<=63)assertEquals(base,m.column(16,3,base,63).height(),1e-9,"water bank ends within its width");
        }
    }
    @Test void bankWidthChangesContinuouslyWithTheWaterDepth(){
        // 0.9.1 audit V1: a hard switch at sea-2 moved the target height by 11.4 blocks.
        var m=new VillageTerrainModel(List.of(new VillageTerrainModel.Site(0,0,6,6,80,true)));
        for(int x=8;x<=16;x++){double previous=m.column(x,3,57,63).height();
            for(double base=57.01;base<=64;base+=.01){double h=m.column(x,3,base,63).height();
                assertTrue(Math.abs(h-previous)<.5,"jump "+(h-previous)+" at x="+x+" base="+base);previous=h;}}
    }
    @Test void smoothNeverLeavesTheUnitInterval(){
        for(double v=65.99999;v<=66.00001;v+=1e-7){double s=VillageTerrainModel.smooth(63,66,v);assertTrue(s>=0&&s<=1);}
    }
    @Test void waterOutsideActualRigidPlotsIsNotDammed(){var m=new VillageTerrainModel(List.of(house(0,80),new VillageTerrainModel.Site(10,0,35,4,75,false)));for(int x=12;x<35;x++){var c=m.column(x,2,58,63);assertEquals(0,c.influence());assertEquals(58,c.height());}}
    @Test void orderDuplicatesAndChunkTraversalDoNotChangeThePlan(){var a=house(-20,81);var b=house(20,86);var p=new VillageTerrainModel(List.of(a,b));var q=new VillageTerrainModel(List.of(b,a,a));for(int x=-64;x<64;x++)for(int z=-32;z<32;z++)assertEquals(p.column(x,z,74+x*.1,63),q.column(x,z,74+x*.1,63));}
    @Test void modelCopiesMutableInputs(){var input=new ArrayList<VillageTerrainModel.Site>();input.add(house(0,80));var m=new VillageTerrainModel(input);input.clear();assertEquals(1,m.sites().size());assertThrows(UnsupportedOperationException.class,()->m.sites().clear());}
    @Test void smoothFalloffHasFlatEnds(){double e=1e-4;assertEquals(0,VillageTerrainModel.smooth(0,24,0));assertEquals(1,VillageTerrainModel.smooth(0,24,24));assertTrue(VillageTerrainModel.smooth(0,24,e)<1e-12);assertTrue(1-VillageTerrainModel.smooth(0,24,24-e)<1e-12);}
    @Test void noRectangularCliffAtFeatherBoundary(){var m=new VillageTerrainModel(List.of(house(0,90)));double previous=m.column(28,4,74,63).height();for(int x=29;x<35;x++){double h=m.column(x,4,74,63).height();assertTrue(Math.abs(h-previous)<.5);previous=h;}}
    @Test void negativeCoordinatesTranslateExactly(){var p=new VillageTerrainModel(List.of(house(0,80)));var q=new VillageTerrainModel(List.of(new VillageTerrainModel.Site(-64,-64,-56,-56,80,true)));for(int x=-30;x<40;x++)assertEquals(p.column(x,4,75,63),q.column(x-64,-60,75,63));}
    @Test void invalidFootprintsAndInputsFail(){assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Site(2,0,1,2,70,true));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Site(0,0,2,2,999,true));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Column(70,Double.NaN,1));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel.Column(70,70,2));assertThrows(IllegalArgumentException.class,()->new VillageTerrainModel(List.of()).column(0,0,Double.NaN,63));}
    @Test void vanillaVillageWhitelistDoesNotIncludeOutpostsOrModdedStructures(){assertTrue(VillageTerrainPlan.villageId("minecraft:village_savanna"));assertFalse(VillageTerrainPlan.villageId("minecraft:pillager_outpost"));assertFalse(VillageTerrainPlan.villageId("other:village_savanna"));}
    @Test void parallelPureEvaluationIsRepeatable(){var m=new VillageTerrainModel(List.of(house(0,80)));var a=java.util.stream.IntStream.range(-100,100).mapToObj(x->m.column(x,2,75,63)).toList();var b=java.util.stream.IntStream.range(-100,100).parallel().mapToObj(x->m.column(x,2,75,63)).toList();assertEquals(a,b);}
}
