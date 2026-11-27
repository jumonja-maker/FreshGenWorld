package dev.freshgenerator.landform;

/** The landform model of the chunk whose native surface is being built (set by GlacialSurfaceMixin round
 * MaterialSystem.buildSurface, restored afterwards): the native eroded-badlands pillars are left out of rifts and
 * crater bowls. A pillar rises to an absolute height above Y64 and is filled with terracotta down to the ground, so in
 * a rift 100 blocks deep it stood as a fin 70 blocks high on the floor (seed 30307 X878 Z1045). */
public final class LandformSurfaceScope {
    private LandformSurfaceScope() {}
    private static final ThreadLocal<LandformDensity.World> CURRENT = new ThreadLocal<>();

    public static LandformDensity.World enter(LandformDensity.World world) {
        LandformDensity.World previous = CURRENT.get();
        CURRENT.set(world);
        return previous;
    }

    public static void exit(LandformDensity.World previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /** Whether the native badlands pillar at (x, z) is left out. */
    public static boolean noPillar(int x, int z) {
        LandformDensity.World world = CURRENT.get();
        return world != null && world.noPillar(x, z);
    }
}
