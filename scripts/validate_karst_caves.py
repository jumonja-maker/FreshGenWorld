"""Protect the 0.9.0 karst-cave / biome-relief / climate-niche / forest update against the accepted 0.8.2.

Package and emitted-graph contract only; native measurements are separate
(FreshGenerator-Workspace/caves-0.9.0-2026-11-18).
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
VERSION = '0.9.0-alpha.1'
CANDIDATE = ROOT / f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE = ROOT / 'build/libs/FreshGenerator-Fabric-26.3-0.8.2-alpha.1.jar'
BASELINE_SHA = 'f4d5fd9cce8aea413a3b52adf78588927db105a9af438561ff3317e84f91dc85'
P = 'freshgenerator:highlands_v2/'
DATA = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
NOISE = 'data/freshgenerator/worldgen/noise/highlands_v2/'
SETTINGS = 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
CLASS = 'dev/freshgenerator/'
MANIFEST = 'freshgenerator-worldgen-manifest.json'
CLIMATE_ADDED = frozenset(DATA + 'landscape/climate/' + n + '.json' for n in ('biome_erosion', 'dry_belt', 'lookup_moisture', 'wetland_cap'))
CHANGED = frozenset({DATA + 'coherent/detail/mountain_factor.json', DATA + 'coherent/detail/rock_relief.json',
                     DATA + 'final_density.json', DATA + 'regions/biome_vegetation.json', DATA + 'landscape/height_unguarded.json', SETTINGS,
                     CLASS + 'core/ForestComposition.class', CLASS + 'core/ForestEcology$1.class', CLASS + 'core/ForestEcology.class',
                     CLASS + 'core/ForestEcotone.class', CLASS + 'forest/ForestEnvironment.class', CLASS + 'forest/NativeTreeGrowth.class',
                     'fabric.mod.json', MANIFEST})


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def validate(candidate=CANDIDATE, baseline=BASELINE):
    baseline, candidate = Path(baseline), Path(candidate)
    require(hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA, 'Wrong 0.8.2 baseline JAR')
    with ZipFile(baseline) as z:
        old = {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
        old_meta = json.loads(z.read('fabric.mod.json')); old_settings = json.loads(z.read(SETTINGS))
    with ZipFile(candidate) as z:
        names = [n for n in z.namelist() if not n.endswith('/')]
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        new = {n: hashlib.sha256(z.read(n)).hexdigest() for n in names}
        removed, added = set(old) - set(new), set(new) - set(old)
        require(removed and all('/cave_network/' in n for n in removed), f'Unexpected removals: {sorted(n for n in removed if "/cave_network/" not in n)}')
        require(not any('/cave_network/' in n for n in new), 'Old cave network still packaged')
        karst = {n for n in added if n.startswith((DATA + 'karst/', NOISE + 'karst/'))}
        relief = {n for n in added if n.startswith((DATA + 'relief/', NOISE + 'relief/'))}
        require(added == karst | relief | CLIMATE_ADDED | {CLASS + 'core/KarstModel.class'}, f'Unreviewed additions: {sorted(added - karst - relief - CLIMATE_ADDED)}')
        changed = {n for n in set(old) & set(new) if old[n] != new[n]}
        require(changed == CHANGED, f'Unreviewed or missing changes: {sorted(changed ^ CHANGED)}')
        decode = lambda n: json.loads(z.read(n))
        root = decode(DATA + 'landscape/height_unguarded.json')['input']
        require(root['type'] == 'minecraft:add' and root['right'] == P + 'relief/biome_relief', 'Biome relief must be added to the landscape root')
        require(decode(DATA + 'regions/biome_vegetation.json')['first'] == P + 'landscape/climate/lookup_moisture', 'Dry belt not routed')
        require(DATA + 'landscape/climate/desert_cap.json' not in new, 'Over-broad desert cap returned')
        final = decode(DATA + 'final_density.json')
        require(final['left'] == P + 'karst/final_body' and final['right']['type'] == 'minecraft:beardifier', 'Cave body or structure fit rewired')
        body = decode(DATA + 'karst/body.json')
        require(body['type'] == 'minecraft:interpolated' and body['cell_size_xz'] == 4 and body['cell_size_y'] == 4, 'Caves must stay on the native 4-block grid')
        require(decode(DATA + 'karst/final_body.json')['left'] == P + 'coherent/surface_density', 'Cave body must stay inside the terrain envelope')
        guard = json.dumps(decode(DATA + 'karst/roof_guard.json'))
        require(P + 'coherent/height' in guard and '14.0' in guard, 'Common 14-block roof guard missing')
        settings = decode(SETTINGS); router = settings['noise_router']
        require(router['erosion'] == P + 'landscape/climate/biome_erosion', 'Wetland niche not routed')
        for key in old_settings:
            if key not in ('noise_router', 'debug_functions'):
                require(settings[key] == old_settings[key], 'Unexpected settings change: ' + key)
        for key, value in old_settings['noise_router'].items():
            if key != 'erosion':
                require(router[key] == value, 'Unexpected router change: ' + key)
        require(decode(DATA + 'coherent/surface_density.json')['type'] == 'minecraft:squeeze', 'Terrain envelope changed')
        meta = decode('fabric.mod.json'); old_meta['version'] = VERSION
        require(meta == old_meta, 'Unexpected mod metadata changes')
        manifest = decode(MANIFEST); data = {n: h for n, h in new.items() if n.startswith('data/')}
        require(manifest['generated_sha256'] == data and manifest['generated_entries'] == len(data), 'Manifest inventory differs')
        require(manifest['default_world_preset'] == 'minecraft:normal', 'Default world selection changed')
        for n in names:
            if n.endswith('.class'):
                b = z.read(n).lower()
                require(not any(w in b for w in (b'hydrolog', b'watershed', b'drainage')), 'Removed custom water code returned: ' + n)
        return dict(version=VERSION, sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(), generated_resources=len(data),
                    karst_resources=len(karst), relief_resources=len(relief), removed_cave_network=len(removed), changed=sorted(CHANGED),
                    scope='Package and emitted graph contract; native measurements are separate')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate', type=Path, default=CANDIDATE); p.add_argument('--baseline', type=Path, default=BASELINE)
    p.add_argument('--output', type=Path)
    a = p.parse_args(); r = validate(a.candidate, a.baseline)
    if a.output: a.output.parent.mkdir(parents=True, exist_ok=True); a.output.write_text(json.dumps(r, indent=2) + '\n')
    print(json.dumps(r, indent=2))
