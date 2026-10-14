#!/usr/bin/env python3
"""Source guards for CI-only compiler caching; real cache hits need Android CI."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = (ROOT / '.github/workflows/build.yml').read_text()
APP = (ROOT / 'clients/android/app/build.gradle.kts').read_text()
EMULATOR = (ROOT / 'scripts/test-android-emulator.sh').read_text()


class CompilationCacheTests(unittest.TestCase):
    def test_cache_policy_is_opt_in_and_compilation_only(self):
        # Keep the entire small policy gated; no changes to ordinary/release builds.
        policy = APP[APP.index('if (providers.gradleProperty("xszcCiCompilationCache")'):]
        self.assertTrue(policy.startswith(
            'if (providers.gradleProperty("xszcCiCompilationCache").orNull == "true") {'))
        self.assertEqual(policy.count('{'), policy.count('}'))
        allowlist = policy.split('val cachedCompilationTasks = setOf(', 1)[1].split(')', 1)[0]
        self.assertEqual(set(re.findall(r'"([^"]+)"', allowlist)), {
            'compileDebugKotlin', 'compileDebugUnitTestKotlin', 'compileDebugAndroidTestKotlin',
            'compileDebugJavaWithJavac', 'compileDebugUnitTestJavaWithJavac',
            'compileDebugAndroidTestJavaWithJavac',
        })
        self.assertIn('''    tasks.configureEach {
        if (name !in cachedCompilationTasks) {
            outputs.doNotCacheIf("CI caches only debug Kotlin/Java compilation") { true }
        }
    }
    tasks.withType<Test>().configureEach {
        outputs.upToDateWhen { false }
    }
}''', policy)
        self.assertIn('import org.gradle.api.tasks.testing.Test', APP)
        self.assertEqual(APP.count('doNotCacheIf'), 1)
        self.assertEqual(APP.count('upToDateWhen'), 1)
        self.assertNotIn('outputs.cacheIf', APP)  # Never force compiler cacheability.

    def test_only_unit_assemble_invocation_opts_in(self):
        self.assertIn('run: gradle -p clients/android --build-cache '
                      '-PxszcCiCompilationCache=true testDebugUnitTest '
                      'assembleDebug assembleDebugAndroidTest', WORKFLOW)
        self.assertEqual(WORKFLOW.count('--build-cache'), 1)
        self.assertEqual(WORKFLOW.count('-PxszcCiCompilationCache=true'), 1)
        self.assertIn('cache-provider: basic', WORKFLOW)
        for path in [ROOT / 'clients/android/gradle.properties',
                     ROOT / '.github/workflows/release.yml']:
            source = path.read_text()
            self.assertNotIn('org.gradle.caching', source)
            self.assertNotIn('xszcCiCompilationCache', source)
        self.assertIn('run: python3 scripts/test-ci-android-cache.py',
                      WORKFLOW.split('\n  android:', 1)[0])

    def test_runtime_audit_and_full_emulator_suite_stay_uncached(self):
        audit = WORKFLOW.split('Verify actual Android release runtime sources before compiling', 1)[1]
        audit = audit.split('      - uses:', 1)[0]
        self.assertIn(':app:exportXssiRuntimeInventory', audit)
        self.assertIn('scripts/check-android-runtime-inventory.py --export', audit)
        for source in [audit, EMULATOR]:
            self.assertNotIn('--build-cache', source)
            self.assertNotIn('xszcCiCompilationCache', source)
        self.assertIn('gradle -p clients/android -PxszcEmulatorTests=true '
                      'connectedDebugAndroidTest\n', EMULATOR)
        self.assertNotIn('android.testInstrumentationRunnerArguments', EMULATOR)
        self.assertIn('run: bash scripts/test-android-emulator.sh', WORKFLOW)
        methods = sum(len(re.findall(r'@Test\b', path.read_text())) for path in
                      (ROOT / 'clients/android/app/src/androidTest').rglob('*.kt'))
        self.assertGreaterEqual(methods, 58)

    def test_arm64_apk_is_uploaded_before_x86_native_rebuild(self):
        markers = ['cargo ndk -t arm64-v8a', '--build-cache -PxszcCiCompilationCache=true',
                   'name: xszc-android-debug', 'cargo ndk -t x86_64',
                   'run: bash scripts/test-android-emulator.sh']
        positions = [WORKFLOW.index(marker) for marker in markers]
        self.assertEqual(positions, sorted(positions))


if __name__ == '__main__':
    unittest.main()
