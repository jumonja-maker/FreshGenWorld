package dev.freshgenerator.core;

import java.util.Objects;

/** Spatial recruitment, not forest templates or a tree-age simulation.
 * Coefficients are conservative game-scale design values, NOT fitted stem densities.
 * Outputs depend only on seed, location and immutable environmental inputs.
 */
public final class ForestEcology {
    private ForestEcology() { }
    public enum Family { TEMPERATE, CONIFER, TROPICAL, DRY_WOODLAND }
    public record Site(double height, double seaLevel, double slope, double convexity,
                       double temperature, double moisture, double shore, double edge) {
        public Site {
            for (double v : new double[]{height,seaLevel,slope,convexity,temperature,moisture,shore,edge})
                if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite ecological input");
            if (slope < 0 || shore < 0 || shore > 1 || edge < 0 || edge > 1)
                throw new IllegalArgumentException("Invalid slope/proximity input");
        }
    }
    public record Fields(double retention, double opening, double stand, double stress) { }
    public static Fields sample(long seed,int x,int z,Family family,Site site) {
        return sample(seed,x,z,family,site,ForestHabitat.WOODLAND);
    }
    public static Fields sample(long seed,int x,int z,Family family,Site site,ForestHabitat habitat) {
        Objects.requireNonNull(family);Objects.requireNonNull(site);Objects.requireNonNull(habitat);
        double wx=x+29*noise(seed^0x12E5L,x/173.0,z/173.0)
                +7*noise(seed^0x55A9L,x/47.0,z/47.0);
        double wz=z+29*noise(seed^0x5B31L,x/173.0,z/173.0)
                +7*noise(seed^0xA12BL,x/47.0,z/47.0);
        double broad=.65*noise(seed^0x87A1L,wx/233.0,wz/233.0)
                +.35*noise(seed^0x6EA3L,wx/97.0,wz/97.0);
        double group=noise(seed^0xDA35L,wx/31.0,wz/31.0);
        // No clearing centres/radii: a shared continuous field crosses stand boundaries.
        double patch=smooth(-.65,.65,broad);
        double openingField=.55*noise(seed^0x7D11L,wx/65.0,wz/65.0)
                +.30*noise(seed^0xA72BL,wx/23.0,wz/23.0)
                +.15*noise(seed^0xBC21L,wx/9.0,wz/9.0);
        double stand=clamp(switch(family) {
            case TEMPERATE -> .72+.24*patch+.07*group;
            case CONIFER -> .65+.30*patch+.08*group;
            case TROPICAL -> .82+.16*patch+.05*group;
            case DRY_WOODLAND -> .62+.32*patch+.09*group;
        },0,1);
        double exposure=smooth(.2,3.5,site.convexity())
                *(.35+.65*smooth(110,230,site.height()));
        // This is an ecological suitability proxy, not soil moisture or runoff simulation.
        double availableMoisture=site.moisture()+.08*smooth(.2,3,-site.convexity())-.12*exposure;
        double moisture=switch(family) {
            case TEMPERATE -> .40+.60*smooth(-.85,.30,availableMoisture);
            case CONIFER -> .55+.45*smooth(-.85,.25,availableMoisture);
            case TROPICAL -> .55+.45*smooth(-.70,.25,availableMoisture);
            case DRY_WOODLAND -> .50+.50*smooth(-.95,.35,availableMoisture);
        };
        // Reference lowland climate is used here; altitude has its own treeline term below.
        double warmth=switch(family) {
            case TEMPERATE -> .50+.50*smooth(-1,0,site.temperature());
            case CONIFER -> .64+.36*smooth(-1,-.35,site.temperature());
            case TROPICAL -> .55+.45*smooth(-.60,.05,site.temperature());
            case DRY_WOODLAND -> .60+.40*smooth(-.60,.10,site.temperature());
        };
        double productivity=moisture*warmth;
        double opening=smooth(.15,.65,openingField+.08*(1-productivity));
        // Savanna is a grass/woodland mosaic, not a uniformly thinned rainforest.
        // A static regional openness proxy stands in for absent disturbance history.
        if(habitat==ForestHabitat.DRY_SAVANNA||habitat==ForestHabitat.DRY_SCRUB)
            opening=clamp(opening+.28*(1-patch)+.18*(1-smooth(-.75,.25,availableMoisture)),0,1);
        else if(habitat==ForestHabitat.TROPICAL_CANOPY||habitat==ForestHabitat.CLOSED_FOREST)
            opening*=.65;
        // Native jungle density is already high; canopy openings remain weaker there.
        double strength=switch(family) {
            case TEMPERATE -> .82;
            case CONIFER -> .90;
            case TROPICAL -> .58;
            case DRY_WOODLAND -> .76;
        };
        double slope=1-.85*smooth(.45,1.80,site.slope());
        // 0.9.0: above the treeline recruitment reaches zero; the former 4% floor
        // left scattered trees on any summit the native biome allowed.
        double altitude=altitude(family,site,broad);
        // Stable low-water-envelope proxy, not a physical flood model.
        double lowBank=site.shore()*(1-smooth(site.seaLevel()+1,site.seaLevel()+5,site.height()));
        double floodPenalty=habitat==ForestHabitat.MANGROVE?.06:habitat==ForestHabitat.WETLAND?.18:.65;
        double factor=productivity*slope*altitude*(1-.30*exposure)*(1-floodPenalty*lowBank)
                *(1-.30*smooth(0,1,site.edge()));
        factor*=habitat.lowlandAffinity(site);
        if(habitat==ForestHabitat.OPEN_GROVE){
            // The native cherry biome already supplies sparse grove candidates.
            // Applying a closed-forest climate/stand filter a second time erased
            // ordinary groves at normal native humidity (about -0.2). Preserve
            // that capacity, thinning mainly under actual site extremes. An open
            // crown is not itself evidence of an unsuitable forest-edge site.
            double dry=1-smooth(-.85,-.35,availableMoisture);
            double cold=1-smooth(-.95,-.35,site.temperature());
            double grove=(1-.85*dry)*(1-.80*cold)*(1-.90*smooth(.65,1.65,site.slope()))
                *altitude*(1-.35*exposure)*(1-.65*lowBank);
            double retained=(.82+.15*patch)*(.95-.15*opening)*grove;
            return new Fields(clamp(retained,0,1),opening,stand,1-clamp(grove,0,1));
        }
        double population=switch(habitat){
            case GRASSLAND -> .22+.38*smooth(-.45,.45,availableMoisture)*patch;
            case COLD_OPEN -> .38+.30*patch;
            case DRY_SAVANNA -> (.52+.38*patch)*(.58+.42*smooth(-.85,.25,availableMoisture));
            case DRY_SCRUB -> (.35+.45*patch)*(.45+.55*smooth(-.85,.25,availableMoisture));
            case SPARSE_TROPICAL -> .62+.22*patch;
            case CLOSED_FOREST,TROPICAL_CANOPY -> 1.10;
            case ALPINE_EDGE -> .82;
            case WETLAND,MANGROVE,WOODLAND,OPEN_GROVE -> 1;
        };
        return new Fields(clamp(population*stand*(1-strength*opening)*factor,0,1),opening,stand,1-factor);
    }
    /** Treeline factor in [0,1]; 1 below the band, 0 well above the local treeline. */
    public static double altitude(Family family,Site site,double broad) {
        double treeline=(switch(family) {
            case CONIFER -> 215;
            case TROPICAL -> 240;
            case TEMPERATE -> 205;
            case DRY_WOODLAND -> 200;
        })+30*clamp(site.temperature(),-1,1)+12*broad;
        return 1-smooth(treeline-16,treeline+34,site.height());
    }
    /** Same treeline factor at a position (shared broad field), for understory use. */
    public static double altitude(long seed,int x,int z,Family family,Site site) {
        double wx=x+29*noise(seed^0x12E5L,x/173.0,z/173.0)+7*noise(seed^0x55A9L,x/47.0,z/47.0);
        double wz=z+29*noise(seed^0x5B31L,x/173.0,z/173.0)+7*noise(seed^0xA12BL,x/47.0,z/47.0);
        double broad=.65*noise(seed^0x87A1L,wx/233.0,wz/233.0)+.35*noise(seed^0x6EA3L,wx/97.0,wz/97.0);
        return altitude(family,site,broad);
    }
    public static boolean retain(long seed,int x,int z,Fields fields) {
        Objects.requireNonNull(fields);return unit(seed^0x98D7F43AL,x,z)<fields.retention();
    }
    public static double smooth(double lo,double hi,double v) {
        if(!Double.isFinite(lo)||!Double.isFinite(hi)||hi<=lo||!Double.isFinite(v))throw new IllegalArgumentException("Invalid interval");
        double t=clamp((v-lo)/(hi-lo),0,1);return t*t*t*(t*(6*t-15)+10);
    }
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    public static double noise(long seed,double x,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(z))throw new IllegalArgumentException("Non-finite coordinates");
        long a=(long)Math.floor(x),b=(long)Math.floor(z);double tx=smooth(0,1,x-a),tz=smooth(0,1,z-b);
        double p=unit(seed,a,b),q=unit(seed,a+1,b),r=unit(seed,a,b+1),s=unit(seed,a+1,b+1);
        return 2*((p+(q-p)*tx)*(1-tz)+(r+(s-r)*tx)*tz)-1;
    }
    private static double unit(long seed,long x,long z) {
        long n=seed+x*0x9E3779B97F4A7C15L+z*0xD1B54A32D192ED03L;
        n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;n=(n^(n>>>27))*0x94D049BB133111EBL;n^=n>>>31;
        return(n>>>11)*0x1.0p-53;
    }
}
