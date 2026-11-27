package dev.freshgenerator.core.hydro;

/** Fields read by the drainage model. In the game these are seed-bound native density samplers;
 * in tests they are synthetic. Implementations must be thread-safe and deterministic. */
public interface HydroInputs {
    /** Terrain height before hydrology at a block position, not interpolated. */
    double height(int x, int z);
    /** Continentalness (ocean below about -0.19). */
    double continents(int x, int z);
    /** Regional (zone) temperature, without altitude cooling. */
    double temperature(int x, int z);
    /** Regional humidity on the biome-table axis. */
    double moisture(int x, int z);
    double erosion(int x, int z);
    /** A position inside a sink - a rift or an active lava crater: no lake fills its basin, the rivers that reach it end
     * on its floor. */
    default boolean sink(int x, int z) { return false; }
    /** Whether ground below the sea level at (x, z) holds no sea water: the native fill is off there (a rift and the
     * band beside its walls, a crater bowl closed above the sea level) and only the drainage model's water stands. */
    default boolean seaFree(int x, int z) { return false; }
    /** Whether (x, z) lies near a rift beyond its dry band: no dry ground there may be carved below the sea level (the
     * native fill would make it sea water standing against the dry rift). */
    default boolean riftShore(int x, int z) { return false; }
    /** Where the node of the lattice cell centred on (cx, cz) stands instead of its jittered point: on the floor of a
     * sink narrower than the lattice spacing that crosses the cell (within half blocks of the centre), or null. */
    default int[] snap(int cx, int cz, int half) { return null; }
}
