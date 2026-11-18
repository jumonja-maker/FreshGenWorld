"""Protect the 0.8.2 terrain-realism update against the exact accepted 0.8.1.

Package/graph scope contract only. Caves, water, aquifers, noise settings,
biome tables, villages and every other resource must keep their 0.8.1 bytes.
It does not claim visual acceptance; native measurements are separate.
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
VERSION = '0.8.2-alpha.1'
CANDIDATE = ROOT / f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE = ROOT / 'build/libs/FreshGenerator-Fabric-26.3-0.8.1-alpha.1.jar'
BASELINE_SHA = '3877532b3d1e7ef64f1f7563bee21ae395169b0f41ac0f1ae44c3128e471be7a'
P = 'freshgenerator:highlands_v2/'
DATA = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
NOISE = 'data/freshgenerator/worldgen/noise/highlands_v2/'
CLASS = 'dev/freshgenerator/'
MANIFEST = 'freshgenerator-worldgen-manifest.json'
ADDED = frozenset({DATA + 'landscape/climate/warp_x.json', DATA + 'landscape/climate/warp_z.json',
                   DATA + 'landscape/coastal_floor.json', DATA + 'landscape/height_unguarded.json', DATA + 'landscape/guard_offset.json',
                   NOISE + 'landscape/climate/warp_x.json', NOISE + 'landscape/climate/warp_z.json',
                   CLASS + 'core/CoastalRelief.class'})
CHANGED = frozenset({DATA + 'coherent/detail/mountain_factor.json', DATA + 'coherent/detail/rock_relief.json',DATA + 'landscape/climate/regional_temperature.json',
                     DATA + 'landscape/climate/regional_vegetation.json', DATA + 'landscape/height_raw.json',
                     DATA + 'landscape/seabed_detail.json', CLASS + 'ice/GlacialSurface.class',
                     CLASS + 'ice/GlacialSurface$Environment.class', CLASS + 'surface/EcotonePolicy.class',
                     'fabric.mod.json', MANIFEST})
WARP_BLOCKS = 80


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def refs(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for v in value.values():
            yield from refs(v)
    elif isinstance(value, list):
        for v in value:
            yield from refs(v)


def validate(candidate=CANDIDATE, baseline=BASELINE):
    baseline, candidate = Path(baseline), Path(candidate)
    require(hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA, 'Wrong 0.8.1 baseline JAR')
    with ZipFile(baseline) as z:
        old = {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
        old_meta = json.loads(z.read('fabric.mod.json'))
    with ZipFile(candidate) as z:
        names = [n for n in z.namelist() if not n.endswith('/')]
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        new = {n: hashlib.sha256(z.read(n)).hexdigest() for n in names}
        require(set(old) - set(new) == set(), 'Unexpected removals')
        require(set(new) - set(old) == ADDED, f'Unreviewed additions: {sorted((set(new) - set(old)) ^ ADDED)}')
        changed = {n for n in set(old) & set(new) if old[n] != new[n]}
        require(changed == CHANGED, f'Unreviewed or missing changes: {sorted(changed ^ CHANGED)}')
        decode = lambda n: json.loads(z.read(n))
        # Coastal guard: the final height is a soft maximum over the unguarded relief.
        height = decode(DATA + 'landscape/height_raw.json')['input']
        require(height['left'] == P + 'landscape/coastal_floor' and height['right']['spline']['coordinate'] == P + 'landscape/guard_offset', 'Coastal guard missing')
        offset = decode(DATA + 'landscape/guard_offset.json')['input']
        require(offset == {'type': 'minecraft:sub', 'left': P + 'landscape/height_unguarded', 'right': P + 'landscape/coastal_floor'}, 'Guard must read the landscape root once')
        require(decode(DATA + 'landscape/coastal_floor.json')['input']['spline']['coordinate'] == P + 'landscape/native_height', 'Floor must follow native height (single cheap read)')
        seabed = json.dumps(decode(DATA + 'landscape/seabed_detail.json'))
        require('52.0' in seabed and '55.0' not in seabed, 'Seabed detail still roughens dry lowland')
        factor = set(refs(decode(DATA + 'coherent/detail/mountain_factor.json')))
        require({P + 'mountain_mask', P + 'landscape/mountain', P + 'coherent/height'} <= factor, 'Rock-detail mask must follow actual uplift')
        relief = decode(DATA + 'coherent/detail/rock_relief.json')
        require(relief['type'] == 'minecraft:interpolated' and relief['cell_size_xz'] == 4 and relief['cell_size_y'] == 4
                and relief['input']['type'] == 'minecraft:range_choice', 'Native rock detail must be cell-interpolated (performance)')
        for axis in ('temperature', 'vegetation'):
            noise = decode(DATA + f'landscape/climate/regional_{axis}.json')
            for s in ('shift_x', 'shift_z'):
                right = noise[s]['right']
                require(noise[s]['type'] == 'minecraft:add' and right['right'] == P + f'landscape/climate/warp_{s[-1]}', 'Climate warp missing')
                require(abs(right['left'] - WARP_BLOCKS * noise['xz_scale']) < 1e-12, 'Unexpected climate warp amplitude')
        meta = decode('fabric.mod.json'); old_meta['version'] = VERSION
        require(meta == old_meta, 'Unexpected mod metadata changes')
        manifest = decode(MANIFEST); data = {n: h for n, h in new.items() if n.startswith('data/')}
        require(manifest['generated_sha256'] == data and manifest['generated_entries'] == len(data) == 441, 'Manifest inventory differs')
        require(manifest['default_world_preset'] == 'minecraft:normal', 'Default world selection changed')
        for n in names:
            if n.endswith('.class'):
                body = z.read(n).lower()
                require(not any(w in body for w in (b'hydrolog', b'watershed', b'drainage')), 'Removed custom water code returned: ' + n)
        caves = [n for n in names if '/cave_network/' in n or '/cave_access/' in n or n.endswith('/final_density.json')]
        require(caves and all(old.get(n) == new[n] for n in caves), 'Cave schema must stay byte-identical')
        return dict(version=VERSION, sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(), generated_resources=len(data),
                    identical_resources=sum(old.get(n) == h for n, h in new.items()), cave_resources_identical=len(caves),
                    added=sorted(ADDED), changed=sorted(CHANGED),
                    scope='Package and emitted graph contract; native measurements are separate')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate', type=Path, default=CANDIDATE); p.add_argument('--baseline', type=Path, default=BASELINE)
    p.add_argument('--output', type=Path)
    a = p.parse_args(); r = validate(a.candidate, a.baseline)
    if a.output: a.output.parent.mkdir(parents=True, exist_ok=True); a.output.write_text(json.dumps(r, indent=2) + '\n')
    print(json.dumps(r, indent=2))
