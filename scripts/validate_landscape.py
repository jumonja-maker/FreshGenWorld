"""Validate the 0.5 landscape against the immutable, published pre-water package.

Only the cave opt-out, reviewed landscape/village classes and explicit resources
may differ. This checks packaged scope, not visual realism or world acceptance.
"""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

VERSION = '0.5.2-alpha.1'
CANDIDATE = Path(f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar')
BASELINE = Path('dist/FreshGenerator-Fabric-26.3-0.3.6-alpha.1.jar')
BASELINE_SHA256 = '42f35e81107affb2f24b23146c10e98a351e63ccf79fe7893ae8724a4a9f3a73'
PREFIX = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
ID = 'freshgenerator:highlands_v2/'
SETTINGS = 'data/freshgenerator/worldgen/noise_settings/highlands_v2.json'
PROFILE = 'data/freshgenerator/terrain_profile/highlands_v2.json'
CAVE_MARKER = PREFIX + 'coherent/surface_only/caves_disabled.json'
CLIMATE_MARKER = PREFIX + 'landscape/climate/enabled.json'

# SurfaceOnlyCompiler.REPLACED, LandscapeTerrainCompiler.REPLACED and
# LandscapeBiomeCompiler.REPLACED, explicitly enumerated rather than inferred
# from the candidate being checked.
CHANGED_RESOURCES = {SETTINGS, PROFILE} | {PREFIX + p + '.json' for p in (
    'coherent/surface_density', 'coherent/cave_weight', 'final_density',
    'continents', 'coherent/height', 'temperature', 'vegetation',
    'regions/biome_temperature', 'regions/biome_vegetation')}
CHANGED_RESOURCES |= {'data/freshgenerator/worldgen/material_rule/highlands_v2/' + p + '.json'
                      for p in ('biome_surface', 'under_biome_surface')}
LANDSCAPE_PREFIXES = (
    PREFIX + 'landscape/',
    'data/freshgenerator/worldgen/noise/highlands_v2/landscape/',
)
REVIEWED_CLASSES = {
    'dev/freshgenerator/mixin/CaveCarverMixin.class',
    'dev/freshgenerator/core/ForestEcology.class',
    'dev/freshgenerator/core/ForestEcology$Family.class',
    'dev/freshgenerator/forest/ForestEnvironment.class',
    'dev/freshgenerator/forest/ForestTelemetry.class',
    'dev/freshgenerator/core/EcotoneModel.class',
    'dev/freshgenerator/surface/SnowTransitions.class',
    'dev/freshgenerator/surface/EcotonePolicy.class',
    'dev/freshgenerator/surface/EcotoneSurfaceRule.class',
    'dev/freshgenerator/surface/EcotoneSurfaceRule$1.class',
    'dev/freshgenerator/mixin/ForestPlacementMixin.class',
    'dev/freshgenerator/village/VillageTerrainModel.class',
}
LANDSCAPE_CLASS = 'dev/freshgenerator/core/LandscapeTerrainProgram.class'
ADDED_CLASSES = {LANDSCAPE_CLASS, 'dev/freshgenerator/core/ForestComposition.class',
                 'dev/freshgenerator/core/ForestGrowth.class',
                 'dev/freshgenerator/core/ForestGrowth$Profile.class',
                 'dev/freshgenerator/core/ForestGrowth$Shape.class',
                 'dev/freshgenerator/forest/NativeTreeGrowth.class',
                 'dev/freshgenerator/core/LandscapeMaterials.class',
                 'dev/freshgenerator/core/LandscapeMaterials$Cover.class',
                 'dev/freshgenerator/village/VillageFarmSupport.class',
                 'dev/freshgenerator/mixin/VillageFarmSupportMixin.class',
                 'dev/freshgenerator/mixin/MaterialRuleContextAccessor.class'}
OPTIONAL_CLASSES = {'dev/freshgenerator/core/ForestEcology$1.class',
                    'dev/freshgenerator/core/ForestGrowth$1.class',
                    'dev/freshgenerator/surface/EcotoneSurfaceRule$2.class'}
MIXINS = ['BiomeSnowMixin', 'ForestPlacementMixin', 'CaveCarverMixin',
          'NoiseChunkVillageMixin', 'VillageTerrainMixin', 'VillageLakeMixin', 'VillageFarmSupportMixin',
          'MaterialRuleContextAccessor']
FORBIDDEN = (b'hydrolog', b'watershed', b'drainage')


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def decoded(archive, name):
    return json.loads(archive.read(name))


def custom_types(value):
    """Collect registered codec IDs without mistaking ordinary resource refs for codecs."""
    if isinstance(value, dict):
        found = {value['type']} if isinstance(value.get('type'), str) and not value['type'].startswith('minecraft:') else set()
        return found.union(*(custom_types(v) for v in value.values()))
    if isinstance(value, list):
        return set().union(*(custom_types(v) for v in value))
    return set()


def check(candidate=CANDIDATE):
    candidate = Path(candidate)
    require(hashlib.sha256(BASELINE.read_bytes()).hexdigest() == BASELINE_SHA256,
            'Published baseline package changed')
    with ZipFile(BASELINE) as old, ZipFile(candidate) as new:
        names = [n for n in new.namelist() if not n.endswith('/')]
        require(len(names) == len(set(names)), 'Duplicate JAR entries')
        old_names = {n for n in old.namelist() if not n.endswith('/')}
        names = set(names)
        meta = decoded(new, 'fabric.mod.json')
        require(meta['version'] == VERSION, 'Wrong candidate version')
        require(meta['entrypoints'] == {'main': ['dev.freshgenerator.FreshGenerator']}, 'Unexpected entrypoint')
        require(meta['mixins'] == ['freshgenerator.mixins.json'], 'Extra mixin configuration')
        require(decoded(new, 'freshgenerator.mixins.json')['mixins'] == MIXINS, 'Unexpected mixins')
        expected_mixins = decoded(old, 'freshgenerator.mixins.json')
        expected_mixins['mixins'] = MIXINS
        require(decoded(new, 'freshgenerator.mixins.json') == expected_mixins,
                'Unexpected changes outside the reviewed farm support and read-only material accessor')

        old_classes = {n for n in old_names if n.endswith('.class')}
        classes = {n for n in names if n.endswith('.class')}
        require(len(old_classes) == 37, 'Unexpected baseline runtime inventory')
        require(old_classes <= classes, 'Published runtime classes removed')
        added_classes = classes - old_classes
        require(ADDED_CLASSES <= added_classes and added_classes <= ADDED_CLASSES | OPTIONAL_CLASSES,
                f'Unreviewed new classes: {sorted(added_classes)}')
        changed_classes = {n for n in old_classes if old.read(n) != new.read(n)}
        require(changed_classes == REVIEWED_CLASSES, f'Runtime change set differs: {sorted(changed_classes ^ REVIEWED_CLASSES)}')
        for name in classes:
            require(not any(token in name.lower().encode() or token in new.read(name).lower() for token in FORBIDDEN),
                    f'Removed water subsystem reference in runtime: {name}')
        require(b'FG caves disabled' in new.read('dev/freshgenerator/mixin/CaveCarverMixin.class'),
                'Native carver cave opt-out missing')

        before = decoded(old, 'freshgenerator-worldgen-manifest.json')
        after = decoded(new, 'freshgenerator-worldgen-manifest.json')
        require(before['source_sha256'] == after['source_sha256'], 'Native source assets changed')
        original = set(before['generated_sha256'])
        generated = set(after['generated_sha256'])
        require(original <= generated, 'Baseline generated resources removed')
        require({n for n in names if n.startswith('data/')} == generated, 'Unmanifested data resources')
        added = generated - original
        require(CAVE_MARKER in added and CLIMATE_MARKER in added, 'Required opt-out/climate markers missing')
        require(all(n == CAVE_MARKER or n.endswith('.json') and n.startswith(LANDSCAPE_PREFIXES) for n in added),
                'New resources outside the landscape density/noise namespace')
        changed = {n for n in original if old.read(n) != new.read(n)}
        require(changed == CHANGED_RESOURCES, f'Resource change set differs: {sorted(changed ^ CHANGED_RESOURCES)}')
        baseline_codecs, current_codecs = set(), set()
        for name, digest in before['generated_sha256'].items():
            require(hashlib.sha256(old.read(name)).hexdigest() == digest, f'Invalid baseline manifest: {name}')
            baseline_codecs |= custom_types(decoded(old, name))
        for name, digest in after['generated_sha256'].items():
            data = new.read(name)
            require(hashlib.sha256(data).hexdigest() == digest, f'Invalid candidate manifest: {name}')
            require(not any(token in data.lower() or token in name.lower().encode() for token in FORBIDDEN),
                    f'Removed water subsystem reference: {name}')
            current_codecs |= custom_types(json.loads(data))
        require(current_codecs <= baseline_codecs, 'New custom worldgen codec')
        require(names - old_names == added | added_classes, 'Unexpected package files')
        require(old_names <= names, 'Baseline package files removed')
        mutable_metadata = {'fabric.mod.json', 'freshgenerator-worldgen-manifest.json', 'freshgenerator.mixins.json'}
        for name in old_names - CHANGED_RESOURCES - REVIEWED_CLASSES - mutable_metadata:
            require(old.read(name) == new.read(name), f'Unreviewed packaged change: {name}')

        normal = decoded(new, 'data/minecraft/tags/worldgen/world_preset/normal.json')
        require(normal['values'] == ['freshgenerator:world'], 'Expected one public world preset')
        settings, old_settings = decoded(new, SETTINGS), decoded(old, SETTINGS)
        require(settings['default_fluid'] == 'minecraft:water' and settings['sea_level'] == 63,
                'Native sea fluid or level changed')
        require({k: v for k, v in settings.items() if k != 'debug_functions'} ==
                {k: v for k, v in old_settings.items() if k != 'debug_functions'},
                'Noise settings changed outside diagnostic sampler additions')
        debug = {v['label']: v['function'] for v in settings['debug_functions']}
        require(debug.get('FG caves disabled') == ID + 'coherent/surface_only/caves_disabled', 'Cave marker not wired')
        require(debug.get('FG landscape climate') == ID + 'landscape/climate/enabled', 'Climate marker not wired')
        require(decoded(new, CAVE_MARKER) == 1 and decoded(new, CLIMATE_MARKER) == 1, 'Disabled marker values')
        require(decoded(new, PREFIX + 'coherent/cave_weight.json') == 0, 'Caves enabled')
        require(decoded(new, PREFIX + 'coherent/surface_density.json') == {
            'type': 'minecraft:squeeze', 'input': {'type': 'minecraft:mul', 'left': {
                'type': 'minecraft:sub', 'left': ID + 'coherent/height', 'right': 'minecraft:y'}, 'right': 0.08}},
            'Expected solid monotonic surface density')
        require(decoded(new, PREFIX + 'final_density.json') == {'type': 'minecraft:add',
                'left': ID + 'coherent/surface_density', 'right': {'type': 'minecraft:beardifier'}},
                'Final terrain no longer uses cave-free surface plus native structures')
        require(decoded(new, PREFIX + 'coherent/height.json') == {'type': 'minecraft:cache', 'input': {
                'type': 'minecraft:interpolated', 'cell_size_xz': 4, 'cell_size_y': 1,
                'input': ID + 'landscape/height_limited'}}, 'Expected 4-block landscape height interpolation')
        profile = decoded(new, PROFILE)
        require(profile['activeLandscapeAlgorithm'] == 2 and profile['activeHeightInterpolationBlocks'] == 4
                and profile['activeOceanDeepening'] == 0, 'Unexpected active terrain profile')
        return dict(version=VERSION, sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    baseline='0.3.6-alpha.1 / 4e50c82', baseline_sha256=BASELINE_SHA256,
                    runtime_classes=len(classes), unchanged_runtime_classes=len(old_classes - changed_classes),
                    changed_runtime_classes=sorted(changed_classes), generated_resources=len(generated),
                    added_landscape_resources=len(added - {CAVE_MARKER}),
                    changed_baseline_resources=sorted(changed), custom_water_system_present=False,
                    caves_enabled=False, public_world_presets=1, sea_level=63, height_interpolation_blocks=4)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', nargs='?', type=Path, default=CANDIDATE)
    parser.add_argument('--report', type=Path, default=Path('build/reports/village-release/landscape-validation.json'))
    args = parser.parse_args()
    result = check(args.candidate)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('FRESHGENERATOR_LANDSCAPE_PASS', json.dumps(result))
