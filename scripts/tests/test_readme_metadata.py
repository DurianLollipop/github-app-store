import argparse
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import catalog
from readme_metadata import extract, image_url, merge_metadata


class ReadmeExtractionTest(unittest.TestCase):
    def extract(self, text, path="docs/README.md"):
        return extract(text, "owner/project", "main", path, "Example")

    def test_html_logo_and_reference_screenshot(self):
        result = self.extract('''# Example
<img alt="App logo" src="/assets/logo.svg">

A useful open source app for sharing files.

## Screenshots
![Home screen][HOME]

[home]: ../images/home.png
''')
        self.assertEqual(result["iconUrl"], "https://raw.githubusercontent.com/owner/project/main/assets/logo.svg")
        self.assertEqual(result["screenshots"], ["https://raw.githubusercontent.com/owner/project/main/images/home.png"])
        self.assertEqual(result["tagline"], "A useful open source app for sharing files.")
        self.assertEqual(result["mediaSource"], result["readmeUrl"])

    def test_badges_stores_and_sponsor_logos_are_not_app_art(self):
        result = self.extract('''# Example
![logo](https://img.shields.io/badge/logo-blue)
![logo](https://github.com/owner/project/actions/workflows/test/badge.svg)
<img alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.svg">

A useful open source app for sharing files.

## Sponsors
![Sponsor logo](https://example.org/company-logo.svg)
''')
        self.assertNotIn("iconUrl", result)
        self.assertNotIn("screenshots", result)

    def test_fences_comments_scripts_and_navigation_are_excluded(self):
        result = self.extract('''# Example
[English](README.md) • [中文](README_ZH.md)

<!-- ![logo](https://example.org/wrong.png) -->
```markdown
![Logo](https://example.org/code.png)
Bad code is not a description of this app.
```
<script>Bad injected text should never appear.</script>

A useful open source app for sharing files.

## Building
Build instructions should not be the app description.
''')
        self.assertEqual(result["description"], "A useful open source app for sharing files.")
        self.assertNotIn("iconUrl", result)

    def test_chinese_features_and_html_preview(self):
        result = self.extract('''# Example

一个开源的 Material Design 文件管理器，适用于 Android 5.0+。

## 预览
<p><img src="../shots/1.png"><img src="../shots/2.png"></p>

## 特性
- 开源：轻量、简洁并且安全。
- NAS 支持：FTP、SFTP。
''')
        self.assertIn("• NAS 支持：FTP、SFTP。", result["description"])
        self.assertEqual(len(result["screenshots"]), 2)

    def test_plain_features_heading(self):
        result = self.extract('''# Example
Simple web browser optimized to use with TV remote

Features:
- working with TV remote
- tabs and bookmarks support
''')
        self.assertIn("• tabs and bookmarks support", result["description"])

    def test_html_title_subtitle_and_description_section(self):
        result = self.extract('''<h2>Example</h2>
<h4>A libre lightweight streaming front-end for Android.</h4>

## Description
This application works without a proprietary account.
''')
        self.assertEqual(result["tagline"], "A libre lightweight streaming front-end for Android.")
        self.assertIn("without a proprietary account", result["description"])

    def test_project_title_does_not_have_to_equal_catalog_name(self):
        result = self.extract('''<h1>Example for Android TV</h1>
<h3>Part of the Example Project</h3>

Example is an open source client for Android TV devices.
''')
        self.assertTrue(result["description"].startswith("Example is"))

    def test_nested_url_parentheses_and_encoded_spaces(self):
        result = self.extract('''# Example
A useful open source app for sharing files.

## Screenshots
![Screenshot](<../shots/home (1).png> "Home")
![Screenshot](../shots/home(2).png)
''')
        self.assertEqual(result["screenshots"], [
            "https://raw.githubusercontent.com/owner/project/main/shots/home%20%281%29.png",
            "https://raw.githubusercontent.com/owner/project/main/shots/home%282%29.png"])

    def test_safe_relative_root_and_github_blob_urls(self):
        self.assertEqual(image_url("../art/logo.svg", "a/b", "main", "docs/README.md"),
                         "https://raw.githubusercontent.com/a/b/main/art/logo.svg")
        self.assertEqual(image_url("https://github.com/a/b/blob/main/art/logo.svg?raw=true", "a/b", "main", "README.md"),
                         "https://raw.githubusercontent.com/a/b/main/art/logo.svg?raw=true")

    def test_unsafe_images_are_rejected(self):
        for url in ("javascript:alert(1)", "data:image/png;base64,aaa", "http://example.org/logo.png",
                    "https://localhost/logo.png", "https://127.0.0.1/logo.png", "https://192.168.1.2/logo.png",
                    "https://example.org/%0alogo.png", "https://u:pass@example.org/logo.png", "../../outside.png"):
            with self.subTest(url=url):
                self.assertEqual(image_url(url, "a/b", "main", "README.md"), "")

    def test_limits_and_deduplicates(self):
        result = self.extract("# Example\n\n" + "中文介绍" * 500 + "\n\n## Screenshots\n" +
                              "\n".join(f"![Screenshot](https://example.org/{i}.png)" for i in range(12)) +
                              "\n![Screenshot](https://example.org/0.png)")
        self.assertLessEqual(len(result["description"]), 1200)
        self.assertLessEqual(len(result["tagline"]), 90)
        self.assertEqual(len(result["screenshots"]), 8)

    def test_empty_readme_does_not_create_fake_metadata(self):
        self.assertEqual(set(self.extract("# Example")), {"readmeUrl"})


class MetadataPipelineTest(unittest.TestCase):
    def setUp(self):
        self.app = {"id": "example", "name": "Example", "repository": "a/b", "packageName": "org.example.app",
                    "tagline": "Fallback", "description": "Fallback", "screenshots": ["https://example.org/old.png"],
                    "mediaSource": "https://example.org/blog", "checkedAt": 123, "certificateSha256": ["a" * 64],
                    "readme": {"ref": "main", "path": "README.md"}}

    def test_merge_preserves_trust_fields_and_missing_media(self):
        before = copy.deepcopy(self.app)
        app = merge_metadata(self.app, {"repository": "a/b", "content": {
            "description": "From README", "certificateSha256": [], "checkedAt": 999, "packageName": "bad.app", "screenshots": []}})
        self.assertEqual(app["description"], "From README")
        for key in ("checkedAt", "certificateSha256", "packageName", "screenshots", "mediaSource"):
            self.assertEqual(app[key], before[key])
        self.assertNotIn("readme", app)

    def test_repository_change_and_explicit_overrides(self):
        self.app["metadataOverrides"] = {"description": "Reviewed correction"}
        app = merge_metadata(self.app, {"repository": "different/repo", "content": {"iconUrl": "https://example.org/foreign.png"}})
        self.assertNotIn("iconUrl", app)
        self.assertEqual(app["description"], "Reviewed correction")
        with self.assertRaises(ValueError):
            merge_metadata({"repository": "a/b", "metadataOverrides": {"certificateSha256": []}}, None)

    def test_failed_sync_preserves_last_good_record(self):
        with tempfile.TemporaryDirectory() as directory:
            sources, metadata = Path(directory) / "sources.json", Path(directory) / "metadata.json"
            catalog.write(sources, {"schemaVersion": 1, "apps": [self.app]})
            old = {"schemaVersion": 1, "apps": {"example": {"repository": "a/b", "content": {"description": "Good content"}}}}
            catalog.write(metadata, old)
            with patch.object(catalog, "SOURCES", sources), patch.object(catalog, "METADATA", metadata), \
                 patch.object(catalog, "fetch_readme", side_effect=RuntimeError("HTTP 429")):
                with self.assertRaises(ValueError):
                    catalog.metadata(argparse.Namespace(app="example"))
            self.assertEqual(catalog.read(metadata), old)

    def test_sync_records_content_origin_and_does_not_touch_review(self):
        with tempfile.TemporaryDirectory() as directory:
            sources, metadata = Path(directory) / "sources.json", Path(directory) / "metadata.json"
            catalog.write(sources, {"schemaVersion": 1, "apps": [self.app]})
            content = b"# Example\n\nA useful open source app for sharing files."
            with patch.object(catalog, "SOURCES", sources), patch.object(catalog, "METADATA", metadata), \
                 patch.object(catalog, "fetch_readme", return_value=(content, "main", "README.md")):
                catalog.metadata(argparse.Namespace(app="example"))
            record = catalog.read(metadata)["apps"]["example"]
            self.assertEqual(len(record["contentSha256"]), 64)
            self.assertGreater(record["fetchedAt"], 0)
            self.assertEqual(record["content"]["readmeUrl"], "https://github.com/a/b/blob/main/README.md")
            self.assertNotIn("checkedAt", record["content"])
            self.assertEqual(catalog.read(sources)["apps"][0]["checkedAt"], 123)


if __name__ == "__main__":
    unittest.main()
