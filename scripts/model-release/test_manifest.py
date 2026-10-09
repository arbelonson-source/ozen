import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import manifest


def write(root, files):
    for path, data in files.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def quietly(action, *args):
    with contextlib.redirect_stdout(io.StringIO()):
        action(*args)


class Pack(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.folder, self.assets, self.back = self.root / "model", self.root / "assets", self.root / "back"

    def test_unpack_rebuilds_the_folder_byte_for_byte(self):
        files = {"config.json": b"{}", "AudioEncoder.mlpackage/Data/com.apple.CoreML/weights/weight.bin": b"\x00\x01" * 50}
        write(self.folder, files)
        quietly(manifest.pack, self.folder, self.assets)
        quietly(manifest.unpack, self.assets, self.back)
        self.assertEqual({p: (self.back / p).read_bytes() for p in files}, files)

    def test_the_manifest_passes_the_phones_checks(self):
        write(self.folder, {"a.json": b"1", "b/c.bin": b"22", ".DS_Store": b"x", "manifest.json": b"old"})
        quietly(manifest.pack, self.folder, self.assets)
        files = json.loads((self.assets / "manifest.json").read_text())["files"]
        self.assertEqual([f["path"] for f in files], ["a.json", "b/c.bin"])
        self.assertEqual([f["asset"] for f in files], ["a.json", "b__c.bin"])
        for f in files:
            self.assertGreater(f["size"], 0)
            self.assertRegex(f["sha256"], r"^[0-9a-f]{64}$")
            self.assertNotIn("/", f["asset"])

    def test_an_empty_file_is_refused_because_the_phone_refuses_its_manifest(self):
        write(self.folder, {"a.json": b"1", "empty.bin": b""})
        with self.assertRaises(SystemExit) as refused:
            quietly(manifest.pack, self.folder, self.assets)
        self.assertIn("empty.bin", str(refused.exception))

    def test_two_files_that_flatten_to_one_asset_are_refused(self):
        write(self.folder, {"a/b__c": b"1", "a__b/c": b"2"})
        with self.assertRaises(SystemExit) as refused:
            quietly(manifest.pack, self.folder, self.assets)
        self.assertIn("a__b__c", str(refused.exception))

    def test_unpack_refuses_an_asset_that_changed(self):
        write(self.folder, {"a.bin": b"12345"})
        quietly(manifest.pack, self.folder, self.assets)
        (self.assets / "a.bin").write_bytes(b"12346")
        with self.assertRaises(SystemExit) as refused:
            quietly(manifest.unpack, self.assets, self.back)
        self.assertIn("a.bin", str(refused.exception))

    def test_unpack_refuses_an_asset_of_the_wrong_size(self):
        write(self.folder, {"a.bin": b"12345"})
        quietly(manifest.pack, self.folder, self.assets)
        listed = json.loads((self.assets / "manifest.json").read_text())
        listed["files"][0]["size"] = 6
        (self.assets / "manifest.json").write_text(json.dumps(listed))
        with self.assertRaises(SystemExit):
            quietly(manifest.unpack, self.assets, self.back)


class Checksum(unittest.TestCase):
    def test_a_file_longer_than_one_read_is_hashed_whole(self):
        data = b"a" * ((1 << 20) + 1)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "big"
            path.write_bytes(data)
            self.assertEqual(manifest.sha256(path), hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    unittest.main()
