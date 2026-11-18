package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.core.LandscapeTerrainProgram;
import dev.freshgenerator.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LandscapeTerrainTest {
    private static final String P=LandscapeTerrainCompiler.P,L=LandscapeTerrainCompiler.L;
    private static final LandscapeTerrainProgram PROGRAM=LandscapeTerrainProgram.compile(P);
    private static Map<String,Double> site(double c,double e,double p,double h,double giant,double rock) {
        return new HashMap<>(Map.of(P+"continents",c,P+"erosion",e,P+"ridges_folded",p,P+"base_offset",(h-128)/128,
                L+"uplift_noise",.4,L+"relief_noise",0.,L+"detail_noise",0.,L+"giant_noise",giant,L+"coast_noise",rock,
                L+"landform_noise",0.));
    }
    private static float sample(String name,Map<String,Double> inputs){return PROGRAM.sample(name,id->{Double v=inputs.get(id);if(v==null)throw new AssertionError(id);return v;});}
    private static float continents(double original,double macro) {
        return LandscapeTerrainCompiler.continentExpression().evaluate(id->id.equals(L+"original_continents")?original:macro);
    }
    @Test void continentalVariationPreservesMushroomTailAndHasContinuousFade() {
        for(double original:new double[]{-2,-1.2,-1.1,-1.05})for(double macro:new double[]{-1,0,1})
            assertEquals(Float.floatToIntBits((float)original),Float.floatToIntBits(continents(original,macro)));
        for(double macro:new double[]{-1,0,1}) {
            for(double boundary:new double[]{-1.05,-.8})
                assertEquals(continents(boundary-1e-5,macro),continents(boundary+1e-5,macro),1e-4);
            assertEquals(-.8*.93+macro*.10,continents(-.8,macro),1e-6);
            for(int i=1;i<=250;i++)assertTrue(continents(-1.05+i*.001,macro)>-1.05,
                    "The transition must not create a new mushroom-island tail");
        }
    }
    @Test void giantUpliftCannotAppearInOceanLowlandOrRiverCorridor() {
        for(var input:List.of(site(-.7,-.8,.9,40,1,0),site(.5,.8,.9,90,1,0),site(.5,-.8,-1,64,1,0))) {
            assertEquals(0,sample("mountain",input),0);
            assertEquals(0,sample("giant",input),0);
            assertEquals(0,sample("uplift",input),0);
        }
    }
    @Test void rareProvinceAddsTallPeaksInsideTheSameMountainRidges() {
        var ordinary=site(.6,-.8,.9,192,-.6,0);
        var rare=site(.6,-.8,.9,192,.9,0);
        assertEquals(0,sample("giant",ordinary),0);
        assertTrue(sample("giant",rare)>.9);
        double low=LandscapeTerrainProgram.softHeight(sample("height_raw",ordinary));
        double high=LandscapeTerrainProgram.softHeight(sample("height_raw",rare));
        assertTrue(low<240,"Ordinary mountain must retain headroom for giants: "+low);
        assertTrue(high>280&&high<310,"Rare peak must be substantially taller: "+high);
        var flank=site(.6,-.8,-.30,100,.9,0);
        assertTrue(sample("height_raw",flank)>100);
        assertTrue(sample("height_raw",flank)<160,"Foothill cannot become a summit wall");
    }
    @Test void coastProfilesVaryContinuouslyWithoutMovingTheSeaLevelCrossing() {
        for(double height:new double[]{40,55,63,75,90}) {
            var soft=site(-.2,-.5,0,height,0,-1);
            var rocky=site(-.2,-.5,0,height,0,1);
            double a=sample("coast_height",soft),b=sample("coast_height",rocky);
            assertEquals(Math.signum(height-63),Math.signum(a-63),0);
            assertEquals(Math.signum(height-63),Math.signum(b-63),0);
            if(height>63)assertTrue(b>a);
            if(height<63)assertTrue(b<a);
        }
    }
    @Test void softCeilingHasNoPlateauAndKeepsWorldHeightHeadroom() {
        double previous=-Double.MAX_VALUE;
        for(double raw=220;raw<=1000;raw+=.25) {
            double h=LandscapeTerrainProgram.softHeight(raw);
            assertTrue(h>previous);assertTrue(h<310);previous=h;
        }
        assertEquals(-48,LandscapeTerrainProgram.softHeight(-1000),0);
        assertThrows(IllegalArgumentException.class,()->LandscapeTerrainProgram.softHeight(Double.NaN));
    }
    @Test void landscapeLayerChangesOnlyItsDeclaredCurrentInputs() throws Exception {
        Map<String,JsonElement> before=new TreeMap<>(WorldgenCatalog.compileTerrain(new VanillaAssets()));
        SurfaceOnlyCompiler.apply(before);
        Map<String,JsonElement> after=new TreeMap<>(before);
        LandscapeTerrainCompiler.apply(after);
        Set<String> changed=new TreeSet<>();
        before.forEach((k,v)->{if(!v.equals(after.get(k)))changed.add(k);});
        assertEquals(LandscapeTerrainCompiler.REPLACED,changed);
        var height=after.get(LandscapeTerrainCompiler.path(P+"coherent/height")).getAsJsonObject().getAsJsonObject("input");
        assertEquals(4,height.get("cell_size_xz").getAsInt());
        assertEquals(before.get(SurfaceOnlyCompiler.FINAL_DENSITY),after.get(SurfaceOnlyCompiler.FINAL_DENSITY));
        assertEquals(before.get(SurfaceOnlyCompiler.PRELIMINARY),after.get(SurfaceOnlyCompiler.PRELIMINARY));
        assertEquals(before.get(SurfaceOnlyCompiler.CAVE_WEIGHT),after.get(SurfaceOnlyCompiler.CAVE_WEIGHT));
        assertEquals(before.get(LandscapeTerrainCompiler.path(P+"continents")),after.get(LandscapeTerrainCompiler.path(L+"original_continents")));
        var frozen=new TreeMap<>(after);
        assertThrows(IllegalStateException.class,()->LandscapeTerrainCompiler.apply(after));
        assertEquals(frozen,after);
    }
    @Test void masksAreBoundedAndHeightFiniteAcrossExtremeInputs() {
        for(double c:new double[]{-2,-.2,0,2})for(double e:new double[]{-2,0,2})for(double p:new double[]{-2,0,2}) {
            var input=site(c,e,p,128,2,-2);
            for(String mask:List.of("mountain","giant","coast","coast_rock","form_massif","form_ridge","form_plateau","form_smoothing",
                    "eroded_lowland","valley_floor","lowland_quiet","footslope")) {
                float value=sample(mask,input);assertTrue(value>=0&&value<=1,mask+" "+value);
            }
            assertTrue(Float.isFinite(sample("height_raw",input)));
        }
    }
    @Test void mountainFormRegionsBlendWithoutOverlapOrMissingWeight() {
        var input=site(.6,-.8,.7,180,0,0);
        for(int i=-200;i<=200;i++) {
            input.put(L+"landform_noise",i/100.0);
            float massif=sample("form_massif",input),ridge=sample("form_ridge",input),plateau=sample("form_plateau",input);
            assertTrue(massif>=0&&ridge>=0&&plateau>=0);
            assertEquals(1,massif+ridge+plateau,1e-6);
            assertEquals(0,massif*plateau,0,"Far-separated form types blend through the ridge domain");
        }
        for(double boundary:new double[]{-.45,-.05,.18,.58}) {
            input.put(L+"landform_noise",boundary-1e-6);float a=sample("height_raw",input);
            input.put(L+"landform_noise",boundary+1e-6);float b=sample("height_raw",input);
            assertEquals(a,b,1e-3,"Regional form transitions must not create an elevation seam");
        }
    }
    @Test void broadFormsRaiseShouldersAndSuppressLocalRoughnessRatherThanOnlyRaisingPeaks() {
        var input=site(.6,-.8,.10,145,0,0);
        input.put(L+"landform_noise",0.);float narrowShoulder=sample("height_raw",input);
        input.put(L+"landform_noise",-.8);float broadShoulder=sample("height_raw",input);
        input.put(L+"landform_noise",.8);float plateauShoulder=sample("height_raw",input);
        assertTrue(broadShoulder>narrowShoulder+15,"Massif changes the width of the elevated shoulder");
        assertTrue(plateauShoulder>narrowShoulder+15,"Plateau changes the shoulder, not just the summit");
        input=site(.6,-.8,.9,190,0,0);
        double[] detailResponse=new double[3];int index=0;
        for(double form:new double[]{-.8,0,.8}) {
            input.put(L+"landform_noise",form);
            input.put(L+"detail_noise",-1.);float low=sample("height_raw",input);
            input.put(L+"detail_noise",1.);float high=sample("height_raw",input);
            detailResponse[index++]=high-low;
        }
        assertTrue(detailResponse[1]>detailResponse[0]*1.5);
        assertTrue(detailResponse[0]>detailResponse[2]*1.4);
    }
    @Test void plateauRetainsReliefAndDoesNotBecomeAUniversalFlatCeiling() {
        var input=site(.6,-.8,.9,150,0,0);input.put(L+"landform_noise",.8);
        double previous=-Double.MAX_VALUE;
        for(int h=150;h<=230;h++) {
            input.put(P+"base_offset",(h-128)/128.0);
            double value=sample("height_raw",input);
            if(h>150)assertTrue(value-previous>=.59&&value-previous<=.61,
                    "Broad plateau must retain 60 percent of the original highland relief");
            previous=value;
        }
    }
    @Test void regionalFormsCannotRaiseOceanValleyOrNonMountainTerrain() {
        for(var input:List.of(site(-.7,-.8,.9,40,1,0),site(.5,.8,.9,90,1,0),site(.5,-.8,-1,64,1,0))) {
            for(double form:new double[]{-.8,0,.8}) {
                input.put(L+"landform_noise",form);
                assertEquals(0,sample("form_adjustment",input),0);
                assertEquals(0,sample("uplift",input),0);
                assertEquals(0,sample("form_smoothing",input),0);
            }
        }
    }
    @Test void smoothSyntheticRidgeTransectStaysWithinExplicitSlopeBound() {
        for(double form:new double[]{-.8,0,.8})for(double giant:new double[]{0,.9}) {
            double previous=Double.NaN;
            for(int x=-512;x<=512;x+=4) {
                double ridge=Math.sin(x/160.0),nativeHeight=110+65*Math.max(0,ridge);
                var input=site(.6,-.8,ridge,nativeHeight,giant,0);input.put(L+"landform_noise",form);
                double height=LandscapeTerrainProgram.softHeight(sample("height_raw",input));
                // Rare peaks may be steep; this bounded synthetic input must not
                // produce a discontinuous rim. This is not a whole-world slope cap.
                double maxStep=giant>0?16:6;
                if(Double.isFinite(previous))assertTrue(Math.abs(height-previous)<maxStep,
                        "Unexpected jump: form="+form+", x="+x+", delta="+(height-previous));
                previous=height;
            }
        }
    }
    @Test void quietLowlandsRetainMacroReliefButLoseFineSurfaceJitter() {
        var low=site(.6,.8,.5,90,0,0);var high=site(.6,.8,.5,180,0,0);
        assertEquals(1,sample("lowland_quiet",low),0);assertEquals(0,sample("lowland_quiet",high),0);
        low.put(L+"detail_noise",-1.);high.put(L+"detail_noise",-1.);
        double lowFine=sample("height_raw",low),highFine=sample("height_raw",high);
        low.put(L+"detail_noise",1.);high.put(L+"detail_noise",1.);
        lowFine=sample("height_raw",low)-lowFine;highFine=sample("height_raw",high)-highFine;
        assertEquals(.35*highFine,lowFine,2e-5);
        low.put(L+"detail_noise",0.);high.put(L+"detail_noise",0.);
        low.put(L+"relief_noise",-1.);high.put(L+"relief_noise",-1.);
        double lowRolling=sample("height_raw",low),highRolling=sample("height_raw",high);
        low.put(L+"relief_noise",1.);high.put(L+"relief_noise",1.);
        lowRolling=sample("height_raw",low)-lowRolling;highRolling=sample("height_raw",high)-highRolling;
        assertEquals(.75*highRolling,lowRolling,3e-5);assertTrue(lowRolling>8,"Lowlands are not a flat plane");
    }
    @Test void foothillApronSupportsIntermediateSlopesAndVanishesInProtectedDomains() {
        var foot=site(.6,-.2,.3,120,0,0);
        double narrow=sample("foothill_apron",foot);assertTrue(narrow>1);assertTrue(narrow<=10);
        foot.put(L+"landform_noise",-.8);assertTrue(sample("foothill_apron",foot)>narrow*2,
                "Broad mountain forms support wider aprons rather than receiving a common-height ring");
        for(var protectedSite:List.of(site(-.7,-.2,.3,120,0,0),site(.1,-.2,.3,120,0,0),
                site(.6,-.2,-1,120,0,0),site(.6,-.8,.9,220,1,0),site(.6,.8,.5,90,0,0),site(.6,-.2,.3,70,0,0)))
            assertEquals(0,sample("foothill_apron",protectedSite),0);
        for(int i=0;i<=1000;i++){
            foot.put(P+"erosion",-.8+i*.0016);double apron=sample("foothill_apron",foot);
            assertTrue(apron>=0&&apron<=10);
        }
    }
    @Test void foothillRedistributionCannotCreateAnArtificialRaisedRimBeforeTheMountain() {
        // Hold the macro-height fixed while mountain expression grows. A narrow
        // ten-block bell bump fails this counterexample by making a raised ring
        // and then dropping down again before the stronger mountain profile.
        var input=site(.6,.8,.3,112,0,0);double previous=Double.NEGATIVE_INFINITY;
        for(int i=0;i<=1600;i++){
            input.put(P+"erosion",.8-i*.001);double value=sample("height_raw",input);
            assertTrue(value>=previous-1e-4,"Artificial foothill rim: "+value+" after "+previous);
            previous=value;
        }
    }
    @Test void lowlandQuietnessDoesNotReshapeTheSeaLevelEnvelope() {
        for(double h:new double[]{-48,20,55,63,65})for(double e:new double[]{-.8,.2,.8})for(double p:new double[]{-1,.3,.9}){
            var input=site(.6,e,p,h,0,0);
            assertEquals(0,sample("lowland_quiet",input),0);assertEquals(0,sample("foothill_apron",input),0);
        }
    }
}
