package dev.freshgenerator.core;

import java.util.Objects;

/** Immutable, world-coordinate ice geometry. Values express Minecraft scale, not
 * a dynamic mass-balance, calving or climate simulation. No chunk state or RNG. */
public final class GlacialIce {
    private GlacialIce() { }
    private static final OceanColumn NO_OCEAN=new OceanColumn(1,0,0,false);
    private static final LandColumn NO_LAND=new LandColumn(0,0,0,false,0);
    public record OceanColumn(int baseY,int topY,int snowDepth,boolean blue) {
        public boolean present(){return baseY<=topY;}
    }
    /** Positive concavity denotes a sheltered hollow; slope is rise/run. */
    public record LandSite(double height,double slope,double concavity,double coldness,double uphillSnowSupply,
                           double gradientX,double gradientZ) {
        public LandSite(double height,double slope,double concavity,double coldness,double uphillSnowSupply) {
            this(height,slope,concavity,coldness,uphillSnowSupply,slope,0);
        }
        public LandSite {
            for(double v:new double[]{height,slope,concavity,coldness,uphillSnowSupply,gradientX,gradientZ})
                if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite glacier input");
            if(slope<0||coldness<0||coldness>1||uphillSnowSupply<0||uphillSnowSupply>1)
                throw new IllegalArgumentException("Invalid glacier site");
        }
    }
    public record LandColumn(int thickness,int snowDepth,int crevasseDepth,boolean moraine,double coverage) { }

    /** Continuous solid columns include both the exposed massif and its keel.
     * Ocean biome selection remains the adapter's responsibility. A shallow or
     * above-water floor cannot acquire an iceberg from this function. */
    public static OceanColumn ocean(long seed,int x,int z,int seaLevel,int seaFloor,double coldSuitability) {
        unitInput(coldSuitability);
        if(seaLevel< -50||seaLevel>270)throw new IllegalArgumentException("Unsupported sea level");
        if(coldSuitability<=.35||seaFloor>=seaLevel-5)return NO_OCEAN;
        double climate=smooth(.35,.85,coldSuitability);int bottom=seaLevel,top=seaLevel-1,snow=0;boolean blue=false;
        // Independent scales and jitter avoid rows of equally sized objects.
        for(int scale=0;scale<3;scale++){
            int cell=scale==0?48:scale==1?192:384;
            int cx=Math.floorDiv(x,cell),cz=Math.floorDiv(z,cell);
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                long id=hash(seed^((scale+1)*0x52C90A17L),cx+dx,cz+dz);
                double chance=scale==0?.78:scale==1?.30:.16;
                if(unit(id)>chance)continue;
                double centerX=(cx+dx+random(id,1))*cell;
                double centerZ=(cz+dz+random(id,2))*cell;
                double radius=scale==0?2.5+7.5*random(id,3):scale==1?10+10*random(id,3):23+20*random(id,3);
                // Early bounds matter when sampled for every column of a region.
                if(Math.abs(x-centerX)>radius*1.55||Math.abs(z-centerZ)>radius*1.55)continue;
                OceanColumn column=iceberg(id,x,z,centerX,centerZ,radius,scale,seaLevel,seaFloor,climate);
                if(!column.present())continue;
                bottom=Math.min(bottom,column.baseY());
                if(column.topY()>top){top=column.topY();snow=column.snowDepth();blue=column.blue();}
            }
        }
        return top<seaLevel?NO_OCEAN:new OceanColumn(bottom,top,snow,blue);
    }
    private static OceanColumn iceberg(long id,int x,int z,double cx,double cz,double radius,int scale,
                                        int sea,int floor,double climate) {
        double angle=random(id,4)*Math.PI*2,ca=Math.cos(angle),sa=Math.sin(angle);
        double px=x-cx,pz=z-cz;
        double u=(px*ca+pz*sa)/(radius*(.95+.20*random(id,5)));
        double v=(-px*sa+pz*ca)/(radius*(.72+.28*random(id,6)));
        double theta=Math.atan2(v,u),phase=random(id,7)*Math.PI*2;
        // A radial outline stays connected to its core while making uneven lobes
        // and erosional bays. Its parameters vary independently for each object.
        double outline=.96+.10*Math.sin(3*theta+phase)+.065*Math.sin(5*theta+random(id,8)*6.28)
            +.035*Math.sin(9*theta+random(id,9)*6.28);
        outline-=.16*Math.pow(Math.max(0,Math.cos(theta-random(id,10)*6.28)),12);
        double q=Math.hypot(u,v)/(outline*Math.sqrt(climate));
        if(q>=1)return NO_OCEAN;
        double edge=smooth(0,.23,1-q),rounded=Math.pow(Math.max(0,1-q*q),.48+.95*random(id,11));
        double angular=Math.max(0,1-Math.max(Math.abs(u)*.72,Math.abs(v)*(.65+.40*random(id,12))));
        double lobes=0;
        int peaks=scale==2?4:scale==1?3:2;
        for(int p=0;p<peaks;p++){
            double a=random(id,20+p*4)*6.28,d=.10+.45*random(id,21+p*4);
            double distance=Math.hypot(u-Math.cos(a)*d,v-Math.sin(a)*d);
            double peak=Math.max(0,1-distance/(.40+.45*random(id,22+p*4)));
            lobes=Math.max(lobes,peak*(.70+.30*random(id,23+p*4)));
        }
        double style=random(id,13),profile=edge*(.22+.78*Math.max(lobes,(1-style)*rounded+style*angular));
        double maximum=scale==0?3+9*random(id,14):scale==1?10+13*random(id,14):20+25*random(id,14);
        int exposed=Math.max(1,(int)Math.floor(maximum*Math.sqrt(climate)*profile));
        int weather=unit(hash(id^0xD0A9L,x,z))>.88&&q>.60?1:0;
        exposed=Math.max(1,exposed-weather);
        // Draft is constrained by the available water column/world floor.
        int draft=Math.max(3,(int)Math.ceil(maximum*(2.4+2.0*random(id,15))*Math.sqrt(Math.max(0,1-q*q))));
        int base=Math.max(Math.max(-63,floor+1),sea-draft);
        if(base>=sea-1)return NO_OCEAN;
        int top=sea+exposed;
        int snow=1+(int)Math.floor(2*smooth(.3,.9,profile)*random(id,16));
        return new OceanColumn(base,top,snow,random(id,17)>.68&&q<.85);
    }

    /** Mountain ice accumulates in sufficiently cold, supplied, sheltered terrain.
     * Broad ice caps and lower tongues have distinct topographic responses. */
    public static LandColumn land(long seed,int x,int z,LandSite site) {
        Objects.requireNonNull(site);
        double cold=smooth(.50,.90,site.coldness()),high=smooth(115,185,site.height());
        double shelter=smooth(-.6,1.8,site.concavity());
        double retention=1-smooth(.65,1.45,site.slope());
        double supply=.15+.85*site.uphillSnowSupply();
        double tongue=cold*high*supply*(.25+.75*shelter);
        double cap=.72*smooth(.82,.98,site.coldness())*smooth(195,270,site.height())
            *(1-smooth(.35,.90,site.slope()));
        double potential=Math.max(tongue,cap)*retention;
        double basin=.65*noise(seed^0x913CE7L,x/237.0,z/237.0)+.35*noise(seed^0x719B32L,x/79.0,z/79.0);
        // Topographic supply/shelter determine the tongue. This weak field only
        // varies its margin and cannot put a glacier on unsuitable terrain.
        double margin=.28+.06*basin;
        double coverage=smooth(0,.58,potential-margin);
        int maximum=Math.max(0,Math.min(28,(int)Math.floor(319-site.height())));
        int thickness=Math.min(maximum,(int)Math.floor(28*coverage));
        if(thickness==0)return NO_LAND;
        // Crevasses occupy only part of a patch and never sever its basal ice.
        double norm=Math.hypot(site.gradientX(),site.gradientZ());
        double flowX=norm>.02?-site.gradientX()/norm:Math.cos(random(seed,119)*6.28);
        double flowZ=norm>.02?-site.gradientZ()/norm:Math.sin(random(seed,119)*6.28);
        // Fracture lines run across flow; their phase changes along its axis.
        double across=(x*flowX+z*flowZ)/8.5;
        double line=Math.abs(Math.sin(across+.8*noise(seed^0x3B950FL,x/31.0,z/31.0)));
        double strain=smooth(.12,.75,site.slope());
        double fissure=smooth(.92,.995,line)*smooth(-.10,.45,noise(seed^0xAAE751L,x/43.0,z/43.0));
        int crack=(int)Math.floor((2+8*strain)*fissure);
        crack=Math.max(0,Math.min(thickness-3,crack));
        boolean moraine=thickness<=5&&site.uphillSnowSupply()>.35
            &&noise(seed^0x12A0B9L,x/23.0,z/23.0)>.05;
        int snow=Math.max(1,Math.min(4,1+(int)Math.floor(3*cold*(1-.6*strain))));
        if(crack>0)snow=0;
        return new LandColumn(thickness,snow,crack,moraine,coverage);
    }
    private static void unitInput(double v){if(!Double.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Expected suitability 0..1");}
    private static double smooth(double a,double b,double v){double t=Math.max(0,Math.min(1,(v-a)/(b-a)));return Math.clamp(t*t*t*(t*(t*6-15)+10),0,1);}
    private static long hash(long seed,int x,int z){long v=seed+x*0x9E3779B97F4A7C15L+z*0xD1B54A32D192ED03L;v=(v^(v>>>30))*0xBF58476D1CE4E5B9L;v=(v^(v>>>27))*0x94D049BB133111EBL;return v^(v>>>31);}
    private static double unit(long v){return(v>>>11)*0x1.0p-53;}
    private static double random(long id,int salt){return unit(hash(id^0x759BA7E1L,salt,salt*31));}
    private static double noise(long seed,double x,double z){
        int ix=(int)Math.floor(x),iz=(int)Math.floor(z);double tx=smooth(0,1,x-ix),tz=smooth(0,1,z-iz);
        double a=2*unit(hash(seed,ix,iz))-1,b=2*unit(hash(seed,ix+1,iz))-1;
        double c=2*unit(hash(seed,ix,iz+1))-1,d=2*unit(hash(seed,ix+1,iz+1))-1;
        return(a+(b-a)*tx)*(1-tz)+(c+(d-c)*tx)*tz;
    }
}
