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
VERSION = '0.9.1-alpha.1'
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
                  | {DATA + 'landscape/' + n + '.json' for n in ('coast_cap', 'coast_profile', 'ocean_relief')}
                  | {DATA + 'landscape/massif/' + n + '.json' for n in ('interior', 'form', 'peaks', 'peaks_noise', 'size', 'size_noise', 'massif',
                                                                     'belt_cold', 'belt_height', 'belt_abs_w', 'belt_gate', 'belt_erosion_floor', 'belt_erosion_cap', 'belt_erosion', 'belt_weight', 'belt_target', 'belt_ridges')}
                  | {'data/freshgenerator/worldgen/noise/highlands_v2/landscape/' + n + '.json' for n in ('massif_peaks', 'massif_size')}
                  | {DATA + 'landscape/climate/' + n + '.json' for n in ('highland_band', 'highland_moisture_floor', 'highland_moisture_cap', 'highland_moisture', 'boreal_moisture_floor', 'boreal_fringe_floor', 'boreal_moisture',
                                                                         'cold_coast_floor', 'coast_erosion', 'frozen_moisture_cap', 'frozen_moisture')}
                  | {'data/minecraft/worldgen/structure_set/' + n + '.json' for n in ('ocean_monuments', 'ocean_ruins', 'shipwrecks')}
                  | {CLASS + n for n in ('core/TerrainRepair.class', 'core/OceanFloor.class', 'core/OceanEcology.class',
                                         'core/OceanEcology$Community.class', 'core/OceanEcology$Ground.class', 'ocean/OceanEnvironment.class')})
CHANGED = frozenset({'data/freshgenerator/worldgen/noise/highlands_v2/karst/doline.json', DATA + 'karst/pothole.json', DATA + 'karst/doline.json', DATA + 'karst/pothole_allow.json', DATA + 'landscape/coast_height.json',
                     DATA + 'landscape/giant.json', DATA + 'landscape/uplift.json', DATA + 'landscape/height_unguarded.json', DATA + 'regions/biome_vegetation.json', 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json', 'data/freshgenerator/worldgen/material_rule/highlands_v2/surface.json', CLASS + 'core/KarstModel.class', CLASS + 'core/LandscapeMaterials$Cover.class',
                     CLASS + 'mixin/ForestPlacementMixin.class',
                     CLASS + 'core/LandscapeMaterials.class', CLASS + 'mixin/CaveCarverMixin.class', CLASS + 'surface/EcotonePolicy.class',
                     CLASS + 'surface/EcotoneSurfaceRule$1.class', CLASS + 'surface/EcotoneSurfaceRule$2.class',
                     CLASS + 'surface/EcotoneSurfaceRule.class', CLASS + 'village/VillageTerrainModel.class', CLASS + 'core/ForestEcotone.class',
                     CLASS + 'core/ForestEcotone$Pool.class', CLASS + 'core/ForestEcotone$Share.class', 'fabric.mod.json', MANIFEST})


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
        require(set(new) - set(old) == ADDED, f'Unreviewed additions: {sorted((set(new) - set(old)) ^ ADDED)}')
        changed = {n for n in set(old) & set(new) if old[n] != new[n]}
        require(changed == CHANGED, f'Unreviewed or missing changes: {sorted(changed ^ CHANGED)}')
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
        require(decode(DATA + 'karst/pothole_depth.json') == {'type': 'minecraft:sub', 'left': P + 'coherent/height', 'right': 'minecraft:y'}, 'Depth below surface')
        meta = decode('fabric.mod.json'); old_meta['version'] = VERSION
        # 0.9.1 release: only the player-facing description changes besides the version.
        require(meta.get('description', '').startswith('Natural world generation for Minecraft 26.3: great snow massifs'), 'Release description')
        old_meta['description'] = meta.get('description')
        require(meta == old_meta, 'Unexpected mod metadata changes')
        manifest = decode(MANIFEST); data = {n: h for n, h in new.items() if n.startswith('data/')}
        require(manifest['generated_sha256'] == data and manifest['generated_entries'] == len(data), 'Manifest inventory differs')
        require(manifest['default_world_preset'] == 'minecraft:normal', 'Default world selection changed')
        return dict(version=VERSION, sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(), generated_resources=len(data),
                    added=sorted(ADDED), changed=sorted(CHANGED), scope='Package and emitted graph contract; native measurements are separate')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate', type=Path, default=CANDIDATE); p.add_argument('--baseline', type=Path, default=BASELINE)
    p.add_argument('--output', type=Path)
    a = p.parse_args(); r = validate(a.candidate, a.baseline)
    if a.output: a.output.parent.mkdir(parents=True, exist_ok=True); a.output.write_text(json.dumps(r, indent=2) + '\n')
    print(json.dumps(r, indent=2))
