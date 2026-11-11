import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci_package import package, release_version


class ReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        self.source = {"versionCode": 7, "versionName": "0.3.1-dev"}

    def test_stable_and_preview_match_source_without_dev_suffix(self):
        self.assertEqual({"version": "0.3.1", "code": 7, "prerelease": "false"}, release_version("v0.3.1", self.source))
        self.assertEqual("true", release_version("v0.3.1-rc.1", self.source)["prerelease"])

    def test_unsafe_tags_and_mismatched_source_are_rejected(self):
        for tag in ["main", "v0.3.1-dev", "v0.3.1+build", "v0.3.2", "v0.3.1\nhello", "v0.3.1;id", "v0.3.1-rc.0"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_version(tag, self.source)

    def test_release_code_must_increase_including_rc_to_stable(self):
        for code in [7, 8]:
            with self.subTest(previous=code), self.assertRaises(ValueError):
                release_version("v0.3.1", self.source, {"versionCode": code})
        self.assertEqual(7, release_version("v0.3.1", self.source, {"versionCode": 6})["code"])

    def test_invalid_android_version_codes_are_rejected(self):
        for code in [0, -1, 2_100_000_001, "7", True]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                release_version("v0.3.1", dict(self.source, versionCode=code))

    def fixture(self, root, **changes):
        folder = root / "app/build/outputs/apk/debug"
        folder.mkdir(parents=True)
        (folder / "app-debug.apk").write_bytes(b"packaging-only-test-fixture")
        metadata = {"applicationId": "dev.githubstore", "elements": [dict(self.source, outputFile="app-debug.apk")]}
        metadata.update(changes)
        (folder / "output-metadata.json").write_text(json.dumps(metadata))
        return metadata, folder

    def test_debug_package_contains_correct_identity_and_checksums(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.fixture(root)
            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch("ci_package.subprocess.check_output", return_value="a" * 40 + "\n"), contextlib.redirect_stdout(io.StringIO()):
                    package("debug", Path("artifacts"))
                manifest = json.loads(Path("artifacts/build-metadata.json").read_text())
                self.assertEqual(7, manifest["versionCode"])
                self.assertEqual("a" * 40, manifest["commit"])
                for line in Path("artifacts/SHA256SUMS").read_text().splitlines():
                    digest, name = line.split("  ")
                    self.assertEqual(digest, hashlib.sha256((Path("artifacts") / name).read_bytes()).hexdigest())
            finally:
                os.chdir(previous)

    def test_other_apps_and_escaping_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); metadata, folder = self.fixture(root)
            previous = Path.cwd()
            try:
                os.chdir(root)
                metadata["elements"][0]["outputFile"] = "../../another.apk"
                (folder / "output-metadata.json").write_text(json.dumps(metadata))
                with self.assertRaises(ValueError):
                    package("debug", Path("artifacts"))
                metadata["applicationId"] = "dev.orbit.fixture"
                (folder / "output-metadata.json").write_text(json.dumps(metadata))
                with self.assertRaises(ValueError):
                    package("debug", Path("artifacts"))
            finally:
                os.chdir(previous)
