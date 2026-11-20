package dev.freshgenerator.core;

/** Continuous substrate preferences, not a sediment or wave simulation.
 * Callers restrict these choices to the exterior and eligible shore/forest biomes. */
public final class LandscapeMaterials {
    private LandscapeMaterials() { }
    public enum Cover { NATIVE, STONE, GRAVEL, SAND, GRASS, COARSE_DIRT, PODZOL, CLAY, BASALT }
    /** Symmetric differences of the continuous ground height, before block rounding
     * or the water surface. Callers sample both sides even across chunk boundaries. */
    public static double continuousSlope(double west,double east,double north,double south,double radius){
        if(!Double.isFinite(west)||!Double.isFinite(east)||!Double.isFinite(north)||!Double.isFinite(south)
                ||!Double.isFinite(radius)||radius<=0)throw new IllegalArgumentException("Invalid slope samples");
        return Math.hypot((east-west)/(2*radius),(south-north)/(2*radius));
    }

    public static Cover coast(double height,double seaLevel,double slope,double patch) {
        check(height,seaLevel,slope,patch);
        double above=height-seaLevel;
        double reach=EcotoneModel.smooth(-12,-4,above)*(1-EcotoneModel.smooth(6,20,above));
        double threshold=.25+.5*(patch+1)*.5;
        if(reach<threshold)return Cover.NATIVE;
        // Exposed steep substrate transitions through coarse material to sand.
        double exposed=EcotoneModel.smooth(.38,1.05,slope);
        if(exposed>threshold)return Cover.STONE;
        double coarse=EcotoneModel.smooth(.12,.58,slope);
        if(coarse>threshold)return Cover.GRAVEL;
        return Cover.SAND;
    }

    public static Cover forest(double height,double seaLevel,double slope,double temperature,double moisture,double patch) {
        check(height,seaLevel,slope,patch);
        if(!Double.isFinite(temperature)||!Double.isFinite(moisture))throw new IllegalArgumentException("Non-finite climate");
        // Preserve shoreline and alpine palettes; this is the shared low forest floor.
        if(height<=seaLevel+8||height>=144||temperature<-.58)return Cover.NATIVE;
        double threshold=(patch+1)*.5;
        double reach=EcotoneModel.smooth(8,20,height-seaLevel)*(1-EcotoneModel.smooth(120,144,height));
        if(reach<threshold)return Cover.NATIVE;
        double cool=1-EcotoneModel.smooth(-.45,.25,temperature);
        double wet=EcotoneModel.smooth(-.7,.35,moisture);
        double shelter=1-EcotoneModel.smooth(.35,1.1,slope);
        double litter=(.16+.60*cool)*wet*shelter;
        if(litter>threshold)return Cover.PODZOL;
        double bare=.12+.35*(1-wet)+.25*(1-shelter);
        if(bare>1-threshold)return Cover.COARSE_DIRT;
        return Cover.GRASS;
    }
    /** 0.9.1: soil does not stay on a near-vertical wall. Above roughly 56-69
     * degrees (slope 1.5-2.6 blocks per block of the continuous ground envelope)
     * grass-topped land turns to exposed rock with an irregular, patchy edge;
     * ordinary hills and 45-degree slopes keep their native palette. */
    public static Cover cliff(double height,double seaLevel,double slope,double patch) {
        check(height,seaLevel,slope,patch);
        if(height<seaLevel+4)return Cover.NATIVE;
        double exposed=EcotoneModel.smooth(1.5,2.6,slope);
        double threshold=.2+.6*(patch+1)*.5;
        return exposed>threshold?Cover.STONE:Cover.NATIVE;
    }
    /** 0.9.1 seabed regime below the shoreline reach (after the 0.9.1 ocean audit; Dutkiewicz
     * et al. 2015 sediment census, Kleinhans et al. 2011 angle of repose). Steep walls expose
     * bedrock, intermediate slopes carry gravel talus, flats hold a sediment mosaic: sand in
     * warm water, gravel in cold water, mixed in temperate seas, and fine clay pockets on deep
     * quiet basin floors. Patches are broad (shared ecotone patch field), not per-block noise. */
    public static Cover seabed(double height,double seaLevel,double slope,double temperature,double patch,double rockPatch) {
        return seabed(height,seaLevel,slope,temperature,patch,rockPatch,0);
    }
    /** Update 19.11 (audit O2): the same province relief that shapes the deep floor also sets
     * its ground. Uplifts (ridges, seamounts; relief in blocks above the plain) are young
     * volcanic rock with thin cover: rock and talus appear on gentler slopes and the exposed
     * rock is basalt, with basalt outcrops between sediment pockets on the crests. Basins and
     * trenches (negative relief) collect fine sediment: clay pockets widen there
     * (Dutkiewicz et al. 2015: clay dominates the deep quiet basins). */
    public static Cover seabed(double height,double seaLevel,double slope,double temperature,double patch,double rockPatch,double relief) {
        check(height,seaLevel,slope,patch);
        if(!Double.isFinite(temperature)||!Double.isFinite(rockPatch)||!Double.isFinite(relief))throw new IllegalArgumentException("Non-finite seabed input");
        double depth=seaLevel-height;
        if(depth<SEABED_MIN_DEPTH)return Cover.NATIVE;
        double uplift=EcotoneModel.smooth(3,12,relief),crest=EcotoneModel.smooth(9,18,relief),basin=EcotoneModel.smooth(2,8,-relief);
        double threshold=(rockPatch+1)*.5*(1-.45*uplift);
        Cover rock=uplift>.5?Cover.BASALT:Cover.STONE;
        if(EcotoneModel.smooth(.75,1.5,slope)>threshold)return rock;
        if(EcotoneModel.smooth(.38,.95,slope)>threshold)return Cover.GRAVEL;
        if(crest>0&&(patch+1)*.5>1-.45*crest)return Cover.BASALT;
        double deep=EcotoneModel.smooth(18,30,depth);
        if(patch< -.55+.30*deep+.45*basin)return Cover.CLAY;
        double warm=EcotoneModel.smooth(.05,.45,temperature),cold=1-EcotoneModel.smooth(-.45,-.05,temperature);
        // Probability-like share of sand: warm water sandy, cold water gravelly.
        double sand=.55+.40*warm-.45*cold-.20*deep;
        return (patch+1)*.5<sand?Cover.SAND:Cover.GRAVEL;
    }
    public static final double SEABED_MIN_DEPTH=6;
    /** Thin rooting-soil patches on stable parts of a wooded mesa. Exposed
     * terracotta slopes keep the native geological palette. Height is measured
     * above the local sea level, never against the native fixed Y97 grass gate.
     * This is substrate suitability, not simulated soil formation or runoff. */
    public static Cover woodedBadlands(double height,double seaLevel,double slope,double moisture,double patch) {
        check(height,seaLevel,slope,patch);
        if(!Double.isFinite(moisture))throw new IllegalArgumentException("Non-finite moisture");
        if(height<=seaLevel+8)return Cover.NATIVE;
        double upland=EcotoneModel.smooth(8,18,height-seaLevel);
        double stable=1-EcotoneModel.smooth(.18,.68,slope);
        double available=EcotoneModel.smooth(-.60,.35,moisture);
        double soil=.70*upland*stable*available;
        if(soil<=0||(patch+1)*.5>=soil)return Cover.NATIVE;
        return available*stable>.60+.15*patch?Cover.GRASS:Cover.COARSE_DIRT;
    }
    private static void check(double height,double seaLevel,double slope,double patch){
        if(!Double.isFinite(height)||!Double.isFinite(seaLevel)||!Double.isFinite(slope)||slope<0
                ||!Double.isFinite(patch)||patch < -1||patch>1)throw new IllegalArgumentException("Invalid substrate input");
    }
}
