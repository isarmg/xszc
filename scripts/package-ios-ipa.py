#!/usr/bin/env python3
"""Package an iphoneos .app as an unsigned IPA without altering its contents."""

import argparse
import os
from pathlib import Path
import plistlib
import re
import stat
import tempfile
import zipfile


def package(app: Path, output: Path, version: str) -> None:
    if app.name != "Xszc.app" or app.is_symlink() or not app.is_dir():
        raise ValueError("expected the built Xszc.app directory")
    if output.suffix != ".ipa":
        raise ValueError("output must have the .ipa extension")
    app = app.resolve()
    if output.resolve().is_relative_to(app):
        raise ValueError("IPA output must be outside the app bundle")
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if info.get("CFBundleIdentifier") != "org.sarmg.xszc":
        raise ValueError("unexpected iOS application identity")
    if info.get("CFBundleShortVersionString") != version:
        raise ValueError("iOS application version does not match the release")
    if info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
        raise ValueError("IPA requires an iphoneos device build, not a simulator build")
    if info.get("MinimumOSVersion") != "27.0":
        raise ValueError("IPA requires a minimum deployment target of iOS 27.0")
    if not re.fullmatch(r"iphoneos27\.0(?:\.\d+)?", str(info.get("DTSDKName", ""))):
        raise ValueError("IPA must be built with the stable iOS 27.0 SDK")
    executable = info.get("CFBundleExecutable")
    if not isinstance(executable, str) or Path(executable).name != executable:
        raise ValueError("invalid application executable")
    binary = app / executable
    if binary.is_symlink() or not binary.is_file() or binary.stat().st_size == 0:
        raise ValueError("application executable is missing or empty")
    if not binary.stat().st_mode & 0o111:
        raise ValueError("application executable has no execute permission")

    entries = [app, *sorted(app.rglob("*"))]
    for entry in entries:
        mode = entry.lstat().st_mode
        if entry.suffix == ".a" and not stat.S_ISDIR(mode):
            raise ValueError("static libraries must be linked, not embedded in the application")
        if entry.is_symlink():
            target = os.readlink(entry)
            if os.path.isabs(target) or not entry.resolve(strict=True).is_relative_to(app):
                raise ValueError("bundle symlink points outside the application")
        elif not (stat.S_ISREG(mode) or stat.S_ISDIR(mode)):
            raise ValueError("unsupported special file in application bundle")

    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(suffix=".ipa", dir=output.parent)
    os.close(descriptor)
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for entry in entries:
                name = "Payload/" + entry.relative_to(app.parent).as_posix()
                if entry.is_symlink():
                    record = zipfile.ZipInfo(name)
                    record.create_system = 3
                    record.external_attr = entry.lstat().st_mode << 16
                    archive.writestr(record, os.fsencode(os.readlink(entry)))
                else:
                    archive.write(entry, name)
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:
                raise ValueError("IPA ZIP integrity check failed")
            required = {"Payload/Xszc.app/Info.plist", f"Payload/Xszc.app/{executable}"}
            if not required.issubset(archive.namelist()):
                raise ValueError("IPA Payload is incomplete")
        os.replace(temporary, output)
    finally:
        Path(temporary).unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    package(args.app, args.output, args.version)
    print(f"Packaged unsigned device IPA: {args.output}")


if __name__ == "__main__":
    main()
