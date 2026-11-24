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
}
