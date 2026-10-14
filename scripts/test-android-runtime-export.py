#!/usr/bin/env python3
"""Exercise real Gradle metadata aliases and the pinned Maven byte validator."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import urllib.error
import zipfile

import importlib.util

ROOT = Path(__file__).resolve().parents[1]


def archive(name, body):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as value:
        value.writestr(name, body)
    return output.getvalue()


class AndroidRuntimeExportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="xszc-gradle-export-test-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name).resolve(strict=True)
        spec = importlib.util.spec_from_file_location(
            "xszc_export_test_inventory", ROOT / "scripts" / "release-inventory.py")
        cls.common = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.common)
        cls.environment = cls.common.clean_environment()
        tools = cls.common.checkout_tools(cls.root / "tools", cls.environment)
        cls.producer = cls.common.load_tool("gradle_sbom", tools)
        cls.repository = "https://runtime.example.invalid/maven/"
        cls.coordinate = "org.xszc.fixture:runtime-kmp:1.0.0"
        cls.originals = {
            "runtime-kmp-1.0.0.aar": archive("classes.jar", archive("Runtime.class", b"fixture class")),
            "runtime-kmp-1.0.0-features.jar": archive("Feature.class", b"fixture feature\r\n\x1a"),
        }
        cls.sources = {cls.repository + "org/xszc/fixture/runtime-kmp/1.0.0/" + name: body
                       for name, body in cls.originals.items()}
        published = cls.root / "repository" / "org/xszc/fixture/runtime-kmp/1.0.0"
        published.mkdir(parents=True)
        files = []
        for local, remote in [("runtime.aar", "runtime-kmp-1.0.0.aar"),
                              ("feature.jar", "runtime-kmp-1.0.0-features.jar")]:
            body = cls.originals[remote]
            (published / remote).write_bytes(body)
            files.append({"name": local, "url": remote, "size": len(body),
                          "sha256": hashlib.sha256(body).hexdigest()})
        metadata = {"formatVersion": "1.1",
                    "component": {"group": "org.xszc.fixture", "module": "runtime-kmp", "version": "1.0.0"},
                    "variants": [{"name": "androidRuntimeElements-published",
                                  "attributes": {"org.gradle.usage": "java-runtime"}, "files": files}]}
        (published / "runtime-kmp-1.0.0.module").write_text(json.dumps(metadata))
        (published / "runtime-kmp-1.0.0.pom").write_text(
            "<project><!-- do_not_remove: published-with-gradle-metadata -->"
            "<modelVersion>4.0.0</modelVersion><groupId>org.xszc.fixture</groupId>"
            "<artifactId>runtime-kmp</artifactId><version>1.0.0</version><packaging>aar</packaging></project>")
        (cls.root / "settings.gradle").write_text(
            "dependencyResolutionManagement { repositories { maven { url = uri('repository') } } }\n"
            "rootProject.name='android-runtime-source-fixture'\ninclude(':app')\n")
        app = cls.root / "app"
        app.mkdir()
        (app / "build.gradle").write_text(
            "configurations { releaseRuntimeClasspath { canBeResolved=true; canBeConsumed=false; "
            "attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, 'java-runtime')) } } }\n"
            "dependencies { releaseRuntimeClasspath 'org.xszc.fixture:runtime-kmp:1.0.0' }\n")
        gradle = shutil.which("gradle")
        if gradle is None:
            raise RuntimeError("The real Gradle runtime is required for Android source inventory tests")
        result = subprocess.run(
            [gradle, "--no-daemon", "--offline", "-p", str(cls.root), "-I",
             str(ROOT / "scripts" / "export-android-runtime.gradle"),
             "-PxssiRuntimeExport=" + str(cls.root / "export.json"), ":app:exportXssiRuntimeInventory"],
            env=cls.environment, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=180)
        if result.returncode:
            raise RuntimeError("Real Gradle resolution failed:\n" + result.stdout + result.stderr)
        cls.export = json.loads((cls.root / "export.json").read_text())
        cls.component = cls.export["components"][0]

    def download(self, url, maximum):
        if url not in self.sources:
            raise urllib.error.HTTPError(url, 404, "missing fixture source", {}, None)
        value = self.sources[url]
        self.assertLessEqual(len(value), maximum)
        return value

    def test_real_metadata_aliases_and_classifier_bind_exact_published_bytes(self):
        self.assertEqual(self.export["configuration"], "releaseRuntimeClasspath")
        self.assertEqual([item["id"] for item in self.export["components"]], [self.coordinate])
        self.assertEqual(self.export["root"]["dependencies"], [self.coordinate])
        self.assertEqual({item["filename"] for item in self.component["artifacts"]}, set(self.originals))
        self.assertEqual({Path(item["path"]).name for item in self.component["artifacts"]},
                         {"runtime.aar", "feature.jar"})
        for artifact in self.component["artifacts"]:
            with self.subTest(filename=artifact["filename"]):
                original = self.originals[artifact["filename"]]
                self.assertEqual(Path(artifact["path"]).read_bytes(), original)
                self.assertEqual(artifact["sha256"], hashlib.sha256(original).hexdigest())
                raw, url, repository = self.producer.upstream_artifact(
                    self.component, artifact, [self.repository], self.download)
                self.assertEqual(raw, original)
                self.assertEqual(url, self.repository + "org/xszc/fixture/runtime-kmp/1.0.0/" + artifact["filename"])
                self.assertEqual(repository, self.repository)

    def test_cache_filename_unknown_source_and_wrong_upstream_bytes_remain_rejected(self):
        for original in self.component["artifacts"]:
            with self.subTest(filename=original["filename"]):
                for filename in [Path(original["path"]).name, "absent-1.0.0.aar"]:
                    with self.assertRaisesRegex(ValueError, "no declared Maven repository"):
                        self.producer.upstream_artifact(
                            self.component, {**original, "filename": filename}, [self.repository], self.download)
                with self.assertRaisesRegex(ValueError, "no declared Maven repository"):
                    self.producer.upstream_artifact(self.component, original, [self.repository],
                                                    lambda url, maximum: b"different upstream bytes")
                with self.assertRaisesRegex(ValueError, "changed before inventory"):
                    self.producer.upstream_artifact(self.component, {**original, "sha256": "0" * 64},
                                                    [self.repository], self.download)


if __name__ == "__main__":
    unittest.main()
