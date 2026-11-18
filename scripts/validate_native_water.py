"""Compare the restored generator with the published pre-water runtime."""
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

VERSION = '0.4.1-alpha.1'
CANDIDATE = Path(f'build/libs/FreshGenerator-Fabric-26.3-{VERSION}.jar')

def check(candidate=CANDIDATE):
    with ZipFile('dist/FreshGenerator-Fabric-26.3-0.3.6-alpha.1.jar') as old, ZipFile(candidate) as new:
        meta = json.loads(new.read('fabric.mod.json'))
        assert meta['version'] == VERSION
        assert meta['entrypoints']['main'] == ['dev.freshgenerator.FreshGenerator']
        assert json.loads(new.read('freshgenerator.mixins.json'))['mixins'] == [
            'BiomeSnowMixin', 'ForestPlacementMixin', 'CaveCarverMixin',
            'NoiseChunkVillageMixin', 'VillageTerrainMixin', 'VillageLakeMixin']
        classes = {n for n in new.namelist() if n.endswith('.class')}
        assert classes == {n for n in old.namelist() if n.endswith('.class')}
        cave = 'dev/freshgenerator/mixin/CaveCarverMixin.class'
        assert new.read(cave) != old.read(cave)
        for name in classes - {cave}:
            assert old.read(name) == new.read(name), name
        before = json.loads(old.read('freshgenerator-worldgen-manifest.json'))
        after = json.loads(new.read('freshgenerator-worldgen-manifest.json'))
        assert before['source_sha256'] == after['source_sha256']
        prefix = 'data/freshgenerator/worldgen/density_function/highlands_v2/'
        marker = prefix + 'coherent/surface_only/caves_disabled.json'
        assert set(after['generated_sha256']) == set(before['generated_sha256']) | {marker}
        changed = {p for p in before['generated_sha256'] if old.read(p) != new.read(p)}
        expected = {prefix + p for p in ['coherent/surface_density.json', 'coherent/cave_weight.json', 'final_density.json']}
        expected.add('data/freshgenerator/worldgen/noise_settings/highlands_v2.json')
        assert changed == expected, changed ^ expected
        assert json.loads(new.read(marker)) == 1
        for name, sha in after['generated_sha256'].items():
            data = new.read(name)
            assert hashlib.sha256(data).hexdigest() == sha, name
            assert not any(s in data.lower() for s in [b'freshgenerator:drainage', b'freshgenerator:watershed', b'coherent/hydrology/']), name
        assert json.loads(new.read('data/minecraft/tags/worldgen/world_preset/normal.json'))['values'] == ['freshgenerator:world']
        return dict(version=VERSION, sha256=hashlib.sha256(Path(candidate).read_bytes()).hexdigest(),
                    baseline_commit='4e50c82', runtime_classes=len(classes), unchanged_runtime_classes=len(classes)-1,
                    generated_resources=len(after['generated_sha256']), custom_water_system_present=False,
                    caves_enabled=False, changed_baseline_resources=sorted(changed))

if __name__ == '__main__':
    print('FRESHGENERATOR_NATIVE_WATER_PASS', json.dumps(check()))
