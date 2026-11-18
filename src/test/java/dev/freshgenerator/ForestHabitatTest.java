package dev.freshgenerator;

import dev.freshgenerator.core.*;
import java.net.JarURLConnection;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForestHabitatTest {
    private static final ForestEcology.Site GOOD=new ForestEcology.Site(90,63,.1,-.8,.35,.45,0,0);
    @Test void everyActualNativeBiomeHasAnExplicitEcologicalOrUnmodifiedPolicy()throws Exception {
        var resource=getClass().getResource("/data/minecraft/worldgen/biome/forest.json");assertNotNull(resource);
        var connection=(JarURLConnection)resource.openConnection();int count=0,adapted=0;
        try(var jar=new ZipFile(Path.of(connection.getJarFileURL().toURI()).toFile())){
            for(var entry:Collections.list(jar.entries())){
                String path=entry.getName(),prefix="data/minecraft/worldgen/biome/";
                if(!path.startsWith(prefix)||!path.endsWith(".json"))continue;
                String local=path.substring(prefix.length(),path.length()-5),id="minecraft:"+local;
                var habitat=ForestHabitat.forBiome(id);count++;
                assertTrue(habitat!=null||ForestHabitat.UNMODIFIED.contains(local),"Unclassified native biome "+id);
                assertFalse(habitat!=null&&ForestHabitat.UNMODIFIED.contains(local),"Contradictory policy "+id);
                if(habitat!=null){adapted++;assertNotNull(ForestHabitat.familyForBiome(id),id);}
            }
        }
        assertTrue(count>=60);assertTrue(adapted>=29);
        assertNull(ForestHabitat.forBiome("other:forest"));assertNull(ForestHabitat.familyForBiome("other:jungle"));
    }
    @Test void nativeCandidateLimitsPreserveGrasslandAndSavannaAgainstJungleCanopy(){
        double grass=0,savanna=0,tropical=0,woodland=0;
        for(int x=-768;x<768;x+=13)for(int z=-768;z<768;z+=17){
            grass+=ForestEcology.sample(73,x,z,ForestEcology.Family.TEMPERATE,GOOD,ForestHabitat.GRASSLAND).retention();
            savanna+=ForestEcology.sample(73,x,z,ForestEcology.Family.DRY_WOODLAND,GOOD,ForestHabitat.DRY_SAVANNA).retention();
            tropical+=ForestEcology.sample(73,x,z,ForestEcology.Family.TROPICAL,GOOD,ForestHabitat.TROPICAL_CANOPY).retention();
            woodland+=ForestEcology.sample(73,x,z,ForestEcology.Family.TEMPERATE,GOOD,ForestHabitat.WOODLAND).retention();
        }
        assertTrue(tropical>savanna*1.5);assertTrue(woodland>grass*1.5);
    }
    @Test void wetlandSpeciesTolerateLowWetGroundWhileUplandSpeciesLoseVigour(){
        var wet=new ForestEcology.Site(64,63,.1,0,.35,.8,1,0);
        var high=new ForestEcology.Site(130,63,.1,0,.35,.8,0,0);
        var family=ForestEcology.Family.TEMPERATE;
        for(int x=-200;x<200;x+=11){
            var wetland=ForestGrowth.sample(42,x,19,family,wet,ForestHabitat.WETLAND);
            var upland=ForestGrowth.sample(42,x,19,family,wet,ForestHabitat.WOODLAND);
            assertTrue(wetland.productivity()>upland.productivity()*1.7);
            assertTrue(ForestEcology.sample(42,x,19,family,wet,ForestHabitat.WETLAND).retention()
                >ForestEcology.sample(42,x,19,family,high,ForestHabitat.WETLAND).retention());
        }
    }
    @Test void openHabitatTreesHaveBroaderCrownsThanClosedCanopyOnTheSameSite(){
        double open=0,closed=0;
        for(int x=-500;x<500;x+=7){
            open+=ForestGrowth.sample(42,x,19,ForestEcology.Family.TEMPERATE,GOOD,ForestHabitat.GRASSLAND).crownSpread();
            closed+=ForestGrowth.sample(42,x,19,ForestEcology.Family.TEMPERATE,GOOD,ForestHabitat.CLOSED_FOREST).crownSpread();
        }
        assertTrue(open>closed*1.5);
    }
    @Test void everyHabitatRemainsFiniteAndDeterministicAcrossSignedCoordinates(){
        for(var habitat:ForestHabitat.values())for(int x=-256;x<=256;x+=13){
            var a=ForestEcology.sample(42,x,-x,ForestEcology.Family.TEMPERATE,GOOD,habitat);
            assertEquals(a,ForestEcology.sample(42,x,-x,ForestEcology.Family.TEMPERATE,GOOD,habitat));
            for(double value:new double[]{a.retention(),a.opening(),a.stand(),a.stress()})assertTrue(Double.isFinite(value)&&value>=0&&value<=1);
            double adjacent=ForestEcology.sample(42,x+1,-x,ForestEcology.Family.TEMPERATE,GOOD,habitat).retention();
            assertTrue(Math.abs(a.retention()-adjacent)<.08);
        }
    }
    @Test void ordinaryNativeCherryGroveKeepsCandidatesAndLowBroadArchitecture(){
        var ordinary=new ForestEcology.Site(98,63,.30,-.2,.1556,-.217,0,.5);
        var ridge=new ForestEcology.Site(180,63,1.15,3,.1556,-.9,0,.5);
        var highCold=new ForestEcology.Site(280,63,.30,-.2,-.65,-.217,0,.5);
        double retained=0,exposed=0,cold=0;int n=0;
        for(int x=-768;x<768;x+=13)for(int z=-768;z<768;z+=17){
            var family=ForestEcology.Family.TEMPERATE;var habitat=ForestHabitat.OPEN_GROVE;
            retained+=ForestEcology.sample(42,x,z,family,ordinary,habitat).retention();
            exposed+=ForestEcology.sample(42,x,z,family,ridge,habitat).retention();
            cold+=ForestEcology.sample(42,x,z,family,highCold,habitat).retention();n++;
            var growth=ForestGrowth.sample(42,x,z,family,ordinary,habitat);
            assertTrue(growth.heightScale()>=.8&&growth.heightScale()<=1.15);
            assertTrue(growth.competition()<.5);
        }
        assertEquals(ForestHabitat.OPEN_GROVE,ForestHabitat.forBiome("minecraft:cherry_grove"));
        assertTrue(retained/n>.75&&retained/n<.96,"Native grove capacity lost: "+retained/n);
        assertTrue(retained>exposed*2);assertTrue(retained>cold*4);
    }
}
