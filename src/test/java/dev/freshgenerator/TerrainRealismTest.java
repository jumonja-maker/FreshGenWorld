package dev.freshgenerator;

import com.google.gson.*;
import dev.freshgenerator.core.CoastalRelief;
import dev.freshgenerator.core.LandscapeTerrainProgram;
import dev.freshgenerator.ice.GlacialSurface;
import dev.freshgenerator.pack.*;
import dev.freshgenerator.surface.EcotonePolicy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TerrainRealismTest {
    private static final String P=TerrainRealismCompiler.P,L=TerrainRealismCompiler.L,C=TerrainRealismCompiler.C;
    private static final LandscapeTerrainProgram PROGRAM=LandscapeTerrainProgram.compile(P);

    private static Map<String,Double> site(double c,double e,double p,double h,double relief) {
        return new HashMap<>(Map.of(P+"continents",c,P+"erosion",e,P+"ridges_folded",p,P+"base_offset",(h-128)/128,
                L+"uplift_noise",.4,L+"relief_noise",relief,L+"detail_noise",-1.,L+"giant_noise",0.,L+"coast_noise",0.,
                L+"landform_noise",0.));
    }
    private static float sample(String node,Map<String,Double> in){return PROGRAM.sample(node,id->{Double v=in.get(id);if(v==null)throw new AssertionError(id);return v;});}
    /** Same composition as the emitted pack: seabed node replaced, then guarded. */
    private static double finalHeight(Map<String,Double> in) {
        double h=sample("native_height",in),relief=in.get(L+"relief_noise");
        double oldSeabed=sample("seabed_detail",in);
        double newSeabed=1.8*relief*(1-smooth(CoastalRelief.SEABED_DETAIL_START,CoastalRelief.SEABED_DETAIL_END,h));
        double raw=sample("height_raw",in)-oldSeabed+newSeabed;
        return CoastalRelief.guardedHeight(sample("native_height",in),raw);
    }
    private static double smooth(double lo,double hi,double v){double t=Math.max(0,Math.min(1,(v-lo)/(hi-lo)));return t*t*t*(t*(t*6-15)+10);}

    @Test void addedReliefCannotDrownNativeDryLowland() {
        int before=0;
        for(double h=64;h<=74;h+=.25)for(double p:new double[]{-.2,.3,.6})for(double c:new double[]{.05,.3,.7}) {
            var in=site(c,.6,p,h,-1);
            if(sample("height_raw",in)<62.5)before++;
            // Sea level 63 fills Y<=62; H>62 keeps a solid top block at or above Y62.
            double H=finalHeight(in),base=sample("native_height",in);
            assertTrue(H>62.5,"Dry native ground drowned: h="+h+" H="+H);
            assertTrue(H>=Math.min(base,CoastalRelief.DRY_FLOOR)-1e-6,"Relief went below the unrelieved dry base: h="+h+" H="+H);
        }
        assertTrue(before>0,"Counterexample must exist in the unguarded 0.8.1 landscape");
    }
    @Test void guardKeepsUpwardReliefMountainsAndSeaBedExact() {
        for(double h:new double[]{66,80,120,190})for(double r:new double[]{0,.5,1}) {
            var in=site(.4,.3,.2,h,r);double raw=sample("height_raw",in);
            if(raw-CoastalRelief.floorValue(sample("native_height",in))>=CoastalRelief.SOFTNESS)
                assertEquals(raw,CoastalRelief.guardedHeight(sample("native_height",in),raw),1e-9);
        }
        // Sea bed and native valley floors below H60 keep full detail.
        for(double base=-40;base<=56;base+=.5)for(double d=-12;d<=12;d+=.5)
            assertEquals(base+d,CoastalRelief.guardedHeight(base,base+d),1e-9);
    }
    @Test void guardIsContinuousWithContinuousSlopeInBothInputs() {
        for(double base=58;base<=70;base+=.37) {
            double previous=CoastalRelief.guardedHeight(base,40),slope=Double.NaN;
            for(double raw=40.01;raw<=90;raw+=.01) {
                double v=CoastalRelief.guardedHeight(base,raw),s=(v-previous)/.01;
                assertTrue(v>=previous-1e-9,"Height must not decrease when relief rises");
                if(!Double.isNaN(slope))assertTrue(Math.abs(s-slope)<.02,"Kink at base="+base+" raw="+raw);
                previous=v;slope=s;
            }
        }
        for(double raw:new double[]{50,60,62,64,66})for(double base=55;base<=68;base+=.001)
            assertEquals(CoastalRelief.guardedHeight(base,raw),CoastalRelief.guardedHeight(base+.001,raw),.05);
    }
    @Test void splineCurvesHaveTheIntendedClosedForms() {
        for(double d=-3;d<=3;d+=.01)assertEquals((d+3)*(d+3)/12,CoastalRelief.spline(CoastalRelief.GUARD_POINTS,d),1e-12);
        for(double d=-40;d<-3;d+=.5)assertEquals(0,CoastalRelief.spline(CoastalRelief.GUARD_POINTS,d),0);
        for(double d=3;d<40;d+=.5)assertEquals(d,CoastalRelief.spline(CoastalRelief.GUARD_POINTS,d),1e-12);
        for(double b=-60;b<=60;b+=.5)assertEquals(b-40,CoastalRelief.floorValue(b),1e-12);
        for(double b=64;b<=300;b+=1)assertEquals(CoastalRelief.DRY_FLOOR,CoastalRelief.floorValue(b),0);
        double previous=CoastalRelief.floorValue(55);
        for(double b=55;b<=70;b+=.001){double v=CoastalRelief.floorValue(b);assertTrue(v>=previous-1e-12,"Floor must rise with the base");previous=v;}
        for(double b=63.5;b<=64;b+=.01)assertTrue(CoastalRelief.floorValue(b)>=b-1e-12,"Dry base is never lowered");
    }
    @Test void emittedSplinesUseExactlyTheReferencePoints() throws Exception {
        var full=WorldgenCatalog.compile(new VanillaAssets());
        var floor=full.get(TerrainRealismCompiler.path(L+"coastal_floor")).getAsJsonObject().getAsJsonObject("input").getAsJsonObject("spline");
        assertEquals(L+"native_height",floor.get("coordinate").getAsString());
        assertPoints(CoastalRelief.FLOOR_POINTS,floor.getAsJsonArray("points"));
        var height=full.get(TerrainRealismCompiler.path(L+"height_raw")).getAsJsonObject().getAsJsonObject("input");
        assertEquals(L+"coastal_floor",height.get("left").getAsString());
        var guard=height.getAsJsonObject("right").getAsJsonObject("spline");
        assertEquals(L+"guard_offset",guard.get("coordinate").getAsString());
        assertPoints(CoastalRelief.GUARD_POINTS,guard.getAsJsonArray("points"));
        // The whole landscape root is read exactly once by the guard.
        long reads=full.values().stream().filter(v->v.toString().contains("\""+L+"height_unguarded\"")).count();
        assertEquals(1,reads);
    }
    private static void assertPoints(double[][] expected,JsonArray actual){
        assertEquals(expected.length,actual.size());
        for(int i=0;i<expected.length;i++){var p=actual.get(i).getAsJsonObject();
            assertEquals(expected[i][0],p.get("location").getAsDouble(),0);assertEquals(expected[i][1],p.get("value").getAsDouble(),0);assertEquals(expected[i][2],p.get("derivative").getAsDouble(),0);}
    }
    @Test void layerChangesOnlyDeclaredResourcesAndLeavesCavesWaterAndSettings() throws Exception {
        Map<String,JsonElement> before=new TreeMap<>(WorldgenCatalog.compileTerrain(new VanillaAssets()));
        SurfaceOnlyCompiler.apply(before);LandscapeTerrainCompiler.apply(before);LandscapeBiomeCompiler.apply(before);LandscapeSurfaceCompiler.apply(before);
        Map<String,JsonElement> after=new TreeMap<>(before);TerrainRealismCompiler.apply(after);
        Set<String> changed=new TreeSet<>(),added=new TreeSet<>(after.keySet());added.removeAll(before.keySet());
        before.forEach((k,v)->{if(!v.equals(after.get(k)))changed.add(k);});
        assertEquals(TerrainRealismCompiler.REPLACED,changed);assertEquals(TerrainRealismCompiler.ADDED,added);
        for(String unchanged:List.of(SurfaceOnlyCompiler.SETTINGS,SurfaceOnlyCompiler.FINAL_DENSITY,SurfaceOnlyCompiler.PRELIMINARY,
                LandscapeTerrainCompiler.path(P+"coherent/height"),LandscapeTerrainCompiler.path(P+"continents")))
            assertEquals(before.get(unchanged),after.get(unchanged),unchanged);
        assertTrue(after.keySet().stream().noneMatch(k->k.contains("cave_network")),"Cave network is installed later, unchanged");
        var frozen=new TreeMap<>(after);assertThrows(IllegalStateException.class,()->TerrainRealismCompiler.apply(after));assertEquals(frozen,after);
    }
    @Test void fullCatalogKeepsCaveSchemaAndUsesTheRealismLayer() throws Exception {
        var full=WorldgenCatalog.compile(new VanillaAssets());
        assertTrue(full.get(TerrainRealismCompiler.path(L+"height_raw")).toString().contains("landscape/coastal_floor"));
        // The alpine rock-detail branch is not part of the active density (audit 2026-11-18):
        // nothing in the published graph references it, so editing it changes no terrain.
        String detail=P+"coherent/detail/rock_relief";
        assertTrue(full.entrySet().stream().filter(e->!e.getKey().equals(TerrainRealismCompiler.path(detail)))
            .noneMatch(e->e.getValue().toString().contains("\""+detail+"\"")),"rock relief became active unexpectedly");
        for(String name:List.of("regional_temperature","regional_vegetation")){
            var noise=full.get(TerrainRealismCompiler.path(C+name)).getAsJsonObject();double scale=noise.get("xz_scale").getAsDouble();
            for(String axis:List.of("x","z")){
                // 0.10 (ClimateShapeCompiler) adds further warp layers on top: find the 0.8.2 bend in the add chain.
                var shift=noise.getAsJsonObject("shift_"+axis);boolean found=false;
                while(shift!=null&&"minecraft:add".equals(shift.get("type").getAsString())){
                    var right=shift.getAsJsonObject("right");
                    if(right.get("right").getAsString().equals(C+"warp_"+axis)){
                        assertEquals(TerrainRealismCompiler.WARP_BLOCKS*scale,right.get("left").getAsDouble(),1e-12);found=true;break;
                    }
                    shift=shift.get("left").isJsonObject()?shift.getAsJsonObject("left"):null;
                }
                assertTrue(found,"0.8.2 climate bend present: "+name+" "+axis);
            }
        }
        var sea=full.get(TerrainRealismCompiler.path(L+"seabed_detail")).toString();
        assertTrue(sea.contains("52.0")&&!sea.contains("55.0"));
    }
    @Test void icebergClimateIsContinuousAcrossOceanBiomeBorders() {
        double previous=GlacialSurface.oceanColdness(.5);
        for(double t=.5;t>=-1;t-=.001){double v=GlacialSurface.oceanColdness(t);assertTrue(v>=previous-1e-12);assertTrue(Math.abs(v-previous)<.01);previous=v;}
        assertTrue(GlacialSurface.oceanColdness(-.30)<.35,"Ordinary cold ocean stays free of large ice");
        assertTrue(GlacialSurface.oceanColdness(-.55)>.95,"Frozen ocean interior keeps its ice");
        assertEquals(0,GlacialSurface.oceanColdness(-.15),0,"No iceberg at the temperate ocean border");
    }
    @Test void alwaysSnowingBiomesAreNotAdvertisedAsSubstrateTargets() throws Exception {
        assertTrue(Collections.disjoint(EcotonePolicy.ALWAYS_SNOW,EcotonePolicy.BIOMES));
        assertTrue(Collections.disjoint(EcotonePolicy.ALWAYS_SNOW,EcotonePolicy.FOREST_FLOORS));
        var vanilla=new VanillaAssets();
        for(String id:EcotonePolicy.ALWAYS_SNOW){
            var biome=vanilla.read(VanillaAssets.path("biome",id)).getAsJsonObject();
            assertTrue(biome.get("has_precipitation").getAsBoolean(),id);
            assertTrue(biome.get("temperature").getAsFloat()<.15f,id+" snows at every height");
        }
        for(String id:EcotonePolicy.BIOMES){
            var biome=vanilla.read(VanillaAssets.path("biome",id)).getAsJsonObject();
            assertTrue(biome.get("temperature").getAsFloat()>=.15f,id+" must be able to reach the rule");
        }
    }
}
