#!/usr/bin/env python3
"""Reject foreign or indirect signing Secret bindings without reading values."""

import importlib.util
from pathlib import Path
import sys
import unittest


sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    "signing_secrets", Path(__file__).with_name("check-signing-secret-references.py")
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class SigningSecretReferenceTests(unittest.TestCase):
    def workflow(self, extra: str = "") -> str:
        return (
            "env:\n"
            "  XSZC_ANDROID_SIGNING_PKCS12_BASE64: "
            "${{ secrets.XSZC_ANDROID_SIGNING_PKCS12_BASE64 }}\n"
            "  XSZC_ANDROID_SIGNING_PKCS12_PASSWORD: "
            "${{ secrets.XSZC_ANDROID_SIGNING_PKCS12_PASSWORD }}\n"
            + extra
        )

    def test_accepts_canonical_bindings_and_repeated_password_reference(self):
        self.assertEqual(checker.validate(self.workflow()), checker.ALLOWED_SECRETS)
        self.assertEqual(
            checker.validate(
                self.workflow("  PASSWORD: ${{ secrets.XSZC_ANDROID_SIGNING_PKCS12_PASSWORD }}\n")
            ),
            checker.ALLOWED_SECRETS,
        )

    def test_rejects_foreign_binding_even_with_both_canonical_bindings(self):
        for expression in (
            "${{ secrets.FOREIGN_ANDROID_SIGNING_PASSWORD }}",
            "${{ secrets . FOREIGN_ANDROID_SIGNING_PASSWORD }}",
            "${{ SECRETS.FOREIGN_ANDROID_SIGNING_PASSWORD }}",
        ):
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                checker.validate(self.workflow(f"  FOREIGN: {expression}\n"))

    def test_rejects_bracket_dynamic_and_whole_context_bindings(self):
        for expression in (
            "${{ secrets['XSZC_ANDROID_SIGNING_PKCS12_PASSWORD'] }}",
            "${{ secrets[env.SIGNING_SECRET_NAME] }}",
            "${{ toJSON(secrets) }}",
            "${{ secrets.* }}",
        ):
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                checker.validate(self.workflow(f"  INDIRECT: {expression}\n"))

    def test_requires_platform_canonical_bindings(self):
        for text in (
            "env: {}\n",
            "env:\n  PASSWORD: ${{ secrets.XSZC_ANDROID_SIGNING_PKCS12_PASSWORD }}\n",
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                checker.validate(text)

    def test_checks_multiline_expressions(self):
        with self.assertRaises(ValueError):
            checker.validate(self.workflow("  FOREIGN: ${{\n    secrets . FOREIGN_KEY\n  }}\n"))

    def test_actual_release_workflow_uses_only_canonical_bindings(self):
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/release.yml"
        self.assertEqual(checker.validate(workflow.read_text()), checker.ALLOWED_SECRETS)


if __name__ == "__main__":
    unittest.main()
