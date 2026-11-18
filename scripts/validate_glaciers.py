"""Freeze the glacial runtime change scope against the accepted 0.6.0 cave release.

Every old runtime class and all 418 worldgen resources must retain their exact
bytes. This validates package scope, not ice morphology, native placement safety
or cave accessibility; those require the separate native block audit.
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

VERSION = '0.7.0-alpha.1'
REPOSITORY = Path(__file__).resolve().parent.parent
CANDIDATE = REPOSITORY/f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar'
BASELINE_FIXTURE = Path(__file__).resolve().parent/'fixtures/glacier-baseline-0.6.0.json'
FIXTURE_SHA256 = 'ec3fdd0a41b85c1807f42e997b2cc0e06de1bdead7e04f7ac153fc078b4f85c2'
BASELINE_SHA256 = '1d7140e4cac06ae58efbf299d53a4f4506c06303d441dad342e81152342c6664'
MANIFEST = 'freshgenerator-worldgen-manifest.json'
SETTINGS = 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
PREFIX = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
ID = 'freshgenerator:highlands_v2/'
ADDED_MIXINS = ['GlacialSurfaceMixin', 'GlacialMaterialMixin', 'GlacialIcebergMixin']
ADDED_CLASSES = {
    'dev/freshgenerator/core/GlacialIce.class',
    'dev/freshgenerator/core/GlacialIce$OceanColumn.class',
    'dev/freshgenerator/core/GlacialIce$LandSite.class',
    'dev/freshgenerator/core/GlacialIce$LandColumn.class',
    'dev/freshgenerator/ice/GlacialSurface.class',
    'dev/freshgenerator/ice/GlacialSurface$Environment.class',
} | {'dev/freshgenerator/mixin/'+name+'.class' for name in ADDED_MIXINS}
MUTABLE_METADATA = {'fabric.mod.json', 'freshgenerator.mixins.json'}
FORBIDDEN = (b'hydrolog', b'watershed', b'drainage')


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def decoded(archive, name):
    return json.loads(archive.read(name))


def baseline_fixture(baseline=None):
    raw = BASELINE_FIXTURE.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == FIXTURE_SHA256, 'Frozen baseline fixture changed')
    frozen = json.loads(raw)
    require(frozen['schema'] == 1 and frozen['baseline_version'] == '0.6.0-alpha.1'
            and frozen['baseline_sha256'] == BASELINE_SHA256, 'Wrong baseline fixture provenance')
    hashes = frozen['entry_sha256']
    require(len(hashes) == 474 and sum(n.endswith('.class') for n in hashes) == 50
            and sum(n.startswith('data/') for n in hashes) == 418,
            'Unexpected frozen baseline inventory')
    if baseline is not None:
        baseline = Path(baseline)
        require(hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA256,
                'Wrong accepted 0.6.0 baseline')
        with ZipFile(baseline) as old:
            names = [n for n in old.namelist() if not n.endswith('/')]
            require(len(names) == len(set(names)) and set(names) == set(hashes),
                    'Baseline JAR and fixture inventories differ')
            require(all(hashlib.sha256(old.read(n)).hexdigest() == h for n,h in hashes.items()),
                    'Baseline JAR and fixture entry hashes differ')
            require(decoded(old, 'fabric.mod.json') == frozen['metadata']
                    and decoded(old, 'freshgenerator.mixins.json') == frozen['mixin_config'],
                    'Baseline JAR and fixture metadata differ')
            manifest = decoded(old, MANIFEST)
            require({k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}
                    == frozen['manifest_header'] and manifest['generated_entries'] == 418
                    and manifest['generated_sha256'] == {n:h for n,h in hashes.items() if n.startswith('data/')},
                    'Baseline JAR and fixture worldgen manifest differ')
    return frozen


def validate(candidate=CANDIDATE, baseline=None):
    candidate = Path(candidate)
    frozen = baseline_fixture(baseline)
    with ZipFile(candidate) as new:
        old_hashes = frozen['entry_sha256']
        names = [n for n in new.namelist() if not n.endswith('/')]
        require(len(names) == len(set(names)), 'Duplicate package entries')
        hashes = {n:hashlib.sha256(new.read(n)).hexdigest() for n in names}
        before, after = set(old_hashes), set(names)
        require(before <= after, f'Existing package entries removed: {sorted(before-after)}')
        require(after-before == ADDED_CLASSES,
                f'Unreviewed new package entries: {sorted((after-before)^ADDED_CLASSES)}')
        classes = {n for n in after if n.endswith('.class')}
        old_classes = {n for n in before if n.endswith('.class')}
        require(classes == old_classes|ADDED_CLASSES, 'Runtime class inventory differs')
        require(all(old_hashes[n] == hashes[n] for n in old_classes), 'Accepted runtime bytecode changed')
        changed = {n for n in before if old_hashes[n] != hashes[n]}
        require(changed == MUTABLE_METADATA,
                f'Unexpected changed package entries: {sorted(changed^MUTABLE_METADATA)}')
        data = {n for n in after if n.startswith('data/')}
        require(data == {n for n in before if n.startswith('data/')} and len(data) == 418,
                'Worldgen resource inventory changed')
        require(all(old_hashes[n] == hashes[n] for n in data), 'Accepted terrain, cave or biome resource changed')

        meta = decoded(new, 'fabric.mod.json')
        expected_meta = dict(frozen['metadata'], version=VERSION)
        require(meta == expected_meta, 'Mod metadata changed beyond version')
        require(meta['entrypoints'] == {'main':['dev.freshgenerator.FreshGenerator']}
                and meta['mixins'] == ['freshgenerator.mixins.json'], 'Unexpected runtime entrypoint/configuration')
        expected_mixins = dict(frozen['mixin_config'])
        expected_mixins['mixins'] = expected_mixins['mixins']+ADDED_MIXINS
        require(decoded(new, 'freshgenerator.mixins.json') == expected_mixins,
                'Expected exactly three appended glacial mixins')
        manifest = decoded(new, MANIFEST)
        require(manifest['generated_entries'] == 418 and manifest['generated_sha256'] == {n:hashes[n] for n in data},
                'Invalid worldgen manifest resource count or digest')
        require({k:v for k,v in manifest.items() if k not in {'generated_entries','generated_sha256'}}
                == frozen['manifest_header'], 'Native source fingerprints/world metadata changed')
        for name in classes|data:
            require(not any(t in name.lower().encode() or t in new.read(name).lower() for t in FORBIDDEN),
                    f'Removed custom water subsystem reference: {name}')

        settings = decoded(new, SETTINGS)
        debug = {v['label']:v['function'] for v in settings['debug_functions']}
        require(debug.get('FG caves disabled') == ID+'coherent/surface_only/caves_disabled'
                and decoded(new, PREFIX+'coherent/surface_only/caves_disabled.json') == 1,
                'Independent native carvers no longer disabled')
        require(debug.get('FG cave network enabled') == ID+'cave_network/enabled'
                and decoded(new, PREFIX+'cave_network/enabled.json') == 1,
                'Accepted cave network no longer enabled')
        require(settings['sea_level'] == 63 and settings['default_fluid'] == 'minecraft:water',
                'Sea level or sea fluid changed')
        require(decoded(new, 'data/minecraft/tags/worldgen/world_preset/normal.json')['values']
                == ['freshgenerator:world'], 'Expected one public world preset')
        return dict(version=VERSION, sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    baseline_version='0.6.0-alpha.1', baseline_sha256=BASELINE_SHA256,
                    baseline_fixture_sha256=FIXTURE_SHA256, baseline_jar_verified=baseline is not None,
                    runtime_classes=len(classes), unchanged_runtime_classes=len(old_classes),
                    changed_runtime_classes=[], added_runtime_classes=sorted(ADDED_CLASSES),
                    appended_mixins=ADDED_MIXINS, identical_worldgen_resources=418,
                    worldgen_manifest_unchanged=True, cave_pipeline_unchanged=True,
                    custom_water_system_present=False, caves_enabled=True, native_carvers_disabled=True,
                    public_world_presets=1, sea_level=63,
                    scope='Packaged change scope only; glacier/iceberg geometry and cave access require native block audit')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, default=CANDIDATE)
    parser.add_argument('--baseline', type=Path,
                        help='Optionally verify the original 0.6.0 JAR alongside the pinned text fixture')
    parser.add_argument('--report', type=Path,
                        default=REPOSITORY/'build/reports/village-release/glacier-validation.json')
    args = parser.parse_args()
    result = validate(args.candidate,args.baseline)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('GLACIAL_PACKAGE_PASS',json.dumps(result))
