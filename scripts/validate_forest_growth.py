"""Freeze the 0.5.2 change scope against the tested 0.5.1 package."""
import argparse, hashlib, json
from pathlib import Path
from zipfile import ZipFile
from validate_landscape import check, CANDIDATE

BASELINE_SHA = '159325e0c400fa9d8c8ffcafbc5b69bc081ec91dd381d1092f640d059557e84c'
CHANGED = {'dev/freshgenerator/forest/ForestEnvironment.class',
           'dev/freshgenerator/mixin/ForestPlacementMixin.class'}
ADDED = {'dev/freshgenerator/core/ForestGrowth.class',
         'dev/freshgenerator/core/ForestGrowth$Profile.class',
         'dev/freshgenerator/core/ForestGrowth$Shape.class',
         'dev/freshgenerator/forest/NativeTreeGrowth.class'}
OPTIONAL = {'dev/freshgenerator/core/ForestGrowth$1.class'}

def validate(candidate, baseline):
    landscape = check(candidate)
    assert hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA, 'Wrong immutable baseline'
    with ZipFile(baseline) as old, ZipFile(candidate) as new:
        before = {n for n in old.namelist() if not n.endswith('/')}
        after = {n for n in new.namelist() if not n.endswith('/')}
        assert before <= after, 'Existing package entries removed'
        assert ADDED <= after-before <= ADDED|OPTIONAL, f'Unexpected additions: {after-before}'
        differences = {n for n in before if old.read(n) != new.read(n)}
        assert differences == CHANGED|{'fabric.mod.json'}, f'Unexpected changes: {differences}'
        old_meta, new_meta = json.loads(old.read('fabric.mod.json')), json.loads(new.read('fabric.mod.json'))
        old_meta['version'] = new_meta['version']
        assert old_meta == new_meta, 'Metadata changed beyond version'
        data = [n for n in before if n.startswith('data/')]
        return dict(version=landscape['version'], sha256=landscape['sha256'], baseline_sha256=BASELINE_SHA,
                    identical_worldgen_resources=len(data), unchanged_mixins=True,
                    changed_runtime_classes=sorted(CHANGED), added_classes=sorted(after-before),
                    custom_water_system_present=False, caves_enabled=False)

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--candidate', default=CANDIDATE, type=Path)
    parser.add_argument('--report', default=Path('build/reports/village-release/forest-growth-validation.json'), type=Path)
    args=parser.parse_args();result=validate(args.candidate,args.baseline)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('FOREST_GROWTH_PACKAGE_PASS',json.dumps(result))
