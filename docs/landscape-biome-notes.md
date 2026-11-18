# Landscape climate for 0.5.0

The new layer keeps Minecraft's biome lookup tables, biome IDs, tree species,
features and structure placement. It changes the climate coordinates presented
to the existing lookup. It does not generate rivers, lakes or underground voids.

## Design

Large temperature and moisture regions still give an area its identity. Smaller,
weaker climate fields create variation within these regions and bend their
boundaries. Forest clearings, tree density and slope exclusions are handled by
the separate forest ecology layer, using the same temperature/moisture fields.
Biome IDs remain discrete; smooth climate fields do not make every visible
material or vegetation boundary continuous by themselves.

The public `highlands_v2/temperature` field is the reference lowland climate.
Existing snowline and forest ecology already combine it with terrain height, so
cooling that field again would count altitude twice. The biome selector instead
uses `landscape/climate/surface_temperature`, which cools gradually with the
final column height. Looking up another Y in the same surface column does not
invent another climate zone. The original temperature and moisture return over
24–64 blocks of cover, preserving the prior deep climate convention.

`highlands_v2/vegetation` combines regional moisture, subregional variation,
independent local variation and a small continental drying bias. This is a
landscape heuristic, not a rain-shadow or precipitation simulation. It does not
claim to infer wind direction, rainfall, soil hydrology or water availability.

## Explicit game parameters

All values below are dimensionless Minecraft climate tuning, not measured
environmental rates or kilometre-to-block conversions.

| Parameter | Value | Purpose |
|---|---:|---|
| Broad climate amplitude | 1.0 | Preserve distinct warm/cold and wet/dry domains |
| Original-scale temperature / moisture contribution | 0.08 / 0.22 | Related subregions inside larger domains |
| Independent local temperature / moisture contribution | 0.05 / 0.12 | Limited boundary and habitat variation |
| Local noise base octave, temperature / moisture | −8 / −7 | Moisture varies on a smaller scale than temperature |
| Local noise octaves / coordinate scale | 2 / 0.75 | Smooth detail rather than per-block randomness |
| Maximum inland moisture reduction | 0.08 | Weak continental influence, preserving regional extremes |
| Surface cooling height range | 96–272 | Smooth broad elevation response |
| Maximum surface biome cooling | 0.42 | Retain warm mountain regions as well as cold ranges |
| Climate output bounds | −1 to 1 | Stay within the intended surface climate domain |

The native noise functions are seed-dependent. The octave settings specify
scales rather than guaranteed biome diameters or a repeating tile size.

## Evidence and limits

The [Royal Meteorological Society's climate explanation](https://www.metlink.org/resource/uk-climate/)
describes regional climate controls, altitude cooling and local effects from
topography, forest cover and proximity to water. It also distinguishes windward
and leeward mountain conditions. These support layered climate and a gradual
height response, not the numerical game coefficients above.

The US Forest Service's [Forest Habitat Types of Northern Idaho](https://www.fs.usda.gov/rm/pubs_int/int_gtr236.pdf)
describes forest-type transitions controlled primarily by moisture at lower
elevations and temperature at upper elevations. This regional ecological
evidence motivates separate temperature and moisture influences; it is not a
universal species distribution model.

Focused tests check preservation of non-climate resources, local variation
within stable regional climate, gentle elevation transitions, no double cooling
of ecology inputs, unchanged deep climate and atomic/deterministic generation.
Actual appearance and biome coverage still need native world samples; these
tests do not establish that the resulting landscape looks natural.

## Lowland snow-cover transition

Within the existing eligible land-biome policy and above sea level + 8 blocks,
native snow-layer placement now uses a coherent seeded mask in the narrow
reference-temperature band −0.58 to −0.32 around the native cold-region boundary.
Outside that band the original cold/warm snow-placement result is retained.
The existing alpine mask has priority; its 104–144 height activation interval
also supplies a smooth handoff for the remaining inactive locations. There is
no new fixed plane where lowland snow is switched off.

Only the climate decision inside native `shouldSnow` changes. Its normal light,
survival and placement checks still run. Water freezing, rainless biomes, ground
materials, shore exclusions and special palettes are untouched. This softens
the generated snow cover; it does not blend biome grass tint, tree species or
the renderer's rain/snow weather boundary. The temperature band is a game-scale
choice, not a measured environmental transition width.
