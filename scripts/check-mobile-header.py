#!/usr/bin/env python3
"""Check the C header using the exact xcsc resolved by Cargo.lock."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    client_source = ROOT / "crates/client-foundation"
    subprocess.run([
        sys.executable, str(client_source / "tools/ffi_header.py"),
        "--product-source", "crates/mobile-ffi/src/lib.rs",
        "--guard", "ANA_FFI_V1_H",
        "--check", "crates/mobile-ffi/include/ana_ffi_v1.h",
    ], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
