import importlib.util
import argparse
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("device_gate", Path(__file__).with_name("test-device.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DeviceReceiptsTest(unittest.TestCase):
    def run_mode_with_fake_processes(self, mode, inherited_environment=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            results = root / "android-native/app/build/outputs/androidTest-results/connected"
            reports = root / "android-native/app/build/reports/androidTests/connected"
            for relative in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                             "android-native/app/build.gradle.kts", "android-native/scripts/test-device.py",
                             "android-native/scripts/test_device_receipts_test.py"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("Synthetic runner fixture\n", encoding="utf-8")
            calls = []

            def fake_device_output(command, **kwargs):
                return {"get-state": "device", "ro.kernel.qemu": "1", "ro.build.version.sdk": "36",
                        "ro.product.model": "Synthetic verification emulator"}[command[-1]]

            def fake_process(command, **kwargs):
                calls.append((list(command), kwargs))
                if ":app:connectedDebugAndroidTest" in command:
                    results.mkdir(parents=True)
                    (results / "TEST-safety.xml").write_text('<testsuite><testcase name="synthetic-receipt"/></testsuite>')
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

            with patch.object(gate, "ROOT", root), patch.object(gate, "RESULTS", results), \
                    patch.object(gate, "REPORTS", reports), \
                    patch.object(gate.sys, "argv", ["test-device.py", "--serial", "emulator-test", "--disposable", "--mode", mode]), \
                    patch.dict(gate.os.environ, inherited_environment or {}, clear=True), \
                    patch.object(gate.shutil, "which", return_value="synthetic-adb"), \
                    patch.object(gate.subprocess, "check_output", side_effect=fake_device_output), \
                    patch.object(gate.subprocess, "run", side_effect=fake_process), patch("builtins.print"):
                self.assertEqual(0, gate.main())
            identity_path = next((root / ".tools/device-runs").glob("*/identity.json"))
            return calls[0], json.loads(identity_path.read_text(encoding="utf-8"))

    def test_every_mode_explicitly_selects_only_the_verification_application(self):
        for mode in ("ordinary", "verification", "fixture", "performance"):
            with self.subTest(mode=mode):
                (command, _), identity = self.run_mode_with_fake_processes(mode)
                self.assertEqual(["-PverificationApplication=true"],
                                 [argument for argument in command if argument.startswith("-PverificationApplication")])
                self.assertEqual(command, identity["command"])
                self.assertEqual("com.constructioncost.nativeapp.verification", identity["expected_application_id"])

    def test_conflicting_case_environment_cannot_select_the_normal_application(self):
        inherited = {"ORG_GRADLE_PROJECT_verificationApplication": "false",
                     "ORG_GRADLE_PROJECT_VERIFICATIONAPPLICATION": "false"}
        for mode in ("ordinary", "verification", "fixture", "performance"):
            with self.subTest(mode=mode):
                (command, kwargs), _ = self.run_mode_with_fake_processes(mode, inherited)
                self.assertIn("-PverificationApplication=true", command)
                self.assertFalse(any(key.casefold() == "org_gradle_project_verificationapplication"
                                     for key in kwargs["env"]))

    def test_connected_command_guard_rejects_normal_or_ambiguous_application_selection(self):
        for properties in ([], ["-PverificationApplication=false"], ["-PverificationApplication"],
                           ["-PverificationApplication=true", "-PverificationApplication=false"],
                           ["-PverificationApplication=true", "-PverificationApplication=true"]):
            with self.subTest(properties=properties):
                with self.assertRaisesRegex(ValueError, "verificationApplication=true"):
                    gate.require_verification_command(["gradlew", ":app:connectedDebugAndroidTest", *properties])
        gate.require_verification_command(["gradlew", ":app:connectedDebugAndroidTest", "-PverificationApplication=true"])

    def test_single_class_and_method_selectors_remain_exact_and_csv_is_rejected(self):
        self.assertEqual("package.FirstTest", gate.single_test_selector("package.FirstTest"))
        self.assertEqual("package.FirstTest#case", gate.single_test_selector("package.FirstTest#case"))
        nested = "com.example._Outer$InnerTest#method_case$generated"
        self.assertEqual(nested, gate.single_test_selector(nested))
        with self.assertRaisesRegex(argparse.ArgumentTypeError, "one class"):
            gate.single_test_selector("package.FirstTest,package.SecondTest")

    def test_selector_rejects_shell_syntax_and_malformed_identifiers_before_any_process(self):
        malicious = ("com.example.ExampleTest&ver", "com.example.ExampleTest&&ver", "com.example.ExampleTest|ver",
                     "com.example.ExampleTest>result", "com.example.ExampleTest<source", "com.example.ExampleTest^&ver",
                     "com.example.ExampleTest%PATH%", "com.example.ExampleTest!PATH!", 'com.example."ExampleTest"',
                     "com.example.'ExampleTest'", "com.example.ExampleTest\nver", "com.example.ExampleTest\n",
                     "com.example.ExampleTest\r\n", "com.example.ExampleTest#case\n", "com.example.ExampleTest case",
                     "com.example.ExampleTest;ver", "com.example.ExampleTest`ver", "com.example.ExampleTest$(ver)",
                     "ExampleTest", "com..ExampleTest", ".com.ExampleTest", "com.example.1Test", "com.example.ExampleTest#",
                     "com.example.ExampleTest#case#other", "com/example/ExampleTest", "")
        for selector in malicious:
            with self.subTest(selector=repr(selector)):
                with self.assertRaises(argparse.ArgumentTypeError):
                    gate.single_test_selector(selector)
                with patch.object(gate.sys, "argv", ["test-device.py", "--serial", "emulator-test", "--disposable", "--class", selector]), \
                        patch.object(gate.subprocess, "check_output") as device, \
                        patch.object(gate.subprocess, "run") as processes, patch.object(gate.sys, "stderr", io.StringIO()):
                    with self.assertRaises(SystemExit) as rejected:
                        gate.main()
                    self.assertEqual(2, rejected.exception.code)
                    device.assert_not_called()
                    processes.assert_not_called()

    def test_nested_receipts_keep_failures_errors_and_skips_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "run.xml"
            path.write_text('<testsuites><testsuite><testcase name="ok"/><testcase name="bad"><failure/></testcase>'
                            '<testcase name="crash"><error/></testcase><testcase name="guard"><skipped/></testcase></testsuite></testsuites>')
            counts, cases = gate.receipt_counts([path])
            self.assertEqual(dict(tests=4, passed=1, failures=1, errors=1, skipped=1), counts)
            self.assertEqual(["passed", "failure", "error", "skipped"], [case["status"] for case in cases])
            self.assertEqual(1, gate.receipt_exit_code(0, counts))

    def test_guarded_only_run_fails_without_losing_skip_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "run.xml"
            path.write_text('<testsuite><testcase name="guard"><skipped/></testcase></testsuite>')
            counts, cases = gate.receipt_counts([path])
            self.assertEqual(dict(tests=1, passed=0, failures=0, errors=0, skipped=1), counts)
            self.assertEqual("skipped", cases[0]["status"])
            self.assertEqual(1, gate.receipt_exit_code(0, counts))

    def test_partial_skip_cannot_report_a_passing_acceptance_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "run.xml"
            path.write_text('<testsuite><testcase name="verified"/><testcase name="not-run"><skipped/></testcase></testsuite>')
            counts, _ = gate.receipt_counts([path])
            self.assertEqual(1, gate.receipt_exit_code(0, counts))

    def test_passing_receipt_still_requires_successful_gradle_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "run.xml"
            path.write_text('<testsuite><testcase name="verified"/></testsuite>')
            counts, _ = gate.receipt_counts([path])
            self.assertEqual(0, gate.receipt_exit_code(0, counts))
            self.assertEqual(2, gate.receipt_exit_code(2, counts))

    def test_empty_receipt_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "run.xml"
            path.write_text('<testsuite tests="0"/>')
            with self.assertRaisesRegex(ValueError, "No test cases"):
                gate.receipt_counts([path])


if __name__ == "__main__":
    unittest.main()
