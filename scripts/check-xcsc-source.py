#!/usr/bin/env python3
"""Check the client boundary against the repository-owned Foundation."""
from pathlib import Path
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
FOUNDATION = ROOT / "crates/client-foundation"
metadata = json.loads(subprocess.check_output([
    "cargo", "metadata", "--locked", "--all-features", "--format-version", "1",
], cwd=ROOT))
packages = [package for package in metadata["packages"] if package["name"] == "xcsc"]
if len(packages) != 1:
    raise SystemExit("expected exactly one locked xcsc support package")
package = packages[0]
if package["source"] is not None or Path(package["manifest_path"]).resolve() != FOUNDATION / "Cargo.toml":
    raise SystemExit("xcsc must resolve to the repository-owned client Foundation")
subprocess.run([
    sys.executable, str(FOUNDATION / "scripts/check-xcsc.py"),
    "--product-root", str(ROOT),
], cwd=ROOT, check=True)
