"""Tamper tests: a rehashed manifest must not hide out-of-scope changes."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile, ZIP_DEFLATED
import validate_terrain_realism as v


class TerrainRealismValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not v.CANDIDATE.exists() or not v.BASELINE.exists():
            raise unittest.SkipTest('Build 0.8.1 and 0.8.2 before package tests')
        with ZipFile(v.CANDIDATE) as z:
            cls.entries = {n: z.read(n) for n in z.namelist() if not n.endswith('/')}

    def reject(self, changes=None, remove=()):
        entries = dict(self.entries); entries.update(changes or {})
        for n in remove: entries.pop(n)
        m = json.loads(entries[v.MANIFEST])
        m['generated_sha256'] = {n: hashlib.sha256(b).hexdigest() for n, b in entries.items() if n.startswith('data/')}
        m['generated_entries'] = len(m['generated_sha256']); entries[v.MANIFEST] = json.dumps(m).encode()
        with tempfile.TemporaryDirectory() as folder:
            f = Path(folder) / 'tampered.jar'
            with ZipFile(f, 'w', ZIP_DEFLATED) as z:
                for n, b in entries.items(): z.writestr(n, b)
            with self.assertRaises(AssertionError): v.validate(f)

    def test_exact_candidate(self):
        r = v.validate()
        self.assertEqual(441, r['generated_resources'])
        self.assertGreater(r['cave_resources_identical'], 10)

    def test_caves_water_and_settings_cannot_change(self):
        for n in [v.DATA + 'final_density.json', v.DATA + 'cave_network/final_body.json',
                  'data/freshgenerator/worldgen/noise_settings/highlands_v2.json']:
            with self.subTest(resource=n): self.reject({n: self.entries[n] + b' '})

    def test_coastal_guard_cannot_be_dropped(self):
        n = v.DATA + 'landscape/height_raw.json'
        self.reject({n: self.entries[v.DATA + 'landscape/height_unguarded.json']})

    def test_unreviewed_runtime_is_rejected(self):
        self.reject({'dev/freshgenerator/Unexpected.class': b'\xca\xfe'})
        self.reject(remove=['dev/freshgenerator/core/CoastalRelief.class'])


if __name__ == '__main__':
    unittest.main()
