"""Validate the packaged scope of the native cave layer against frozen 0.5.2.

This checks package contents and native-graph boundaries, not cave connectivity,
visual quality or world acceptance. Those require the separate native block audit.
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

VERSION = '0.6.0-alpha.1'
REPOSITORY = Path(__file__).resolve().parent.parent
CANDIDATE = REPOSITORY / f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE_FIXTURE = Path(__file__).resolve().parent / 'fixtures/cave-baseline-0.5.2.json'
FIXTURE_SHA256 = 'cb7d77b7ee4a9add086c81b0f027b9cb13b476b7fa1982f6ff20205edfad8cc8'
BASELINE_SHA256 = '4fa901415ab2c3c58bbdcb26679966116377759b7ed9a54c7a9b3f47959158f8'
PREFIX = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
ID = 'freshgenerator:highlands_v2/'
SETTINGS = 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
MANIFEST = 'freshgenerator-worldgen-manifest.json'
CARVER_MARKER = PREFIX + 'coherent/surface_only/caves_disabled.json'
CHANGED_RESOURCES = {PREFIX + 'final_density.json', PREFIX + 'coherent/cave_weight.json', SETTINGS}
# Exact reviewed inventory is deliberately independent of the candidate manifest.
DENSITY_NODES = ('enabled', 'mountain_gate', 'entrance_gate', 'ceiling', 'depth_limit',
                 'chambers', 'native_passages', 'links', 'body', 'final_body')
NOISE_NODES = ('link_a', 'link_b')
ADDED_RESOURCES = {PREFIX + 'cave_network/' + n + '.json' for n in DENSITY_NODES}
ADDED_RESOURCES |= {'data/freshgenerator/worldgen/noise/highlands_v2/cave_network/' + n + '.json'
                    for n in NOISE_NODES}
NETWORK_ENABLED = ID + 'cave_network/enabled'
DEBUG_ADDITIONS = tuple({'label':'FG cave network '+name, 'function':ID+'cave_network/'+name}
                        for name in ('enabled', 'mountain_gate', 'entrance_gate', 'body', 'depth_limit', 'final_body'))
FORBIDDEN = (b'hydrolog', b'watershed', b'drainage')


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def decoded(archive, name):
    return json.loads(archive.read(name))


def custom_types(value):
    if isinstance(value, dict):
        found = {value['type']} if isinstance(value.get('type'), str) and not value['type'].startswith('minecraft:') else set()
        return found.union(*(custom_types(v) for v in value.values()))
    if isinstance(value, list):
        return set().union(*(custom_types(v) for v in value))
    return set()


def baseline_fixture(baseline=None):
    """The pinned text inventory carries every old entry hash for binary-free CI.

    A supplied local JAR must match both its historical whole-package SHA and the
    fixture. The default path needs only the checked-in, pinned text fixture.
    """
    raw = BASELINE_FIXTURE.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == FIXTURE_SHA256, 'Frozen baseline fixture changed')
    fixture = json.loads(raw)
    require(fixture['schema'] == 1 and fixture['baseline_version'] == '0.5.2-alpha.1'
            and fixture['baseline_sha256'] == BASELINE_SHA256, 'Wrong baseline fixture provenance')
    inventory = fixture['entry_sha256']
    require(len(inventory) == 462 and sum(n.endswith('.class') for n in inventory) == 50
            and sum(n.startswith('data/') for n in inventory) == 406,
            'Unexpected frozen baseline inventory')
    if baseline is not None:
        baseline = Path(baseline)
        require(hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA256,
                'Wrong immutable 0.5.2 baseline')
        with ZipFile(baseline) as old:
            names = [n for n in old.namelist() if not n.endswith('/')]
            require(len(names) == len(set(names)) and set(names) == set(inventory),
                    'Baseline JAR and fixture inventories differ')
            require(all(hashlib.sha256(old.read(n)).hexdigest() == h for n,h in inventory.items()),
                    'Baseline JAR and fixture entry hashes differ')
            require(decoded(old, 'fabric.mod.json') == fixture['metadata']
                    and decoded(old, SETTINGS) == fixture['settings'],
                    'Baseline JAR and fixture metadata differ')
            manifest = decoded(old, MANIFEST)
            require({k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}
                    == fixture['manifest_header'] and manifest['generated_entries'] == 406
                    and manifest['generated_sha256'] == {n:h for n,h in inventory.items() if n.startswith('data/')},
                    'Baseline JAR and fixture worldgen manifest differ')
    return fixture


def validate(candidate=CANDIDATE, baseline=None):
    candidate = Path(candidate)
    frozen = baseline_fixture(baseline)
    require(bool(DENSITY_NODES) and bool(NOISE_NODES) and bool(DEBUG_ADDITIONS),
            'Reviewed cave resource inventory has not been finalized')
    with ZipFile(candidate) as new:
        old_hashes = frozen['entry_sha256']
        new_list = [n for n in new.namelist() if not n.endswith('/')]
        require(len(new_list) == len(set(new_list)), 'Duplicate package entries')
        new_hashes = {n:hashlib.sha256(new.read(n)).hexdigest() for n in new_list}
        before, after = set(old_hashes), set(new_list)
        require(before <= after, f'Existing package entries removed: {sorted(before-after)}')
        require(after-before == ADDED_RESOURCES,
                f'Unexpected added package entries: {sorted((after-before)^ADDED_RESOURCES)}')
        old_classes = {n for n in before if n.endswith('.class')}
        classes = {n for n in after if n.endswith('.class')}
        require(classes == old_classes, 'Runtime class inventory changed')
        require(all(old_hashes[n] == new_hashes[n] for n in classes), 'Runtime bytecode changed')
        require(old_hashes['freshgenerator.mixins.json'] == new_hashes['freshgenerator.mixins.json'],
                'Mixin configuration changed')
        require(b'FG caves disabled' in new.read('dev/freshgenerator/mixin/CaveCarverMixin.class'),
                'Native carver opt-out missing')

        old_meta, meta = dict(frozen['metadata']), decoded(new, 'fabric.mod.json')
        require(meta['version'] == VERSION, 'Wrong candidate version')
        old_meta['version'] = VERSION
        require(old_meta == meta, 'Mod metadata changed beyond version')
        require(meta['entrypoints'] == {'main': ['dev.freshgenerator.FreshGenerator']},
                'Unexpected runtime entrypoint')
        require(meta['mixins'] == ['freshgenerator.mixins.json'], 'Extra mixin configuration')

        changed = {n for n in before if old_hashes[n] != new_hashes[n]}
        expected_changes = CHANGED_RESOURCES | {'fabric.mod.json', MANIFEST}
        require(changed == expected_changes,
                f'Unexpected changed package entries: {sorted(changed^expected_changes)}')
        old_generated = {n:h for n,h in old_hashes.items() if n.startswith('data/')}
        manifest = decoded(new, MANIFEST)
        mutable = {'generated_entries', 'generated_sha256'}
        require(frozen['manifest_header'] ==
                {k:v for k,v in manifest.items() if k not in mutable},
                'Native source fingerprints or world metadata changed')
        require(manifest['generated_entries'] == len(manifest['generated_sha256']),
                'Wrong manifest resource count')
        require(set(manifest['generated_sha256']) == {n for n in after if n.startswith('data/')},
                'Unmanifested data resources')
        require(set(manifest['generated_sha256']) - set(old_generated) == ADDED_RESOURCES,
                'Manifest additions differ from reviewed resources')
        baseline_codecs, codecs = set(frozen['custom_codec_types']), set()
        for name, digest in manifest['generated_sha256'].items():
            raw = new.read(name)
            require(new_hashes[name] == digest, f'Invalid manifest digest: {name}')
            require(not any(t in name.lower().encode() or t in raw.lower() for t in FORBIDDEN),
                    f'Removed custom water reference: {name}')
            codecs |= custom_types(json.loads(raw))
        require(codecs <= baseline_codecs, f'New custom worldgen codecs: {sorted(codecs-baseline_codecs)}')
        for name in classes:
            raw = new.read(name).lower()
            require(not any(t in name.lower().encode() or t in raw for t in FORBIDDEN),
                    f'Removed custom water reference in runtime: {name}')

        old_settings, settings = frozen['settings'], decoded(new, SETTINGS)
        require({k:v for k,v in old_settings.items() if k != 'debug_functions'} ==
                {k:v for k,v in settings.items() if k != 'debug_functions'},
                'Noise settings changed outside diagnostic samplers')
        require(settings['debug_functions'] == old_settings['debug_functions'] + list(DEBUG_ADDITIONS),
                'Unexpected debug sampler additions or replacements')
        debug = {v['label']:v['function'] for v in settings['debug_functions']}
        require(len(debug) == len(settings['debug_functions']), 'Duplicate diagnostic sampler labels')
        require(debug.get('FG caves disabled') == ID+'coherent/surface_only/caves_disabled'
                and decoded(new, CARVER_MARKER) == 1, 'Native carvers enabled')
        require(debug.get('FG cave network enabled') == NETWORK_ENABLED
                and decoded(new, PREFIX+'cave_network/enabled.json') == 1,
                'Native noise cave layer marker missing')
        require(decoded(new, PREFIX+'coherent/cave_weight.json') == 1, 'Cave layer marker is not enabled')
        require(settings['sea_level'] == 63 and settings['default_fluid'] == 'minecraft:water',
                'Sea level or fluid changed')
        normal = decoded(new, 'data/minecraft/tags/worldgen/world_preset/normal.json')
        require(normal['values'] == ['freshgenerator:world'], 'Expected one public world preset')
        final = decoded(new, PREFIX+'final_density.json')
        require(final == {'type':'minecraft:add', 'left':ID+'cave_network/final_body',
                          'right':{'type':'minecraft:beardifier'}},
                'Native structure beardifier no longer outside cave terrain body')
        require(decoded(new, PREFIX+'cave_network/final_body.json') == {
                    'type':'minecraft:min', 'left':ID+'coherent/surface_density',
                    'right':{'type':'minecraft:max', 'left':ID+'cave_network/body',
                             'right':ID+'cave_network/depth_limit'}},
                'Caves are no longer capped by the exterior and post-interpolation roof/floor')
        return dict(version=VERSION, sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    baseline_version='0.5.2-alpha.1', baseline_sha256=BASELINE_SHA256,
                    baseline_fixture_sha256=FIXTURE_SHA256, baseline_jar_verified=baseline is not None,
                    runtime_classes=len(classes), unchanged_runtime_classes=len(classes),
                    changed_runtime_classes=[], added_runtime_classes=[], unchanged_mixins=True,
                    generated_resources=len(manifest['generated_sha256']),
                    changed_resources=sorted(CHANGED_RESOURCES), added_resources=sorted(ADDED_RESOURCES),
                    custom_water_system_present=False, caves_enabled=True, native_carvers_disabled=True,
                    public_world_presets=1, sea_level=63, height_interpolation_blocks=4,
                    scope='Package scope only; connectivity and entrance constraints require native block audit')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path,
                        help='Optionally verify the original 0.5.2 JAR as well as the pinned text fixture')
    parser.add_argument('--candidate', type=Path, default=CANDIDATE)
    parser.add_argument('--report', type=Path, default=REPOSITORY/'build/reports/village-release/cave-validation.json')
    args = parser.parse_args()
    result = validate(args.candidate, args.baseline)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('CAVE_NETWORK_PACKAGE_PASS',json.dumps(result))
