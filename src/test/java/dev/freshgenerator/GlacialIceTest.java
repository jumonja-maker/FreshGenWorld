package dev.freshgenerator;

import dev.freshgenerator.core.GlacialIce;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlacialIceTest {
    private static final GlacialIce.LandSite GOOD=new GlacialIce.LandSite(225,.30,1.8,.94,.95,.30,0);
    @Test void warmWaterAndDryOrShallowGroundCannotAcquireIcebergs(){
        for(int z=-512;z<=512;z+=16)for(int x=-512;x<=512;x+=16){
            for(double cold:new double[]{0,.1,.35})assertFalse(GlacialIce.ocean(42,x,z,63,-64,cold).present());
            for(int floor:new int[]{58,62,63,80,300})assertFalse(GlacialIce.ocean(42,x,z,63,floor,1).present());
        }
    }
    @Test void exposedMassesAlwaysHaveBoundedSolidUnderwaterKeels(){
        int present=0;
        for(int floor:new int[]{-64,-20,20,50})for(double cold:new double[]{.45,.7,1})
            for(int z=-768;z<=768;z+=16)for(int x=-768;x<=768;x+=16){
                var c=GlacialIce.ocean(37,x,z,63,floor,cold);if(!c.present())continue;present++;
                assertTrue(c.baseY()>=Math.max(-63,floor+1));assertTrue(c.baseY()<=61);
                assertTrue(c.topY()>=64&&c.topY()<=108);assertTrue(c.snowDepth()>=1&&c.snowDepth()<=3);
            }
        assertTrue(present>500);
    }
    @Test void worldCoordinateResultsDoNotDependOnTraversalOrParallelOrder(){
        var serial=IntStream.range(0,1200).mapToObj(i->GlacialIce.ocean(91,i*7-4200,31-i*3,63,-48,1)).toList();
        var parallel=IntStream.range(0,1200).parallel().mapToObj(i->GlacialIce.ocean(91,i*7-4200,31-i*3,63,-48,1)).toList();
        assertEquals(serial,parallel);
        for(int i=1199;i>=0;i--)assertEquals(serial.get(i),GlacialIce.ocean(91,i*7-4200,31-i*3,63,-48,1));
        var land=IntStream.range(0,800).mapToObj(i->GlacialIce.land(18,i-400,47,GOOD)).toList();
        assertEquals(land,IntStream.range(0,800).parallel().mapToObj(i->GlacialIce.land(18,i-400,47,GOOD)).toList());
    }
    @Test void signedChunkAndObjectCellEdgesDoNotConcentrateIceCutsOrBreakKeels(){
        // Compare one-block differences across actual chunk/object-cell edges
        // against nearby interior differences. A misplaced cell-relative origin
        // or truncating division at negative coordinates would concentrate cuts
        // at these edges, even if repeated samples remained deterministic.
        for(int cell:new int[]{16,48,192,384}){
            long edgeEnergy=0,insideEnergy=0;int edgeOutlines=0,insideOutlines=0,connectedPairs=0;
            for(long seed:new long[]{42,-3356723558182187063L,91,37,0,8173})
            for(int boundary=-4;boundary<=4;boundary++)for(int axis=0;axis<2;axis++)for(int offset=0;offset<=1;offset++)
            for(int along=-768;along<=768;along+=7){
                // Both sides matter: truncation toward zero changes its cell
                // just AFTER a negative multiple, unlike floor division.
                int seam=boundary*cell+offset;
                var a=GlacialIce.ocean(seed,axis==0?seam-1:along,axis==0?along:seam-1,63,-55,1);
                var b=GlacialIce.ocean(seed,axis==0?seam:along,axis==0?along:seam,63,-55,1);
                var c=GlacialIce.ocean(seed,axis==0?seam-8:along,axis==0?along:seam-8,63,-55,1);
                var d=GlacialIce.ocean(seed,axis==0?seam-7:along,axis==0?along:seam-7,63,-55,1);
                edgeEnergy+=Math.abs((a.present()?a.topY():63)-(b.present()?b.topY():63));
                insideEnergy+=Math.abs((c.present()?c.topY():63)-(d.present()?d.topY():63));
                if(a.present()!=b.present())edgeOutlines++;
                if(c.present()!=d.present())insideOutlines++;
                if(a.present()&&b.present()){
                    connectedPairs++;
                    // Face-adjacent columns overlap through the underwater keel,
                    // rather than becoming two detached, above-water caps.
                    assertTrue(Math.max(a.baseY(),b.baseY())<=61);
                    assertTrue(Math.min(a.topY(),b.topY())>=64);
                }
            }
            assertTrue(connectedPairs>1000,"Insufficient cross-edge ice coverage at cell "+cell);
            assertTrue(insideEnergy>0&&insideOutlines>0);
            // Fixed seeds make this a reproducible spatial-regression check,
            // not a claim that individual natural outlines must be smooth.
            assertTrue(edgeEnergy<insideEnergy*1.3,"Height cuts concentrate at cell "+cell);
            assertTrue(edgeOutlines<insideOutlines*1.3,"Outlines concentrate at cell "+cell);
        }
    }
    @Test void sampledIceFieldHasManySmallFormsAndRarerHighMassifs(){
        int small=0,large=0,peak=0;Set<Integer> heights=new HashSet<>(),drafts=new HashSet<>();
        for(int z=-1536;z<=1536;z+=8)for(int x=-1536;x<=1536;x+=8){
            var c=GlacialIce.ocean(42,x,z,63,-55,1);if(!c.present())continue;
            int h=c.topY()-63;if(h<10)small++;if(h>=15)large++;
            heights.add(h);drafts.add(63-c.baseY());peak=Math.max(peak,h);
        }
        assertTrue(small>large*2&&large>50);assertTrue(peak>=35&&peak<=45);
        assertTrue(heights.size()>25&&drafts.size()>30);
    }
    @Test void largeMassifHasConnectedCoreAcrossChunksAndUnequalProfiles(){
        // Find a large form, then independently audit it densely at block scale.
        int px=0,pz=0,peak=0;
        for(int z=-1536;z<=1536;z+=8)for(int x=-1536;x<=1536;x+=8){
            int top=GlacialIce.ocean(42,x,z,63,-55,1).topY();
            if(top>peak){peak=top;px=x;pz=z;}
        }
        assertTrue(peak>=98);
        int side=145,x0=px-side/2,z0=pz-side/2;var cols=new GlacialIce.OceanColumn[side*side];
        for(int z=0;z<side;z++)for(int x=0;x<side;x++)cols[z*side+x]=GlacialIce.ocean(42,x0+x,z0+z,63,-55,1);
        int start=(pz-z0)*side+px-x0;assertTrue(cols[start].topY()>=98);
        boolean[] seen=new boolean[cols.length];ArrayDeque<Integer> q=new ArrayDeque<>();q.add(start);seen[start]=true;
        int count=0,minX=side,minZ=side,maxX=0,maxZ=0,unequal=0;
        while(!q.isEmpty()){
            int i=q.remove(),x=i%side,z=i/side;var c=cols[i];count++;
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
            assertTrue(c.baseY()<63&&c.topY()>63); // Every column overlaps at the waterline.
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                int nx=x+d[0],nz=z+d[1];if(nx<0||nz<0||nx>=side||nz>=side)continue;
                int ni=nz*side+nx;if(!seen[ni]&&cols[ni].present()){seen[ni]=true;q.add(ni);}
            }
            int reflected=z*side+(side-1-x);if(cols[reflected].present()&&cols[reflected].topY()!=c.topY())unequal++;
        }
        assertTrue(count>1500&&count<11000);assertTrue(maxX-minX>=40&&maxZ-minZ>=40);
        assertTrue(maxX-minX<=125&&maxZ-minZ<=125);assertTrue(unequal>100);
        assertTrue(Math.floorDiv(x0+maxX,16)-Math.floorDiv(x0+minX,16)>=3);
    }
    @Test void landAccumulationRespondsToShelterSupplyAndColdInsteadOfBlanketingEveryMountain(){
        int protectedIce=0,exposedIce=0,steepIce=0,warmIce=0,unsupplied=0,iceCap=0;
        for(int x=-400;x<400;x+=7){
            protectedIce+=GlacialIce.land(42,x,31,GOOD).thickness();
            exposedIce+=GlacialIce.land(42,x,31,new GlacialIce.LandSite(225,.30,-1,.94,.95)).thickness();
            steepIce+=GlacialIce.land(42,x,31,new GlacialIce.LandSite(225,1.6,1.8,.94,.95)).thickness();
            warmIce+=GlacialIce.land(42,x,31,new GlacialIce.LandSite(225,.30,1.8,.40,.95)).thickness();
            unsupplied+=GlacialIce.land(42,x,31,new GlacialIce.LandSite(225,.30,1.8,.94,0)).thickness();
            iceCap+=GlacialIce.land(42,x,31,new GlacialIce.LandSite(280,.15,0,1,0)).thickness();
        }
        assertTrue(protectedIce>exposedIce*2&&protectedIce>unsupplied*2);
        assertEquals(0,steepIce);assertEquals(0,warmIce);assertTrue(iceCap>1000);
    }
    @Test void glacierRimsThinGraduallyAndIceStaysBelowBuildLimit(){
        int previous=0,transitions=0;
        for(double concavity=-.6;concavity<1.81;concavity+=.025){
            var c=GlacialIce.land(42,137,31,new GlacialIce.LandSite(225,.3,concavity,.94,.95));
            assertTrue(c.thickness()>=previous&&c.thickness()-previous<=2);
            if(c.thickness()>0&&c.thickness()<28)transitions++;previous=c.thickness();
            assertTrue(c.coverage()>=0&&c.coverage()<=1);
        }
        assertTrue(transitions>15);
        for(int height:new int[]{300,310,318,319,330})assertTrue(GlacialIce.land(42,17,31,new GlacialIce.LandSite(height,.1,2,1,1)).thickness()<=Math.max(0,319-height));
    }
    @Test void crevassesRunAcrossLocalFlowAndKeepBasalIceWithoutSnowBridges(){
        for(int rotation=0;rotation<2;rotation++){
            long xEnergy=0,zEnergy=0;int cracks=0;
            var site=new GlacialIce.LandSite(225,.45,1.8,.95,1,rotation==0?.45:0,rotation==0?0:.45);
            for(int z=-64;z<64;z++)for(int x=-64;x<64;x++){
                var a=GlacialIce.land(42,x,z,site);int d=a.crevasseDepth();
                xEnergy+=Math.abs(d-GlacialIce.land(42,x+1,z,site).crevasseDepth());
                zEnergy+=Math.abs(d-GlacialIce.land(42,x,z+1,site).crevasseDepth());
                if(d>0){cracks++;assertTrue(a.thickness()-d>=3);assertEquals(0,a.snowDepth());}
            }
            assertTrue(cracks>100);
            if(rotation==0)assertTrue(xEnergy>zEnergy*2);else assertTrue(zEnergy>xEnergy*2);
        }
    }
    @Test void capShareAndBaseSnowSupportTheCallerLimits(){
        // Audit R8 OI-02: a flat cold plateau is a cap, a supplied sheltered valley a tongue.
        var plateau=new GlacialIce.LandSite(250,0,0,1,0);
        assertTrue(GlacialIce.capShare(plateau)>.99,"flat plateau is an ice cap");
        assertEquals(0,GlacialIce.capShare(new GlacialIce.LandSite(170,.3,1.8,.94,.95)),1e-9,"low valley tongue (no cap branch)");
        // Audit R8 OI-03: where no crevasse opens, the restored snow is exactly the profile snow.
        for(int x=-300;x<300;x++){
            var site=new GlacialIce.LandSite(225,.3,1.8,.94,.95);var c=GlacialIce.land(42,x,31,site);
            if(c.thickness()>0&&c.crevasseDepth()==0)assertEquals(c.snowDepth(),GlacialIce.baseSnow(site));
        }
    }
    @Test void invalidPhysicalInputsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->GlacialIce.ocean(1,0,0,63,-64,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->GlacialIce.ocean(1,0,0,63,-64,1.1));
        assertThrows(IllegalArgumentException.class,()->new GlacialIce.LandSite(200,-.1,0,.9,1));
        assertThrows(IllegalArgumentException.class,()->new GlacialIce.LandSite(200,.1,0,.9,1,Double.NaN,0));
        assertThrows(NullPointerException.class,()->GlacialIce.land(1,0,0,null));
    }
}
