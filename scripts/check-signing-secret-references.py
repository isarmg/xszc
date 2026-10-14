#!/usr/bin/env python3
"""Allow only the exact platform and release-manifest signing Secret references."""

from pathlib import Path
import re
import sys


ALLOWED_SECRETS = frozenset(
    {
        "XSZC_ANDROID_SIGNING_PKCS12_BASE64",
        "XSZC_ANDROID_SIGNING_PKCS12_PASSWORD",
    }
)


def validate(text: str) -> set[str]:
    references: set[str] = set()
    for expression in re.finditer(r"\$\{\{(.*?)\}\}", text, re.DOTALL):
        body = expression.group(1)
        for context in re.finditer(r"\bsecrets\b", body, re.IGNORECASE):
            reference = re.match(
                r"\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\b", body[context.end() :]
            )
            if reference is None:
                raise ValueError("signing Secrets require explicit canonical dot references")
            name = reference.group(1)
            if name not in ALLOWED_SECRETS:
                raise ValueError("a noncanonical signing Secret reference is forbidden")
            references.add(name)
    if references != ALLOWED_SECRETS:
        raise ValueError("all canonical protected signing Secrets must be referenced")
    return references


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: check-signing-secret-references.py RELEASE_WORKFLOW")
    workflow = Path(sys.argv[1])
    if not workflow.is_file() or workflow.is_symlink():
        raise SystemExit("release workflow must be a regular non-symlink file")
    try:
        validate(workflow.read_text(encoding="utf-8"))
    except ValueError as error:
        raise SystemExit(f"signing Secret reference check failed: {error}") from error
    print("canonical protected signing Secret reference check passed")


if __name__ == "__main__":
    main()
