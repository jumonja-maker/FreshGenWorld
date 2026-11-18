"""Validate the bounded 0.7.2 cave-roof change against accepted 0.7.1.

This is a package/graph contract, not a replacement for actual block topology,
native cave connectivity, generation-order or save/reload measurements.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
from zipfile import ZipFile

VERSION = '0.7.2-alpha.1'
REPOSITORY = Path(__file__).resolve().parent.parent
CANDIDATE = REPOSITORY/f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE_FIXTURE = Path(__file__).resolve().parent/'fixtures/world-baseline-0.7.1.json'
FIXTURE_SHA256 = 'aab4feed2b03323360b0e87b6c40cc77ceb0b69c15ff082e6f823310870914d3'
BASELINE_SHA256 = 'c0d0232cee6487fd9f4368778c0b45660e2bb9b69e69f771bf778d307fdb117a'
MANIFEST = 'freshgenerator-worldgen-manifest.json'
NORMAL_TAG = 'data/minecraft/tags/worldgen/world_preset/normal.json'
DEFAULT_PRESET = 'data/minecraft/worldgen/world_preset/normal.json'
FRESH_PRESET = 'data/freshgenerator/worldgen/world_preset/world.json'
SETTINGS = 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
PREFIX = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
ID = 'freshgenerator:highlands_v2/'
N = ID+'cave_network/'
ADDED = frozenset(PREFIX+'cave_network/'+n+'.json' for n in
                  ('deep_ceiling','access_footprint','access_body','access_limit'))
CHANGED = frozenset(PREFIX+'cave_network/'+n+'.json' for n in ('ceiling','depth_limit','final_body'))
FORBIDDEN = (b'hydrolog', b'watershed', b'drainage')


def require(condition,message):
    if not condition:
        raise AssertionError(message)


def decoded(archive,name):
    return json.loads(archive.read(name))


def same_json(left,right):
    # Python otherwise considers False == 0 and True == 1. Native codecs do not
    # accept booleans as density scalars, so those substitutions must fail.
    if isinstance(left,bool) or isinstance(right,bool):
        return type(left) is type(right) and left == right
    if isinstance(left,dict) or isinstance(right,dict):
        return isinstance(left,dict) and isinstance(right,dict) and left.keys() == right.keys() \
            and all(same_json(value,right[key]) for key,value in left.items())
    if isinstance(left,list) or isinstance(right,list):
        return isinstance(left,list) and isinstance(right,list) and len(left) == len(right) \
            and all(same_json(a,b) for a,b in zip(left,right))
    if isinstance(left,(int,float)) and isinstance(right,(int,float)):
        return math.isfinite(left) and math.isfinite(right) and left == right
    return type(left) is type(right) and left == right


def baseline_fixture(baseline=None):
    raw = BASELINE_FIXTURE.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == FIXTURE_SHA256, 'Frozen baseline fixture changed')
    frozen = json.loads(raw)
    require(frozen['schema'] == 1 and frozen['baseline_version'] == '0.7.1-alpha.1'
            and frozen['baseline_sha256'] == BASELINE_SHA256, 'Wrong baseline fixture provenance')
    hashes = frozen['entry_sha256']
    require(len(hashes) == 484 and sum(n.endswith('.class') for n in hashes) == 59
            and sum(n.startswith('data/') for n in hashes) == 419,
            'Unexpected frozen baseline inventory')
    if baseline is not None:
        baseline = Path(baseline)
        require(hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA256,
                'Wrong accepted 0.7.1 baseline')
        with ZipFile(baseline) as old:
            names = [n for n in old.namelist() if not n.endswith('/')]
            require(len(names) == len(set(names)) and set(names) == set(hashes),
                    'Baseline JAR and fixture inventories differ')
            require(all(hashlib.sha256(old.read(n)).hexdigest() == h for n,h in hashes.items()),
                    'Baseline JAR and fixture entry hashes differ')
            require(decoded(old,'fabric.mod.json') == frozen['metadata']
                    and decoded(old,SETTINGS) == frozen['settings'],
                    'Baseline JAR and fixture metadata differ')
            manifest = decoded(old,MANIFEST)
            require({k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}
                    == frozen['manifest_header'] and manifest['generated_entries'] == 419
                    and manifest['generated_sha256'] == {n:h for n,h in hashes.items() if n.startswith('data/')},
                    'Baseline JAR and fixture worldgen manifest differ')
    return frozen


def binary(operation,left,right):
    return {'type':'minecraft:'+operation,'left':left,'right':right}


def expected_cave_graph(frozen):
    """Independent exact allowlist: a rewritten manifest cannot approve a graph."""
    b = binary
    old = frozen['cave_nodes']
    deep = {'type':'minecraft:cache','input':b('min',b('sub',ID+'coherent/height',40),20)}
    # Only the Y scale and width differ from the already accepted linking noise.
    footprint = copy.deepcopy(old['links'])
    require(footprint['type'] == 'minecraft:sub' and footprint['right'] == .16,
            'Unexpected accepted deep-link width')
    footprint['right'] = .11
    for side in ('left','right'):
        noise = footprint['left'][side]['input']
        require(noise['type'] == 'minecraft:noise' and noise['xz_scale'] == 1
                and noise['y_scale'] == 1.25 and noise['noise'] == N+'link_'+('a' if side == 'left' else 'b'),
                'Unexpected accepted link field')
        noise['y_scale'] = 0
    grid = old['body']
    require(grid['type'] == 'minecraft:interpolated' and grid['cell_size_xz'] == 4
            and grid['cell_size_y'] == 4, 'Unexpected accepted cave interpolation')
    access = {k:copy.deepcopy(v) for k,v in grid.items() if k != 'input'}
    access['input'] = footprint
    depth = b('max',b('mul',.125,b('sub','minecraft:y',N+'deep_ceiling')),
              b('mul',.125,b('sub',-54,'minecraft:y')))
    limit = b('max',b('mul',.125,b('sub','minecraft:y',N+'ceiling')),
              b('mul',.125,b('sub',-4,'minecraft:y')))
    return {
        'deep_ceiling':deep,
        # The entrance decision is constant in Y. Leaving even a fractional
        # positive roof above an otherwise carved shaft can strand a one-block
        # cap, so permitted access reaches the exact terrain envelope.
        'ceiling':{'type':'minecraft:cache','input':ID+'coherent/height'},
        'access_footprint':access,
        'access_body':b('max',N+'access_footprint',b('sub',.5,N+'entrance_gate')),
        'access_limit':limit,
        'depth_limit':depth,
        'final_body':b('min',ID+'coherent/surface_density',
                       b('min',b('max',N+'body',N+'depth_limit'),
                         b('max',N+'access_body',N+'access_limit'))),
    }


def validate(candidate=CANDIDATE,baseline=None):
    candidate = Path(candidate)
    frozen = baseline_fixture(baseline)
    with ZipFile(candidate) as new:
        old_hashes = frozen['entry_sha256']
        names = [n for n in new.namelist() if not n.endswith('/')]
        require(len(names) == len(set(names)), 'Duplicate package entries')
        hashes = {n:hashlib.sha256(new.read(n)).hexdigest() for n in names}
        before,after = set(old_hashes),set(names)
        require(before <= after, f'Existing package entries removed: {sorted(before-after)}')
        require(after-before == ADDED,
                f'Unreviewed new package entries: {sorted((after-before)^ADDED)}')
        classes = {n for n in after if n.endswith('.class')}
        require(classes == {n for n in before if n.endswith('.class')} and len(classes) == 59,
                'Runtime class inventory changed')
        require(all(old_hashes[n] == hashes[n] for n in classes), 'Accepted runtime bytecode changed')
        changed = {n for n in before if old_hashes[n] != hashes[n]}
        expected_changed = CHANGED|{'fabric.mod.json',MANIFEST}
        require(changed == expected_changed,
                f'Unexpected changed package entries: {sorted(changed^expected_changed)}')
        data = {n for n in after if n.startswith('data/')}
        old_data = {n for n in before if n.startswith('data/')}
        require(data == old_data|ADDED and len(data) == 423, 'Unexpected worldgen resource inventory')
        require(all(old_hashes[n] == hashes[n] for n in old_data-CHANGED),
                'Accepted terrain, biome, glacier, water or unrelated cave data changed')
        require(same_json(decoded(new,'fabric.mod.json'),dict(frozen['metadata'],version=VERSION)),
                'Mod metadata changed beyond version')
        require(same_json(decoded(new,NORMAL_TAG),{'replace':False,'values':[]}),
                'Extra cycling option restored or native alternatives replaced')
        require(hashes[DEFAULT_PRESET] == hashes[FRESH_PRESET]
                and decoded(new,DEFAULT_PRESET) == decoded(new,FRESH_PRESET),
                'Native Default no longer aliases the exact FreshGenerator preset')
        dimensions = decoded(new,DEFAULT_PRESET)['dimensions']
        require(dimensions['minecraft:overworld']['generator']['settings'] == 'freshgenerator:highlands_v2'
                and dimensions['minecraft:the_nether']['generator']['settings'] == 'minecraft:nether'
                and dimensions['minecraft:the_end']['generator']['settings'] == 'minecraft:end',
                'Default dimension settings changed')
        require('data/minecraft/worldgen/noise_settings/overworld.json' not in after,
                'Vanilla saved-world noise settings overridden')
        manifest = decoded(new,MANIFEST)
        require(manifest['generated_entries'] == 423 and manifest['generated_sha256'] == {n:hashes[n] for n in data},
                'Invalid worldgen manifest resource count or digest')
        require({k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}
                == frozen['manifest_header'], 'Native fingerprints or world metadata changed')
        for name in classes|data:
            require(not any(t in name.lower().encode() or t in new.read(name).lower() for t in FORBIDDEN),
                    f'Removed custom water subsystem reference: {name}')
        require(decoded(new,SETTINGS) == frozen['settings'], 'Accepted noise/aquifer settings changed')
        for name,expected in expected_cave_graph(frozen).items():
            require(same_json(decoded(new,PREFIX+'cave_network/'+name+'.json'),expected),
                    f'Unreviewed cave roof/access/junction graph: {name}')
        debug = {v['label']:v['function'] for v in frozen['settings']['debug_functions']}
        require(debug.get('FG caves disabled') == ID+'coherent/surface_only/caves_disabled'
                and decoded(new,PREFIX+'coherent/surface_only/caves_disabled.json') == 1,
                'Independent native carvers no longer disabled')
        require(debug.get('FG cave network enabled') == N+'enabled'
                and decoded(new,PREFIX+'cave_network/enabled.json') == 1,
                'Cave network no longer enabled')
        return dict(version=VERSION,sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    baseline_version='0.7.1-alpha.1',baseline_sha256=BASELINE_SHA256,
                    baseline_fixture_sha256=FIXTURE_SHA256,baseline_jar_verified=baseline is not None,
                    runtime_classes=59,unchanged_runtime_classes=59,unchanged_mixins=True,
                    identical_worldgen_resources=416,generated_resources=423,
                    changed_resources=sorted(CHANGED),added_resources=sorted(ADDED),
                    terrain_biomes_glaciers_unchanged=True,deep_cave_field_unchanged=True,
                    wide_chamber_ceiling='min(surface-40,20)',access_width=.11,deep_link_width=.16,
                    access_y_invariant=True,access_reaches_exact_surface=True,
                    matching_interpolation_grid=[4,4],access_junction_y=0,
                    native_carvers_disabled=True,custom_water_system_present=False,
                    default_world_preset='minecraft:normal',default_generator_settings='freshgenerator:highlands_v2',
                    scope='Package and exact density graph only; solid support, native connectivity, chunk seams and save/reload require separate measurements')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',type=Path,default=CANDIDATE)
    parser.add_argument('--baseline',type=Path,help='Optionally verify the original accepted 0.7.1 JAR')
    parser.add_argument('--report',type=Path,default=REPOSITORY/'build/reports/village-release/world-integrity-validation.json')
    args = parser.parse_args()
    result = validate(args.candidate,args.baseline)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('WORLD_INTEGRITY_PACKAGE_PASS',json.dumps(result))
