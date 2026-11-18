"""Adversarial package checks: rebuilding the manifest must not widen scope."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZIP_DEFLATED

import validate_world_integrity as validator


class WorldIntegrityValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.candidate = Path(os.environ.get('FG_WORLD_INTEGRITY_CANDIDATE',validator.CANDIDATE))
        if not cls.candidate.is_file():
            raise unittest.SkipTest('Build the 0.7.2 candidate before package mutation tests')
        with ZipFile(cls.candidate) as archive:
            cls.accepted = {n:archive.read(n) for n in archive.namelist() if not n.endswith('/')}
        validator.validate(cls.candidate)

    def rejected(self,changes=None,remove=()):
        entries = dict(self.accepted)
        entries.update(changes or {})
        for name in remove:
            entries.pop(name)
        # Treat the embedded manifest as attacker-controlled and keep it valid.
        # Rejection must come from the independent fixture/graph contract.
        manifest = json.loads(entries[validator.MANIFEST])
        data = {n:hashlib.sha256(raw).hexdigest() for n,raw in entries.items() if n.startswith('data/')}
        manifest['generated_sha256'] = data
        manifest['generated_entries'] = len(data)
        entries[validator.MANIFEST] = json.dumps(manifest).encode()
        with tempfile.TemporaryDirectory() as temporary:
            candidate = Path(temporary)/'mutated.jar'
            with ZipFile(candidate,'w',ZIP_DEFLATED) as archive:
                for name,raw in entries.items():
                    archive.writestr(name,raw)
            with self.assertRaises(AssertionError):
                validator.validate(candidate)

    def json_change(self,name,change):
        value = json.loads(self.accepted[name])
        replacement = change(copy.deepcopy(value))
        return {name:json.dumps(replacement).encode()}

    def test_accepted_candidate_and_signed_baseline_fixture(self):
        result = validator.validate(self.candidate)
        self.assertEqual(59,result['unchanged_runtime_classes'])
        self.assertEqual(423,result['generated_resources'])
        self.assertEqual(416,result['identical_worldgen_resources'])
        self.assertTrue(result['access_y_invariant'])
        self.assertTrue(result['access_reaches_exact_surface'])

    def test_runtime_class_cannot_change(self):
        name = next(n for n in self.accepted if n.endswith('.class'))
        self.rejected({name:self.accepted[name]+b'\0'})

    def test_unrelated_height_biome_or_mixin_cannot_change(self):
        for name in (validator.PREFIX+'coherent/height.json',validator.SETTINGS,'freshgenerator.mixins.json'):
            with self.subTest(resource=name):
                self.rejected({name:self.accepted[name]+b' '})

    def test_access_cannot_widen_beyond_reviewed_link_subset(self):
        name = validator.PREFIX+'cave_network/access_footprint.json'
        def widen(value):
            value['input']['right'] = .20
            return value
        self.rejected(self.json_change(name,widen))

    def test_access_cannot_turn_into_a_closed_three_dimensional_noise_tube(self):
        name = validator.PREFIX+'cave_network/access_footprint.json'
        def change_y(value):
            value['input']['left']['left']['input']['y_scale'] = 1.25
            return value
        self.rejected(self.json_change(name,change_y))

    def test_boolean_cannot_impersonate_a_zero_noise_scale(self):
        name = validator.PREFIX+'cave_network/access_footprint.json'
        def boolean_y(value):
            value['input']['left']['left']['input']['y_scale'] = False
            return value
        self.rejected(self.json_change(name,boolean_y))

    def test_access_cannot_use_unrelated_noise_or_mismatched_interpolation(self):
        name = validator.PREFIX+'cave_network/access_footprint.json'
        def change_noise(value):
            value['input']['left']['right']['input']['noise'] = 'minecraft:cave_cheese'
            return value
        self.rejected(self.json_change(name,change_noise))
        self.rejected(self.json_change(name,lambda value:dict(value,cell_size_xz=8)))

    def test_access_gate_cannot_be_removed_or_moved_to_lowlands(self):
        name = validator.PREFIX+'cave_network/access_body.json'
        self.rejected({name:json.dumps(validator.N+'access_footprint').encode()})
        name = validator.PREFIX+'cave_network/mountain_gate.json'
        self.rejected({name:b'1'})

    def test_broad_chambers_cannot_reuse_open_surface_ceiling(self):
        name = validator.PREFIX+'cave_network/deep_ceiling.json'
        old = validator.baseline_fixture()['cave_nodes']['ceiling']
        self.rejected({name:json.dumps(old).encode()})
        name = validator.PREFIX+'cave_network/depth_limit.json'
        def remove_roof(value):
            value['left']['right']['right'] = validator.N+'ceiling'
            return value
        self.rejected(self.json_change(name,remove_roof))

    def test_access_must_reach_its_y_zero_junction(self):
        name = validator.PREFIX+'cave_network/access_limit.json'
        def shallow(value):
            value['right']['right']['left'] = 80
            return value
        self.rejected(self.json_change(name,shallow))

    def test_access_cannot_leave_a_fractional_gated_cap_above_a_shaft(self):
        name = validator.PREFIX+'cave_network/ceiling.json'
        old = validator.baseline_fixture()['cave_nodes']['ceiling']
        self.rejected({name:json.dumps(old).encode()})
        self.rejected({name:json.dumps({'type':'minecraft:cache','input':validator.binary(
            'sub',validator.ID+'coherent/height',.25)}).encode()})

    def test_final_density_cannot_omit_access_or_native_structure_fit(self):
        name = validator.PREFIX+'cave_network/final_body.json'
        self.rejected({name:json.dumps(validator.ID+'coherent/surface_density').encode()})
        name = validator.PREFIX+'final_density.json'
        self.rejected({name:json.dumps(validator.N+'final_body').encode()})

    def test_native_default_and_vanilla_saved_world_settings_are_preserved(self):
        name = validator.DEFAULT_PRESET
        def vanilla(value):
            value['dimensions']['minecraft:overworld']['generator']['settings'] = 'minecraft:overworld'
            return value
        self.rejected(self.json_change(name,vanilla))
        self.rejected({'data/minecraft/worldgen/noise_settings/overworld.json':self.accepted[validator.SETTINGS]})

    def test_custom_water_resources_and_deleted_resources_are_rejected(self):
        self.rejected({'data/freshgenerator/worldgen/density_function/hydrology/test.json':b'0'})
        self.rejected(remove=(validator.PREFIX+'cave_network/enabled.json',))

    def test_version_is_the_only_permitted_mod_metadata_change(self):
        self.rejected(self.json_change('fabric.mod.json',lambda value:dict(value,environment='client')))

    def test_fixture_itself_is_pinned_independently(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Path(temporary)/'fake-baseline.json'
            fixture.write_bytes(validator.BASELINE_FIXTURE.read_bytes()+b' ')
            with patch.object(validator,'BASELINE_FIXTURE',fixture):
                with self.assertRaisesRegex(AssertionError,'Frozen baseline fixture changed'):
                    validator.validate(self.candidate)


if __name__ == '__main__':
    unittest.main()
