import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("process_gate", Path(__file__).with_name("test-process.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ProcessReceiptsTest(unittest.TestCase):
    def test_exact_single_pass(self):
        gate.require_one_pass("example.Test:.\nTime: 1.0\n\nOK (1 test)\n")

    def test_reject_missing_multiple_failed_and_skipped_results(self):
        for output in ("", "OK (0 tests)", "OK (2 tests)", "OK (1 test)\nOK (1 test)",
                       "FAILURES!!!\nOK (1 test)", "INSTRUMENTATION_FAILED: crashed\nOK (1 test)",
                       "INSTRUMENTATION_ABORTED\nOK (1 test)", "shortMsg=Process crashed\nOK (1 test)",
                       "INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)", "INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)",
                       "INSTRUMENTATION_STATUS_CODE: -1\nOK (1 test)", "INSTRUMENTATION_STATUS_CODE: -2\nOK (1 test)"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                gate.require_one_pass(output)

    def test_metadata_accepts_only_existing_verification_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "verification.apk"
            apk.write_bytes(b"Synthetic APK")
            metadata = root / "output-metadata.json"
            metadata.write_text(json.dumps(dict(applicationId=gate.PACKAGE, elements=[dict(outputFile=apk.name)])))
            self.assertEqual(apk.resolve(), gate.verification_apk(metadata, gate.PACKAGE))
            for value in (
                dict(applicationId="com.constructioncost.nativeapp", elements=[dict(outputFile=apk.name)]),
                dict(applicationId=gate.PACKAGE, elements=[]),
                dict(applicationId=gate.PACKAGE, elements=[dict(outputFile=apk.name)] * 2),
                dict(applicationId=gate.PACKAGE, elements=[dict(outputFile="missing.apk")]),
                dict(applicationId=gate.PACKAGE, elements=[dict(outputFile="../verification.apk")]),
            ):
                metadata.write_text(json.dumps(value))
                with self.subTest(value=value), self.assertRaises(ValueError):
                    gate.verification_apk(metadata, gate.PACKAGE)

    def test_runner_builds_only_verification_and_executes_ordered_phases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "android-native/app/build.gradle.kts"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("Synthetic source")
            for relative, application_id in (("debug", gate.PACKAGE), ("androidTest/debug", gate.PACKAGE + ".test")):
                directory = root / "android-native/app/build/outputs/apk" / relative
                directory.mkdir(parents=True)
                (directory / "synthetic.apk").write_bytes(b"Synthetic APK")
                (directory / "output-metadata.json").write_text(json.dumps(dict(applicationId=application_id, elements=[dict(outputFile="synthetic.apk")])))
            calls = []

            def device(command, **kwargs):
                calls.append(command)
                if "install" in command:
                    return "Success"
                if "instrumentation" in command:
                    return "instrumentation:" + gate.RUNNER + " (target=" + gate.PACKAGE + ")"
                return {"get-state": "device", "ro.kernel.qemu": "1", "ro.build.version.sdk": "36",
                        "ro.product.model": "Synthetic emulator"}.get(command[-1], "")

            def execute(command, **kwargs):
                calls.append(command)
                return subprocess.CompletedProcess(command, 0, stdout="OK (1 test)\n", stderr="")

            with patch.object(gate, "ROOT", root), patch("sys.argv", ["test-process.py", "--serial", "emulator-5554", "--disposable"]), patch.object(gate.subprocess, "check_output", side_effect=device), patch.object(gate.subprocess, "run", side_effect=execute):
                self.assertEqual(0, gate.main())
            builds = [command for command in calls if ":app:assembleDebug" in command]
            self.assertEqual(1, len(builds))
            self.assertIn("-PverificationApplication=true", builds[0])
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.class=com.constructioncost.nativeapp.WorkflowRestartTest#prepare", builds[0])
            stops = [command for command in calls if "force-stop" in command or "kill" in command]
            self.assertEqual(["force-stop", "kill"], [command[-2] for command in stops])
            self.assertTrue(all(command[-1] == gate.PACKAGE for command in stops))
            self.assertFalse(any("uninstall" in command or "clear" in command for command in calls))
            summaries = list((root / ".tools/device-runs").glob("*/summary.json"))
            self.assertEqual(1, len(summaries))
            summary = json.loads(summaries[0].read_text())
            self.assertTrue(summary["complete"])
            self.assertEqual(4, summary["passed"])

    def test_disposable_guard_precedes_device_commands(self):
        with patch("sys.argv", ["test-process.py", "--serial", "emulator-5554"]), patch.object(gate.subprocess, "check_output") as device:
            with self.assertRaises(SystemExit) as failure:
                gate.main()
            self.assertEqual(2, failure.exception.code)
            device.assert_not_called()

    def test_non_emulator_guard_precedes_build(self):
        with patch("sys.argv", ["test-process.py", "--serial", "phone", "--disposable"]), patch.object(gate.subprocess, "check_output", side_effect=["device", "0"]), patch.object(gate.subprocess, "run") as build:
            with self.assertRaises(SystemExit) as failure:
                gate.main()
            self.assertEqual(2, failure.exception.code)
            build.assert_not_called()


if __name__ == "__main__":
    unittest.main()
