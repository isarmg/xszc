#!/usr/bin/env python3
"""Exercise IPA layout, executable permissions and rejection before publication."""

import importlib.util
from pathlib import Path
import plistlib
import stat
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("ipa", Path(__file__).with_name("package-ios-ipa.py"))
ipa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ipa)


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.app = Path(self.root.name) / "build" / "Xszc.app"
        self.app.mkdir(parents=True)
        self.output = Path(self.root.name) / "dist" / "xszc-ios-0.4.1-unsigned.ipa"
        self.info = {
            "CFBundleIdentifier": "org.sarmg.xszc",
            "CFBundleShortVersionString": "0.4.1",
            "CFBundleSupportedPlatforms": ["iPhoneOS"],
            "CFBundleExecutable": "Xszc",
            "MinimumOSVersion": "27.0",
            "DTSDKName": "iphoneos27.0",
        }
        self.write_info()
        (self.app / "Xszc").write_bytes(b"device executable fixture")
        (self.app / "Xszc").chmod(0o755)

    def write_info(self):
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info, fmt=plistlib.FMT_BINARY))

    def test_standard_payload_preserves_binary_resources_and_links(self):
        (self.app / "照片.png").write_bytes(b"image fixture")
        (self.app / "resource-link").symlink_to("照片.png")
        ipa.package(self.app, self.output, "0.4.1")
        with zipfile.ZipFile(self.output) as archive:
            self.assertIsNone(archive.testzip())
            self.assertTrue(all(name.startswith("Payload/Xszc.app") for name in archive.namelist()))
            self.assertEqual(archive.read("Payload/Xszc.app/照片.png"), b"image fixture")
            binary = archive.getinfo("Payload/Xszc.app/Xszc")
            self.assertEqual((binary.external_attr >> 16) & 0o777, 0o755)
            link = archive.getinfo("Payload/Xszc.app/resource-link")
            self.assertTrue(stat.S_ISLNK(link.external_attr >> 16))
            self.assertEqual(archive.read(link).decode(), "照片.png")

    def test_simulator_and_wrong_release_are_rejected(self):
        for field, value in [("CFBundleSupportedPlatforms", ["iPhoneSimulator"]),
                             ("CFBundleShortVersionString", "0.3.0"),
                             ("CFBundleIdentifier", "wrong.application")]:
            with self.subTest(field=field):
                original = self.info[field]
                self.info[field] = value
                self.write_info()
                with self.assertRaises(ValueError):
                    ipa.package(self.app, self.output, "0.4.1")
                self.assertFalse(self.output.exists())
                self.info[field] = original

    def test_wrong_or_missing_deployment_target_and_sdk_are_rejected(self):
        for field, value in [("MinimumOSVersion", "17.0"),
                             ("MinimumOSVersion", "26.0"),
                             ("MinimumOSVersion", None),
                             ("DTSDKName", "iphoneos25.0"),
                             ("DTSDKName", "iphonesimulator27.0"),
                             ("DTSDKName", "iphoneos27.1"),
                             ("DTSDKName", None)]:
            with self.subTest(field=field, value=value):
                original = self.info.pop(field)
                if value is not None:
                    self.info[field] = value
                self.write_info()
                with self.assertRaises(ValueError):
                    ipa.package(self.app, self.output, "0.4.1")
                self.assertFalse(self.output.exists())
                self.info[field] = original

    def test_missing_binary_and_external_links_are_rejected(self):
        (self.app / "Xszc").unlink()
        with self.assertRaises(ValueError):
            ipa.package(self.app, self.output, "0.4.1")
        (self.app / "Xszc").write_bytes(b"fixture")
        (self.app / "Xszc").chmod(0o755)
        (self.app / "outside").symlink_to(Path(self.root.name))
        with self.assertRaises(ValueError):
            ipa.package(self.app, self.output, "0.4.1")
        self.assertFalse(self.output.exists())

    def test_embedded_static_library_is_rejected_without_changing_input_or_output(self):
        library = self.app / "Frameworks" / "libxszc_mobile.a"
        library.parent.mkdir()
        library.write_bytes(b"!<arch>\nstatic library fixture")
        original_bundle = {path.relative_to(self.app): path.read_bytes()
                           for path in self.app.rglob("*") if path.is_file()}
        with self.assertRaisesRegex(ValueError, "static libraries must be linked"):
            ipa.package(self.app, self.output, "0.4.1")
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.parent.exists())

        self.output.parent.mkdir()
        self.output.write_bytes(b"previous release fixture")
        with self.assertRaisesRegex(ValueError, "static libraries must be linked"):
            ipa.package(self.app, self.output, "0.4.1")
        self.assertEqual(self.output.read_bytes(), b"previous release fixture")
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])
        self.assertEqual({path.relative_to(self.app): path.read_bytes()
                          for path in self.app.rglob("*") if path.is_file()}, original_bundle)


if __name__ == "__main__":
    unittest.main()
