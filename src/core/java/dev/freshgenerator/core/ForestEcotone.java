package dev.freshgenerator.core;

import java.util.*;

/** A bounded transition in expected recruitment, not a biome-ID replacement.
 * Native feature capacities and ecological suitability are mixed together;
 * merely mixing retention leaves the original hundredfold candidate jump.
 */
public final class ForestEcotone {
    private ForestEcotone() { }
    public static final int GRID=16, RADIUS=32, MAX_PROPOSALS=64;
    /** Own share when the stencil holds no compatible forest at all (limit of zero support). */
    public static final double EMPTY_SHARE=1e-9;
    public record Pool(String biome,double capacity) {
        public Pool {Objects.requireNonNull(biome);if(!Double.isFinite(capacity)||capacity<=0||capacity>MAX_PROPOSALS)throw new IllegalArgumentException("Invalid native tree capacity");}
    }
    public record Share(Pool pool,double weight) {
        public Share {Objects.requireNonNull(pool);if(!Double.isFinite(weight)||weight<=0||weight>1)throw new IllegalArgumentException("Invalid ecotone weight");}
    }
    @FunctionalInterface public interface Source {Pool sample(int x,int z);}

    /** Keep the pale garden's gameplay-bearing native palette in its own biome.
     * Its density and stature can still form an ecotone; the native selector
     * keeps the pale oak/creaking-heart distinction on the native side only. */
    public static boolean mayBorrowPalette(String own,String donor){
        if(own==null||donor==null)return false;
        return own.equals(donor)||(!own.equals("minecraft:pale_garden")&&!donor.equals("minecraft:pale_garden"));
    }

    public static boolean compatible(String a,String b,ForestEcology.Site site){
        var ah=ForestHabitat.forBiome(a);var bh=ForestHabitat.forBiome(b);
        if(ah==null||bh==null)return false;
        if(a.equals(b))return true;
        // A water-rooted forest boundary requires a different establishment
        // niche. Do not export terrestrial candidates into mangrove water.
        if(ah.waterTolerant()||bh.waterTolerant())return ah==bh;
        var af=ForestHabitat.familyForBiome(a);var bf=ForestHabitat.familyForBiome(b);
        if(af==bf)return true;
        double t=site.temperature(),m=site.moisture(),h=site.height();
        if((af==ForestEcology.Family.CONIFER&&bf==ForestEcology.Family.TEMPERATE)
            ||(bf==ForestEcology.Family.CONIFER&&af==ForestEcology.Family.TEMPERATE))
            return !a.equals("minecraft:snowy_taiga")&&!b.equals("minecraft:snowy_taiga")
                &&t>-.42&&t<.55&&h<175+25*t;
        if((af==ForestEcology.Family.DRY_WOODLAND&&bf==ForestEcology.Family.TEMPERATE)
            ||(bf==ForestEcology.Family.DRY_WOODLAND&&af==ForestEcology.Family.TEMPERATE))
            return t>-.10&&m>-.80&&m<.20&&h<200;
        if((af==ForestEcology.Family.DRY_WOODLAND&&bf==ForestEcology.Family.TROPICAL)
            ||(bf==ForestEcology.Family.DRY_WOODLAND&&af==ForestEcology.Family.TROPICAL))
            return t>0&&m>-.45&&m<.35&&h<200;
        return false;
    }

    /** Fixed world-coordinate stencil and interpolation. There are no chunk
     * votes or state from previously placed trees. Weights of treeless or
     * incompatible neighbours are kept out of the sum, so recruitment falls
     * smoothly to zero toward them. A homogeneous interior has one share of weight 1. */
    public static List<Share> blend(int x,int z,Pool own,ForestEcology.Site site,Source source){
        Objects.requireNonNull(own);Objects.requireNonNull(site);Objects.requireNonNull(source);
        int x0=Math.floorDiv(x,GRID)*GRID,z0=Math.floorDiv(z,GRID)*GRID;
        double tx=ForestEcology.smooth(0,1,(x-x0)/(double)GRID),tz=ForestEcology.smooth(0,1,(z-z0)/(double)GRID);
        var weights=new LinkedHashMap<Pool,Double>();double total=0,treeless=0;
        for(int cx=0;cx<=1;cx++)for(int cz=0;cz<=1;cz++){
            double corner=(cx==0?1-tx:tx)*(cz==0?1-tz:tz);if(corner==0)continue;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                Pool pool=source.sample(x0+cx*GRID+dx*RADIUS,z0+cz*GRID+dz*RADIUS);
                double weight=corner*(dx==0?2:1)*(dz==0?2:1)/16;
                // 0.9.0: a treeless or incompatible neighbour is a zero-capacity share.
                // Renormalising it away kept a full forest up to the last column.
                if(pool==null||!compatible(own.biome(),pool.biome(),site)){treeless+=weight;continue;}
                weights.merge(pool,weight,Double::sum);total+=weight;
            }
        }
        // 0.9.1 audit R4 (F-1): no compatible forest anywhere in the stencil is the limit of a
        // vanishing forest weight, not a full forest. The former share of 1 jumped from 0.011%
        // to 79% retention between neighbouring columns. A negligible own share keeps the
        // stature/palette defined while recruitment stays at that limit (about zero).
        if(weights.isEmpty())return List.of(new Share(own,EMPTY_SHARE));
        double all=total+treeless;
        var result=new ArrayList<Share>();for(var entry:weights.entrySet())result.add(new Share(entry.getKey(),entry.getValue()/all));
        return List.copyOf(result);
    }

    public static ForestEcology.Fields recruitment(long seed,int x,int z,ForestEcology.Site site,List<Share> shares,double proposalMean){
        if(!Double.isFinite(proposalMean)||proposalMean<=0)throw new IllegalArgumentException("Invalid proposal budget");
        double expected=0,opening=0,stand=0,stress=0;
        for(var share:shares){
            var pool=share.pool();var fields=fields(seed,x,z,site,pool);
            expected+=share.weight()*pool.capacity()*fields.retention();
            opening+=share.weight()*fields.opening();stand+=share.weight()*fields.stand();stress+=share.weight()*fields.stress();
        }
        return new ForestEcology.Fields(Math.clamp(expected/proposalMean,0,1),opening,stand,stress);
    }
    public static ForestGrowth.Profile growth(long seed,int x,int z,ForestEcology.Site site,List<Share> shares){
        double height=0,crown=0,productivity=0,exposure=0,competition=0,cohort=0;
        double sum=0;for(var share:shares)sum+=share.weight();
        // Stature is an average over the forest shares only; treeless weight thins, not shrinks.
        for(var share:shares){var pool=share.pool();var p=ForestGrowth.sample(seed,x,z,ForestHabitat.familyForBiome(pool.biome()),site,ForestHabitat.forBiome(pool.biome()));double w=share.weight()/sum;
            height+=w*p.heightScale();crown+=w*p.crownSpread();productivity+=w*p.productivity();exposure+=w*p.exposure();competition+=w*p.competition();cohort+=w*p.cohort();}
        return new ForestGrowth.Profile(Math.clamp(height,.6,1.5),Math.clamp(crown,0,2),Math.clamp(productivity,0,1),Math.clamp(exposure,0,1),Math.clamp(competition,0,1),Math.clamp(cohort,0,1));
    }
    /** Palette follows the same recruitment intensity as density. Otherwise a
     * rare native meadow form would be multiplied by the entire forest budget. */
    public static String palette(long seed,int x,int z,ForestEcology.Site site,List<Share> shares){
        double total=0;for(var share:shares)total+=share.weight()*share.pool().capacity()*fields(seed,x,z,site,share.pool()).retention();
        if(total<=0)return shares.getFirst().pool().biome();
        double choice=unit(seed^0xEE17C68BL,x,z)*total;
        for(var share:shares){choice-=share.weight()*share.pool().capacity()*fields(seed,x,z,site,share.pool()).retention();if(choice<0)return share.pool().biome();}
        return shares.getLast().pool().biome();
    }
    private static ForestEcology.Fields fields(long seed,int x,int z,ForestEcology.Site site,Pool pool){
        return ForestEcology.sample(seed,x,z,ForestHabitat.familyForBiome(pool.biome()),site,ForestHabitat.forBiome(pool.biome()));
    }
    private static double unit(long seed,int x,int z){long n=seed+x*0x9E3779B97F4A7C15L+z*0xD1B54A32D192ED03L;n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;n=(n^(n>>>27))*0x94D049BB133111EBL;n^=n>>>31;return(n>>>11)*0x1.0p-53;}
}
