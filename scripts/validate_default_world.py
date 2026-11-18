"""Validate FreshGenerator as the native new-world Default against frozen 0.7.0.

All 59 runtime classes and every terrain/cave/glacier resource retain their bytes.
Only the normal-preset alias and removal of the extra FreshGenerator menu option
are permitted. Actual default factory selection and old-save reopening require
the separate native registry/server audit.
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

VERSION = '0.7.1-alpha.1'
REPOSITORY = Path(__file__).resolve().parent.parent
CANDIDATE = REPOSITORY/f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE_FIXTURE = Path(__file__).resolve().parent/'fixtures/terrain-baseline-0.7.0.json'
FIXTURE_SHA256 = 'ea0b1dc1a6e82ed5430c0cbf4311cc24d6fc6e2fbe7c103eab3cab2703c44c63'
BASELINE_SHA256 = 'b528653e0cdf1f2e1ca7b1b7bdfe27376f0f617ba71e4bef1d4e7def7779af79'
MANIFEST = 'freshgenerator-worldgen-manifest.json'
NORMAL_TAG = 'data/minecraft/tags/worldgen/world_preset/normal.json'
DEFAULT_PRESET = 'data/minecraft/worldgen/world_preset/normal.json'
FRESH_PRESET = 'data/freshgenerator/worldgen/world_preset/world.json'
SETTINGS = 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
PREFIX = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
ID = 'freshgenerator:highlands_v2/'
FORBIDDEN = (b'hydrolog', b'watershed', b'drainage')


def require(condition,message):
    if not condition:
        raise AssertionError(message)


def decoded(archive,name):
    return json.loads(archive.read(name))


def baseline_fixture(baseline=None):
    raw = BASELINE_FIXTURE.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == FIXTURE_SHA256, 'Frozen baseline fixture changed')
    frozen = json.loads(raw)
    require(frozen['schema'] == 1 and frozen['baseline_version'] == '0.7.0-alpha.1'
            and frozen['baseline_sha256'] == BASELINE_SHA256, 'Wrong baseline fixture provenance')
    hashes = frozen['entry_sha256']
    require(len(hashes) == 483 and sum(n.endswith('.class') for n in hashes) == 59
            and sum(n.startswith('data/') for n in hashes) == 418,
            'Unexpected frozen baseline inventory')
    if baseline is not None:
        baseline = Path(baseline)
        require(hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA256,
                'Wrong accepted 0.7.0 baseline')
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
                    == frozen['manifest_header'] and manifest['generated_entries'] == 418
                    and manifest['generated_sha256'] == {n:h for n,h in hashes.items() if n.startswith('data/')},
                    'Baseline JAR and fixture worldgen manifest differ')
    return frozen


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
        require(after-before == {DEFAULT_PRESET},
                f'Unreviewed new package entries: {sorted((after-before)^{DEFAULT_PRESET})}')
        classes = {n for n in after if n.endswith('.class')}
        old_classes = {n for n in before if n.endswith('.class')}
        require(classes == old_classes and len(classes) == 59, 'Runtime class inventory changed')
        require(all(old_hashes[n] == hashes[n] for n in classes), 'Accepted runtime or glacial bytecode changed')
        changed = {n for n in before if old_hashes[n] != hashes[n]}
        expected_changed = {NORMAL_TAG,'fabric.mod.json',MANIFEST}
        require(changed == expected_changed,
                f'Unexpected changed package entries: {sorted(changed^expected_changed)}')
        old_data = {n for n in before if n.startswith('data/')}
        data = {n for n in after if n.startswith('data/')}
        require(data == old_data|{DEFAULT_PRESET} and len(data) == 419,
                'Unexpected worldgen resource inventory')
        require(all(old_hashes[n] == hashes[n] for n in old_data-{NORMAL_TAG}),
                'Accepted terrain, cave, glacial or biome resource changed')
        require(hashes['freshgenerator.mixins.json'] == old_hashes['freshgenerator.mixins.json'],
                'Mixin configuration changed')
        require(decoded(new,'fabric.mod.json') == dict(frozen['metadata'],version=VERSION),
                'Mod metadata changed beyond version')
        require(decoded(new,NORMAL_TAG) == {'replace':False,'values':[]},
                'Extra FreshGenerator cycling option restored or native alternatives replaced')
        require(hashes[DEFAULT_PRESET] == old_hashes[FRESH_PRESET]
                and decoded(new,DEFAULT_PRESET) == decoded(new,FRESH_PRESET),
                'Native Default is not the exact accepted FreshGenerator preset')
        dimensions = decoded(new,DEFAULT_PRESET)['dimensions']
        require(dimensions['minecraft:overworld']['generator']['settings'] == 'freshgenerator:highlands_v2'
                and dimensions['minecraft:the_nether']['generator']['settings'] == 'minecraft:nether'
                and dimensions['minecraft:the_end']['generator']['settings'] == 'minecraft:end',
                'Default dimensions do not retain the reviewed generator settings')
        require('data/minecraft/worldgen/noise_settings/overworld.json' not in after,
                'Vanilla noise settings overridden instead of aliasing new-world Default')
        manifest = decoded(new,MANIFEST)
        require(manifest['generated_entries'] == 419 and manifest['generated_sha256'] == {n:hashes[n] for n in data},
                'Invalid worldgen manifest resource count or digest')
        expected_header = dict(frozen['manifest_header'],
                               selectable_world_presets=['minecraft:normal'],
                               default_world_preset='minecraft:normal',
                               compatibility_world_presets=['freshgenerator:world'])
        require({k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}
                == expected_header, 'Unreviewed native source fingerprints/world metadata change')
        for name in classes|data:
            require(not any(t in name.lower().encode() or t in new.read(name).lower() for t in FORBIDDEN),
                    f'Removed custom water subsystem reference: {name}')
        settings = decoded(new,SETTINGS)
        require(settings == frozen['settings'], 'Accepted noise/cave/aquifer settings changed')
        debug = {v['label']:v['function'] for v in settings['debug_functions']}
        require(debug.get('FG caves disabled') == ID+'coherent/surface_only/caves_disabled'
                and decoded(new,PREFIX+'coherent/surface_only/caves_disabled.json') == 1,
                'Independent native carvers no longer disabled')
        require(debug.get('FG cave network enabled') == ID+'cave_network/enabled'
                and decoded(new,PREFIX+'cave_network/enabled.json') == 1,
                'Accepted cave network no longer enabled')
        require(settings['sea_level'] == 63 and settings['default_fluid'] == 'minecraft:water',
                'Sea level or sea fluid changed')
        return dict(version=VERSION,sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    baseline_version='0.7.0-alpha.1',baseline_sha256=BASELINE_SHA256,
                    baseline_fixture_sha256=FIXTURE_SHA256,baseline_jar_verified=baseline is not None,
                    runtime_classes=59,unchanged_runtime_classes=59,changed_runtime_classes=[],
                    unchanged_mixins=True,glacial_runtime_unchanged=True,cave_pipeline_unchanged=True,
                    identical_worldgen_resources=417,generated_resources=419,
                    changed_resources=[NORMAL_TAG],added_resources=[DEFAULT_PRESET],
                    default_world_preset='minecraft:normal',default_generator_settings='freshgenerator:highlands_v2',
                    extra_freshgenerator_cycling_option=False,native_preset_alternatives_preserved=True,
                    custom_water_system_present=False,caves_enabled=True,native_carvers_disabled=True,
                    sea_level=63,
                    scope='Package scope only; default factory selection and old-save preservation require native registry/server audit')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',type=Path,default=CANDIDATE)
    parser.add_argument('--baseline',type=Path,
                        help='Optionally verify the original 0.7.0 JAR alongside the pinned text fixture')
    parser.add_argument('--report',type=Path,
                        default=REPOSITORY/'build/reports/village-release/default-world-validation.json')
    args = parser.parse_args()
    result = validate(args.candidate,args.baseline)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('DEFAULT_WORLD_PACKAGE_PASS',json.dumps(result))
