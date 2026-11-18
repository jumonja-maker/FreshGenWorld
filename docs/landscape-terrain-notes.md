# Terrain layer: physical motivation and scope

This layer is a procedural approximation at Minecraft scale. Its coefficients are design values; it does not claim to simulate tectonics, erosion time, sediment transport, or measured real-world mountain frequencies.

The National Park Service describes mountain form as the interaction of uplift, climate and erosion, including sharper younger relief and gentler older ranges. The implementation separates broad uplift provinces, continuous native ridge geometry, foothills and small surface relief. Rare stronger uplift follows those same ridges instead of adding a circular mountain object. Source: [Geology and Physical Processes of Mountains](https://www.nps.gov/subjects/mountains/geology.htm).

Coastal profiles depend on bedrock resistance, geology and wave energy. The implementation therefore varies coastal profile steepness across broad regions, with smooth transitions between resistant and gentler profiles. It preserves the original sea-level crossing of the base profile; it does not paint a fixed-width beach. This is a geometric proxy, not a wave-exposure calculation. Source: [Rocky Coast Landforms](https://www.nps.gov/articles/rocky-coast-landforms.htm).

Sediment availability and transport help distinguish depositional coasts from eroding headlands. The terrain proxy provides gentler lowlands and steeper coastal sectors; it does not claim a simulated sediment budget or add dunes, deltas, sea caves or barriers. Source: [Coastal Processes—Sediment Transport and Deposition](https://www.nps.gov/articles/coastal-processes-sediment-transport-and-deposition.htm).

## Current implementation contract

- New current layer only; historical compilation stages remain frozen.
- Native biome/spline continental input is a shared broad-scale blend: `0.93 * original_continents + 0.10 * macro_noise` outside the protected mushroom-island tail. The correction fades to zero between original continentality -0.80 and -1.05; values below -1.05 remain exact. It is not a second independent coastline.
- Active height uses the native `base_offset`, bypassing the old blanket extra ocean deepening and old added mountain uplift. Native valley clearance remains a continuous mask.
- Height is interpolated on a 4-block grid instead of the previous global 32-block grid. The single `coherent/height` authority is shared by solid terrain, climate depth, tree placement and preliminary surface level.
- Normal uplift and rare stronger uplift share the same broad mountain province, shoulders and crests. Giant prevalence must be measured on actual seeds; there is no per-chunk spawn chance.
- A continuous upper envelope approaches Y=310 and avoids a flat summit cap. Native structures remain able to modify foundations; caves stay disabled.
- Six debug fields expose continentality, mountain probability proxy, giant uplift weight, coast mask, rock-resistance proxy and raw height for native sampling.
- No custom river/lake model, water-level controller, aquifer implementation, chunk-dependent surface writing or circular terrain templates are introduced.

Numerical contracts and native sampling are necessary but do not prove visual realism. Review elevation maps, slope maps and actual chunk sections before accepting the new landscape.

## 0.5.1: regional mountain forms

One additional native two-octave field (`landform_noise`, base octave −11)
smoothly partitions mountain provinces into three overlapping transitions:

- **Broad massifs:** wider elevated shoulders, 25% attenuation toward a regional
  highland level, and 0.70 of the ridge-detail reference amplitude.
- **Ridge ranges:** the original connected native ridge crests remain narrow;
  detail amplitude is 1.25 of the reference.
- **Plateau-like highlands:** broad shoulders, at most 40% attenuation of the
  native summit relief, and 0.40 of the reference detail amplitude. At least
  60% of original summit relief remains, so this never cuts mountains against
  a universal horizontal plane. These are subdued uplands rather than flat mesas.

Selectors sum to one and change smoothly across large regions. The regional
highland level also depends on the existing uplift province and erosion field.
All shaping is multiplied by the original mountain mask, which vanishes at
protected native valley corridors, oceans and non-mountain conditions. Rare
giant uplift remains independent and follows the same native ridges. The
4-block height interpolation and soft upper envelope below Y=310 are retained.
The continental correction, mushroom-tail protection and coastal geometry
functions are unchanged by this mountain-form pass.

Cost: one extra native noise input, ten cached arithmetic density nodes, no
extra runtime loops or chunk queries. Twelve generated resources are added
(one noise definition, its cached density sampler, ten arithmetic nodes).
`activeLandscapeAlgorithm` is now 2; three form masks are exposed in the native
debug functions. A native performance run is still needed to quantify cost.

Twelve `LandscapeTerrainTest` methods passed through an isolated Java 25 runner
with the real JUnit assertions and Minecraft data resources. They check the
partition and boundaries, protected terrain, retained plateau relief, broad
shoulders, different roughness, finite supported heights and an explicit slope
bound on a synthetic transect. That bound permits steeper rare peaks and does
not claim a world-wide maximum slope. Initial overly narrow plateau shoulders
were broadened during this check.

For a synthetic common input (continentality 0.6, erosion −0.8, uplift field 0.4,
no fine/rolling noise), the three fully expressed forms give:

| Form | Shoulder at native Y=145, ridge=0.1 | Summit at native Y=190, ridge=0.9 | Same summit with rare uplift |
|---|---:|---:|---:|
| Massif | 200.17 | 245.95 | 299.31 |
| Ridge | 157.60 | 231.65 | 298.47 |
| Plateau-like | 191.17 | 257.14 | 299.88 |

These values demonstrate distinct profiles under identical inputs, not actual
world frequencies or acceptance of the in-game appearance. Native maps and
generated chunks provide the final sample evidence for this version.
