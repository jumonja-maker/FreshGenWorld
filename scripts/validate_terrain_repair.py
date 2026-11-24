"""Protect the 0.9.1 audit-repair update against the published 0.9.0 release JAR.

Package and emitted-graph contract only; native measurements are separate
(FreshGenerator-Workspace/caves-0.9.0-2026-11-18, labels v091-*).
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
VERSION = '0.9.3-beta.1'
CANDIDATE = ROOT / f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE = ROOT.parent / 'FreshGenerator-Workspace/release-0.9.1/baseline-0.9.0-alpha.1.jar'
BASELINE_SHA = 'ac9423cca905034f83af66564168b52ec428a65f1ece8c0984709fc184e6981c'
P = 'freshgenerator:highlands_v2/'
DATA = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
CLASS = 'dev/freshgenerator/'
MANIFEST = 'freshgenerator-worldgen-manifest.json'
OCEAN = 'data/freshgenerator/worldgen/noise/highlands_v2/ocean/'
KARST_ADDED = ('pothole_depth', 'pothole_shift_cross', 'pothole_shift_plan', 'pothole_noise', 'doline_noise', 'junction_sin',
               'plan_a_distance', 'plan_b_distance', 'plan_a_grad2', 'plan_b_grad2', 'plan_a_gx', 'plan_a_gz', 'plan_b_gx', 'plan_b_gz',
               'plan_a_inverse_gradient', 'plan_b_inverse_gradient', 'plan_a_nx', 'plan_a_nz', 'plan_b_nx', 'plan_b_nz',
               'pothole_phase', 'junction_det', 'junction_inverse_det', 'junction_dx', 'junction_dz', 'pothole_junction_noise', 'doline_junction_noise',
               'junction_sin_at') + tuple(f + s for f in ('plan_a', 'plan_b') for s in ('_jgx', '_jgz', '_jgrad2', '_inverse_gradient_at'))
OCEAN_NODES = ('basin_mask', 'deep', 'mount_noise', 'province', 'province_neg', 'raw', 'relief', 'ridge_line', 'ridge_mask', 'size',
               'size_noise', 'trench_line')
ADDED = frozenset({DATA + 'karst/' + n + '.json' for n in KARST_ADDED}
                  | {DATA + 'ocean/' + n + '.json' for n in OCEAN_NODES}
                  | {OCEAN + n + '.json' for n in ('mount', 'province', 'ridge', 'size', 'swell', 'trench')}
                  | {DATA + 'landscape/' + n + '.json' for n in ('coast_cap', 'coast_profile', 'ocean_relief', 'dry_weight', 'dry_floor')}
                  | {DATA + 'landscape/massif/' + n + '.json' for n in ('interior', 'form', 'peaks', 'peaks_noise', 'size', 'size_noise', 'massif',
                                                                     'belt_cold', 'belt_height', 'belt_peak_height', 'belt_abs_w', 'belt_gate', 'belt_erosion_floor', 'belt_erosion_cap', 'belt_erosion', 'belt_weight', 'belt_target', 'belt_ridges', 'belt_depth', 'routed_erosion', 'routed_ridges')}
                  | {'data/freshgenerator/worldgen/noise/highlands_v2/landscape/' + n + '.json' for n in ('massif_peaks', 'massif_size')}
                  | {DATA + 'landscape/climate/' + n + '.json' for n in ('highland_band', 'highland_moisture_floor', 'highland_moisture_cap', 'highland_moisture', 'boreal_moisture_floor', 'boreal_fringe_floor', 'boreal_moisture',
                                                                         'cold_coast_floor', 'coast_erosion', 'frozen_moisture_cap', 'frozen_moisture', 'frozen_erosion', 'frozen_windswept', 'forest_moisture')}
                  | {'data/freshgenerator/worldgen/structure_set/' + n + '.json' for n in ('ocean_monuments', 'ocean_ruins', 'shipwrecks')}
                  | {CLASS + n for n in ('core/TerrainRepair.class', 'core/OceanFloor.class', 'core/OceanEcology.class',
                                         'core/OceanEcology$Community.class', 'core/OceanEcology$Ground.class', 'ocean/OceanEnvironment.class', 'cave/NativeCarverSwitch.class', 'cave/DebugFields.class', 'forest/ForestEnvironment$MixKey.class')})
CHANGED = frozenset({'data/freshgenerator/worldgen/noise/highlands_v2/karst/doline.json', DATA + 'karst/pothole.json', DATA + 'karst/doline.json', DATA + 'karst/pothole_allow.json', DATA + 'landscape/coast_height.json',
                     DATA + 'landscape/giant.json', DATA + 'landscape/uplift.json', DATA + 'landscape/height_unguarded.json', DATA + 'regions/biome_vegetation.json', DATA + 'regions/biome_temperature.json', DATA + 'coherent/height.json', 'data/freshgenerator/worldgen/material_rule/highlands_v2/biome_surface.json', 'data/freshgenerator/worldgen/material_rule/highlands_v2/under_biome_surface.json', CLASS + 'core/ForestComposition.class', CLASS + 'core/ForestEcology.class', CLASS + 'forest/NativeTreeGrowth.class', CLASS + 'forest/NativeForestPalette.class', DATA + 'landscape/climate/biome_continents.json', 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json', 'data/freshgenerator/worldgen/material_rule/highlands_v2/surface.json', CLASS + 'core/KarstModel.class', CLASS + 'core/GlacialIce.class', CLASS + 'ice/GlacialSurface$Environment.class', CLASS + 'ice/GlacialSurface.class', CLASS + 'forest/ForestEnvironment.class', CLASS + 'core/LandscapeMaterials$Cover.class',
                     CLASS + 'mixin/ForestPlacementMixin.class',
                     CLASS + 'core/LandscapeMaterials.class', CLASS + 'mixin/CaveCarverMixin.class', CLASS + 'surface/EcotonePolicy.class',
                     CLASS + 'surface/EcotoneSurfaceRule$1.class', CLASS + 'surface/EcotoneSurfaceRule$2.class',
                     CLASS + 'surface/EcotoneSurfaceRule.class', CLASS + 'village/VillageTerrainModel.class', CLASS + 'core/ForestEcotone.class',
                     CLASS + 'core/ForestEcotone$Pool.class', CLASS + 'core/ForestEcotone$Share.class', 'fabric.mod.json', MANIFEST})


# 0.10 water on land (docs/hydrology-1.0.md): drainage network terrain, river slice and trench removal.
HYDRO_ADDED = frozenset({DATA + n + '.json' for n in ('coherent/terrain', 'hydrology/biome_ridges', 'hydrology/dry_banks', 'hydrology/ridges_folded_terrain')}
                        | {CLASS + 'mixin/ChunkGeneratorHydrologyMixin.class', CLASS + 'mixin/NoiseChunkHydrologyMixin.class'}
                        # audit 23.11: lake ice per water body, river biomes in /locate, no village on the water
                        | {CLASS + 'mixin/' + n + '.class' for n in ('SnowAndFreezeMixin', 'BiomeSourceRiverMixin', 'VillageWaterMixin',
                                                                     'StructureStateMixin', 'StructureSetsMixin')}
                        # creeks 23.11: water disks stay on the bed and edge of their water
                        | {CLASS + 'mixin/DiskFeatureMixin.class'}
                        # audit 24.11: cached fallback biomes (explorer maps), lake decision in play
                        | {CLASS + 'mixin/ServerLevelBiomeMixin.class'}
                        # speed-up 23.11: per-world memo of the forest adapter's biome and height reads
                        | {CLASS + 'forest/ForestWorldMemo.class', CLASS + 'surface/EcotoneSurfaceRule$Column.class'}
                        | {'data/freshgenerator/worldgen/feature/hydro_decor.json', 'data/freshgenerator/worldgen/placed_feature/hydro_decor.json'})
# code audit 24.11: broken generation invariants reported once instead of a crash; licence texts in the JAR
CODE_AUDIT_ADDED = frozenset({CLASS + 'core/Diagnostics.class', CLASS + 'forest/NativeForestPalette$Ops.class', 'LICENSE_freshgenerator', 'NOTICE_freshgenerator.md'})
HYDRO_PACKAGES = (CLASS + 'core/hydro/', CLASS + 'hydrology/')
HYDRO_CHANGED = frozenset({DATA + n + '.json' for n in ('base_offset', 'cave_access/land', 'coherent/surface_density', 'preliminary_surface_level',
                                                         'karst/entrance_allow', 'karst/entrance_deep', 'karst/entrance_depth', 'karst/entrance_flare',
                                                         'karst/ramp_centre', 'karst/roof_guard')}
                          | {CLASS + 'FreshGenerator.class', 'freshgenerator.mixins.json'}
                          # audit 23.11: village grading reads the water level of rivers and lakes
                          | {CLASS + 'village/VillageTerrainPlan.class'})


# 0.10 climate shape (user request 23.11): underground climate follows the surface, meandering climate borders
# (domain-warp layers), island climate over zone temperature and biome moisture.
CLIMATE_WARPS = ('warp_large', 'warp_mid', 'warp_fine', 'warp_detail')
CLIMATE_ADDED = frozenset({DATA + 'landscape/climate/' + w + '_' + a + '.json' for w in CLIMATE_WARPS for a in 'xz'}
                          | {'data/freshgenerator/worldgen/noise/highlands_v2/landscape/climate/' + w + '_' + a + '.json' for w in CLIMATE_WARPS for a in 'xz'}
                          | {DATA + 'landscape/climate/zone_temperature_local.json', DATA + 'landscape/climate/biome_moisture_local.json'}
                          | {DATA + 'landscape/climate/warp_field_' + a + '.json' for a in 'xz'}
                          | {DATA + 'landscape/climate/dither_' + f + '.json' for f in ('temperature', 'moisture')}
                          | {'data/freshgenerator/worldgen/noise/highlands_v2/landscape/climate/' + d + '_' + f + '.json'
                             for d in ('dither_coarse', 'dither_fine', 'dither_detail') for f in 'tm'})
CLIMATE_PACKAGES = (CLASS + 'climate/', CLASS + 'core/IslandClimate', CLASS + 'core/NodeTable')
CLIMATE_CHANGED = frozenset({DATA + 'landscape/climate/' + n + '.json' for n in ('regional_temperature', 'regional_vegetation', 'zone_temperature', 'biome_moisture',
                                                                                    'altitude_cooling', 'surface_continents')})


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def validate(candidate=CANDIDATE, baseline=BASELINE):
    baseline, candidate = Path(baseline), Path(candidate)
    require(hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA, 'Wrong 0.9.0 baseline JAR')
    with ZipFile(baseline) as z:
        old = {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
        old_meta = json.loads(z.read('fabric.mod.json'))
        old_coast = json.loads(z.read(DATA + 'landscape/coast_height.json'))
        old_depth = json.loads(z.read(DATA + 'ecotone/biome_depth.json'))
    with ZipFile(candidate) as z:
        names = [n for n in z.namelist() if not n.endswith('/')]
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        new = {n: hashlib.sha256(z.read(n)).hexdigest() for n in names}
        require(not set(old) - set(new), f'Unexpected removals: {sorted(set(old) - set(new))}')
        added = set(new) - set(old)
        hydro_classes = {n for n in added if n.startswith(HYDRO_PACKAGES + CLIMATE_PACKAGES) and n.endswith('.class')}
        require(any(n.endswith('core/hydro/HydroModel.class') for n in hydro_classes), 'Drainage model missing')
        require(any(n.endswith('core/IslandClimate.class') for n in hydro_classes), 'Island climate model missing')
        expected_added = ADDED | HYDRO_ADDED | CLIMATE_ADDED | CODE_AUDIT_ADDED
        require(added - hydro_classes == expected_added, f'Unreviewed additions: {sorted((added - hydro_classes) ^ expected_added)}')
        changed = {n for n in set(old) & set(new) if old[n] != new[n]}
        expected_changed = CHANGED | HYDRO_CHANGED | CLIMATE_CHANGED
        require(changed == expected_changed, f'Unreviewed or missing changes: {sorted(changed ^ expected_changed)}')
        decode = lambda n: json.loads(z.read(n))
        require(decode(DATA + 'landscape/coast_profile.json') == old_coast, 'Original coastal profile must be kept verbatim')
        coast = decode(DATA + 'landscape/coast_height.json')['input']
        require(coast == {'type': 'minecraft:min', 'left': P + 'landscape/coast_profile', 'right': P + 'landscape/coast_cap'},
                'Coastal height must read the profile once, limited by the ceiling only')
        cap = json.dumps(decode(DATA + 'landscape/coast_cap.json'))
        require('65.0' in cap and P + 'landscape/coast_rock' in cap and P + 'continents' in cap, 'Coastal ceiling inputs')
        giant = json.dumps(decode(DATA + 'landscape/giant.json'))
        require(all(s in giant for s in (P + 'ridges_folded', P + 'landscape/land', P + 'landscape/province', P + 'landscape/giant_noise', '-0.65', '-0.95', '0.3')),
                'Giant uplift: regional base, wide valley clearance and broad ridge window')
        require(decode(DATA + 'ecotone/biome_depth.json') == old_depth, 'A11 deferred: biome depth stays exactly 0.9.0')
        pothole = json.dumps(decode(DATA + 'karst/pothole.json'))
        require(P + 'karst/pothole_shift_cross' in pothole and P + 'karst/pothole_shift_plan' in pothole, 'Pothole pitches not wired')
        choice = decode(DATA + 'karst/pothole.json')['left']['left']['left']
        require(choice['type'] == 'minecraft:range_choice' and choice['input'] == P + 'karst/pothole_noise'
                and 'pothole_shift' not in json.dumps(choice['when_in_range']), 'Pitch geometry must be evaluated only near pothole sites')
        shift = decode(DATA + 'karst/pothole_shift_cross.json')
        # Update 19.11 (C1): the helix phase follows absolute height (320 - Y), shared by all columns of a shaft.
        require(shift['type'] == 'minecraft:spline' and shift['spline']['coordinate'] == P + 'karst/pothole_phase', 'Pitches follow one phase')
        require(decode(DATA + 'karst/pothole_phase.json') == {'type': 'minecraft:sub', 'left': 320.0, 'right': 'minecraft:y'}, 'Absolute helix phase')
        points = shift['spline']['points']
        require(points[0] == {'location': 0.0, 'value': 0.0, 'derivative': 0.0} and points[1]['location'] >= 8, 'Helix starts above the build height')
        require(P + 'karst/junction_sin_at' in pothole and P + 'karst/junction_dx' in pothole, 'Shaft judged and placed at the junction point')
        require(points[-1]['location'] >= 330 and max(abs(p['value']) for p in points) <= 4.5 * 1.25 + 1e-6, 'Winding covers the shaft, bounded swing')
        require(decode(DATA + 'karst/pothole_depth.json') == {'type': 'minecraft:sub', 'left': P + 'coherent/terrain', 'right': 'minecraft:y'}, 'Depth below the carved surface')
        terrain = decode(DATA + 'coherent/terrain.json')
        require(terrain['type'] == 'freshgenerator:hydrology' and terrain['base'] == P + 'landscape/dry_floor', 'Drainage terrain over the ground')
        require(decode(DATA + 'hydrology/dry_banks.json').get('output') == 'dry_banks', 'Dry-bank mask')
        offset = json.dumps(decode(DATA + 'base_offset.json'))
        require(P + 'hydrology/ridges_folded_terrain' in offset and '"' + P + 'ridges_folded"' not in offset, 'Native river trench removed from the terrain spline')
        settings = decode('data/freshgenerator/worldgen/noise_settings/highlands_v2.json')
        require(settings['noise_router']['ridges'] == P + 'hydrology/biome_ridges', 'Native river slice removed from the biome table')
        require(any(d['label'] == 'FreshGenerator hydrology' for d in settings['debug_functions']), 'Hydrology binding label')
        for name in ('coherent/surface_density', 'preliminary_surface_level', 'karst/roof_guard'):
            text = json.dumps(decode(DATA + name + '.json'))
            require(P + 'coherent/terrain' in text and P + 'coherent/height"' not in text, f'{name} follows the carved terrain')
        for name in ('karst/entrance_allow', 'karst/pothole_allow'):
            require(P + 'hydrology/dry_banks' in json.dumps(decode(DATA + name + '.json')), f'{name} keeps clear of water')
        for axis, name in (('temperature', 'surface_temperature'), ('vegetation', 'frozen_moisture')):
            require(decode(DATA + 'regions/biome_' + axis + '.json') == {'type': 'minecraft:cache', 'input': P + 'landscape/climate/' + name},
                    f'Underground biome {axis} follows the surface')
        for name, output in (('zone_temperature', 'temperature'), ('biome_moisture', 'moisture')):
            island = decode(DATA + 'landscape/climate/' + name + '.json')['input']
            require(island['type'] == 'freshgenerator:island_climate' and island['output'] == output and island['height'] == P + 'landscape/native_height',
                    f'Island climate over {name} (climate-independent land)')
        for axis in 'xz':
            lattice = decode(DATA + 'landscape/climate/warp_field_' + axis + '.json')['input']
            text = json.dumps(lattice)
            require(lattice['type'] == 'freshgenerator:lattice' and 8 <= lattice['spacing'] <= 32
                    and all(P + 'landscape/climate/' + w + '_' + axis in text for w in CLIMATE_WARPS), f'Warp field {axis}: layers on a fine lattice')
        for name, local in (('temperature', 'zone_temperature_local'), ('moisture', 'biome_moisture_local')):
            dither = decode(DATA + 'landscape/climate/dither_' + name + '.json')['input']
            require(dither['type'] == 'freshgenerator:lattice' and 8 <= dither['spacing'] <= 32, f'Ecotone dither of {name} on a fine lattice')
            require(P + 'landscape/climate/dither_' + name in json.dumps(decode(DATA + 'landscape/climate/' + local + '.json')),
                    f'Zone {name} carries the ecotone dither')
        for name in ('regional_temperature', 'regional_vegetation'):
            text = json.dumps(decode(DATA + 'landscape/climate/' + name + '.json'))
            require(all(P + 'landscape/climate/warp_field_' + a in text for a in 'xz') and P + 'landscape/climate/warp_x' in text, f'{name} warp layers')
        meta = decode('fabric.mod.json'); old_meta['version'] = VERSION
        # 0.9.1 release: only the player-facing description changes besides the version.
        require(meta.get('description', '').startswith('Natural world generation for Minecraft 26.3: great snow massifs'), 'Release description')
        old_meta['description'] = meta.get('description')
        require(meta == old_meta, 'Unexpected mod metadata changes')
        manifest = decode(MANIFEST); data = {n: h for n, h in new.items() if n.startswith('data/')}
        require(manifest['generated_sha256'] == data and manifest['generated_entries'] == len(data), 'Manifest inventory differs')
        require(manifest['default_world_preset'] == 'minecraft:normal', 'Default world selection changed')
        return dict(version=VERSION, sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(), generated_resources=len(data),
                    added=sorted(expected_added), hydrology_classes=sorted(hydro_classes), changed=sorted(expected_changed),
                    scope='Package and emitted graph contract; native measurements are separate')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate', type=Path, default=CANDIDATE); p.add_argument('--baseline', type=Path, default=BASELINE)
    p.add_argument('--output', type=Path)
    a = p.parse_args(); r = validate(a.candidate, a.baseline)
    if a.output: a.output.parent.mkdir(parents=True, exist_ok=True); a.output.write_text(json.dumps(r, indent=2) + '\n')
    print(json.dumps(r, indent=2))
