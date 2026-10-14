#!/usr/bin/env python3
"""Verify the actual Android release runtime sources before package signing."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[1]


def common():
    spec = importlib.util.spec_from_file_location(
        "xszc_runtime_inventory", ROOT / "scripts" / "release-inventory.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def unique(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("runtime export repeats a JSON object field")
        result[name] = value
    return result


def verify(export):
    module = common()
    environment = module.clean_environment()
    revision = module.run(["git", "rev-parse", "HEAD"], ROOT, environment)
    version = tomllib.loads((ROOT / "Cargo.toml").read_text())["workspace"]["package"]["version"]
    if (ROOT / "VERSION").read_text().strip() != version:
        raise ValueError("Android runtime inventory version differs from the workspace")
    with tempfile.TemporaryDirectory(prefix="xszc-runtime-source-check-") as temporary:
        root = Path(temporary).resolve(strict=True)
        tools = module.checkout_tools(root / "tools", environment)
        producer = module.load_tool("gradle_sbom", tools)
        document = json.loads(producer.bytes_regular(export, 8 * 1024 * 1024),
                              object_pairs_hook=unique)
        stage = root / "stage"
        stage.mkdir()
        bom = producer.produce(document, stage, "xszc", version, revision)
        components = json.loads(bom.read_text())["components"]
        print(f"Verified {len(components)} actual Android release runtime components, "
              "their original upstream bytes, and upstream license texts")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", required=True, type=Path)
    verify(parser.parse_args().export)


if __name__ == "__main__":
    main()
