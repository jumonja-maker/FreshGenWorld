package dev.freshgenerator.core;

import java.util.Objects;

/** Static site-dependent tree stature, not a simulation of tree age, soil or wind.
 * Ecological directions are documented; coefficients express Minecraft scale.
 * Height and crown architecture respond separately, using immutable inputs only. */
public final class ForestGrowth {
    private ForestGrowth() { }
    private static final double[] SCALES = {.60, .80, 1.00, 1.25, 1.50};
    public record Profile(double heightScale, double crownSpread, double productivity,
                          double exposure, double competition, double cohort) {
        public Profile {
            if(!Double.isFinite(heightScale)||heightScale<SCALES[0]||heightScale>SCALES[4]
                    ||!Double.isFinite(crownSpread)||crownSpread<0||crownSpread>2)
                throw new IllegalArgumentException("Invalid tree dimensions");
            for(double v:new double[]{productivity,exposure,competition,cohort})
                if(!Double.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Invalid growth field");
        }
    }
    public record Shape(int sizeBand, int crownBand) {
        public Shape {
            if(sizeBand<0||sizeBand>=SCALES.length||crownBand<0||crownBand>2)
                throw new IllegalArgumentException("Invalid tree shape");
        }
    }
    public static double scale(int band) {
        if(band<0||band>=SCALES.length)throw new IllegalArgumentException("Invalid size band");
        return SCALES[band];
    }
    public static Profile sample(long seed,int x,int z,ForestEcology.Family family,ForestEcology.Site site) {
        return sample(seed,x,z,family,site,ForestHabitat.WOODLAND);
    }
    public static Profile sample(long seed,int x,int z,ForestEcology.Family family,ForestEcology.Site site,ForestHabitat habitat) {
        Objects.requireNonNull(family);Objects.requireNonNull(site);Objects.requireNonNull(habitat);
        var ecology=ForestEcology.sample(seed,x,z,family,site,habitat);
        double ridge=smooth(.10,3.0,site.convexity());
        double hollow=smooth(.10,3.0,-site.convexity());
        // Convex exposed ground is less sheltered. Edge alone is not a mountain ridge.
        double exposure=clamp(ridge*(.35+.65*smooth(100,230,site.height()))+.12*site.edge(),0,1);
        double water=site.moisture()+.10*hollow-.14*exposure;
        double supply=smooth(-.95,.35,water);
        double wetGround=site.shore()*(1-smooth(site.seaLevel()+1,site.seaLevel()+6,site.height()))
            *smooth(-.1,.65,site.moisture());
        // Slope/convexity are limited rooting-depth proxies, not measured soil fertility.
        double rooting=1-.50*smooth(.35,1.65,site.slope())-.16*ridge;
        double warmth=switch(family){
            case CONIFER -> .60+.40*smooth(-1.1,-.25,site.temperature());
            case TEMPERATE -> .35+.65*smooth(-.95,.15,site.temperature());
            case TROPICAL -> .35+.65*smooth(-.50,.25,site.temperature());
            case DRY_WOODLAND -> .55+.45*smooth(-.60,.20,site.temperature());
        };
        double moisture=switch(family){
            case CONIFER -> .38+.62*supply;
            case TEMPERATE -> .24+.76*supply;
            case TROPICAL -> .18+.82*supply;
            case DRY_WOODLAND -> .48+.52*supply;
        };
        double treeline=(switch(family){
            case CONIFER -> 215; case TEMPERATE -> 205;
            case TROPICAL -> 240; case DRY_WOODLAND -> 200;
        })+30*clamp(site.temperature(),-1,1);
        double coldExposure=1-.82*smooth(treeline-30,treeline+25,site.height());
        double floodPenalty=habitat==ForestHabitat.MANGROVE?.08:habitat==ForestHabitat.WETLAND?.18:.65;
        double productivity=clamp(moisture*warmth*rooting*(1-floodPenalty*wetGround)
            *(1-.42*exposure)*coldExposure*habitat.lowlandAffinity(site),0,1);
        // Potential neighbors/light competition is independent of actual decoration order.
        double competition=clamp(ecology.stand()*(1-.90*ecology.opening())
            *(1-.70*site.edge())*(.65+.35*supply),0,1);
        if(family==ForestEcology.Family.DRY_WOODLAND)competition*=.45;
        if(habitat.open())competition*=.45;
        if(habitat==ForestHabitat.CLOSED_FOREST||habitat==ForestHabitat.TROPICAL_CANOPY)
            competition=clamp(competition*1.12,0,1);
        double cohort=clamp(.50+.36*ForestEcology.noise(seed^0x597BC11AL,x/113.0,z/113.0)
            +.14*ForestEcology.noise(seed^0x2AB9019CL,x/37.0,z/37.0),0,1);
        // Broad site potential dominates; cohort mosaics preserve uneven canopy layers.
        double height=(.61+1.02*productivity)*(.90+.20*competition)*(.78+.22*cohort);
        if(family==ForestEcology.Family.DRY_WOODLAND)height*=.90;
        if(habitat==ForestHabitat.ALPINE_EDGE)height*=1-.22*smooth(130,250,site.height());
        if(habitat==ForestHabitat.GRASSLAND||habitat==ForestHabitat.COLD_OPEN)height*=.92;
        // Native cherry architecture is a low, broad grove tree. Preserve its
        // branch proportions instead of giving it the full tall-forest range.
        if(habitat==ForestHabitat.OPEN_GROVE)height=clamp((.80+.35*productivity)*(.92+.08*cohort),.80,1.15);
        double crown=1.65-1.25*competition-.80*exposure-.30*(1-productivity)
            +.10*ForestEcology.noise(seed^0x611AAD8FL,x/59.0,z/59.0);
        if(habitat.open())crown+=.18;
        return new Profile(clamp(height,SCALES[0],SCALES[4]),clamp(crown,0,2),
            productivity,exposure,competition,cohort);
    }
    /** Stochastic interpolation preserves gradual distributions across band boundaries. */
    public static Shape shape(long seed,int x,int z,Profile profile) {
        Objects.requireNonNull(profile);
        if(!Double.isFinite(profile.heightScale())||profile.heightScale()<SCALES[0]
                ||profile.heightScale()>SCALES[4]||!Double.isFinite(profile.crownSpread())
                ||profile.crownSpread()<0||profile.crownSpread()>2)
            throw new IllegalArgumentException("Invalid growth profile");
        // Some recruited trees remain subordinate within otherwise tall stands.
        // This is a static mixture, not measured shading or chronological age.
        double individualHeight=profile.heightScale();
        if(unit(seed^0xF7BD91A3L,x,z)<.20*profile.competition())
            individualHeight=Math.max(SCALES[0],individualHeight*(.58+.20*profile.cohort()));
        int band=SCALES.length-1;
        for(int i=0;i<SCALES.length-1;i++)if(individualHeight<=SCALES[i+1]){
            double fraction=(individualHeight-SCALES[i])/(SCALES[i+1]-SCALES[i]);
            band=i+(unit(seed^0xA14F87CL,x,z)<fraction?1:0);break;
        }
        int crown=(int)Math.floor(profile.crownSpread());
        if(crown<2&&unit(seed^0xE7D3AB19L,x,z)<profile.crownSpread()-crown)crown++;
        return new Shape(band,crown);
    }
    private static double smooth(double a,double b,double v){return ForestEcology.smooth(a,b,v);}
    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
    private static double unit(long seed,int x,int z){
        long n=seed+x*0x9E3779B97F4A7C15L+z*0xD1B54A32D192ED03L;
        n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;n=(n^(n>>>27))*0x94D049BB133111EBL;n^=n>>>31;
        return(n>>>11)*0x1.0p-53;
    }
}
