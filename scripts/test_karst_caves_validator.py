"""Tamper tests: a rehashed manifest must not hide out-of-scope changes."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile, ZIP_DEFLATED
import validate_karst_caves as v


class KarstCavesValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not v.CANDIDATE.exists() or not v.BASELINE.exists():
            raise unittest.SkipTest('Build 0.8.2 and 0.9.0 before package tests')
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
        r = v.validate(); self.assertGreater(r['karst_resources'], 60); self.assertEqual(16, r['removed_cave_network'])

    def test_terrain_water_and_other_settings_cannot_change(self):
        for n in [v.DATA + 'coherent/height.json', v.DATA + 'coherent/surface_density.json', v.DATA + 'landscape/height_raw.json']:
            with self.subTest(resource=n): self.reject({n: self.entries[n] + b' '})

    def test_roof_guard_and_grid_cannot_be_dropped(self):
        body = json.loads(self.entries[v.DATA + 'karst/body.json']); body['cell_size_y'] = 1
        self.reject({v.DATA + 'karst/body.json': json.dumps(body).encode()})
        self.reject({v.DATA + 'karst/roof_guard.json': b'0.0'})

    def test_old_network_cannot_return(self):
        self.reject({v.DATA + 'cave_network/final_body.json': b'1.0'})


if __name__ == '__main__':
    unittest.main()
