"""Build/install only the verification app, then prove workflows survive process restart."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.constructioncost.nativeapp.verification"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
PHASES = (
    ("WorkflowRestartTest", "prepare", ()),
    ("WorkflowRestartTest", "verifyAfterRestart", ()),
    ("ReminderOverflowProcessTest", "prepareOverflowForProcessRestart", ("-e", "disposableReminder", "true")),
    ("ReminderOverflowProcessTest", "verifyOverflowAfterProcessRestartAndRemoveFixture", ("-e", "disposableReminder", "true")),
)


def require_one_pass(output):
    counts = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    failed = re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]", output)
    if counts != ["1"] or failed:
        raise ValueError("Expected exactly one passing instrumentation test with no failures/skips")


def verification_apk(metadata_path, expected_id):
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("applicationId") != expected_id or len(metadata.get("elements", [])) != 1:
        raise ValueError("APK metadata must name exactly one verification artifact: " + expected_id)
    directory = metadata_path.parent.resolve()
    apk = (directory / metadata["elements"][0]["outputFile"]).resolve()
    if apk.parent != directory or apk.suffix != ".apk" or not apk.is_file():
        raise ValueError("APK must exist directly inside its metadata directory")
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--disposable", action="store_true")
    args = parser.parse_args()
    if not args.disposable:
        parser.error("Pass --disposable only for an emulator verification installation without private records")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk")
    adb = shutil.which("adb") or str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    environment = {key: value for key, value in os.environ.items() if key.casefold() != "org_gradle_project_verificationapplication"}
    environment["ANDROID_SERIAL"] = args.serial
    jbr = Path("C:/Program Files/Android/Android Studio/jbr")
    if not environment.get("JAVA_HOME") and jbr.is_dir():
        environment["JAVA_HOME"] = str(jbr)

    def device(*command):
        return subprocess.check_output([adb, "-s", args.serial, *command], text=True, timeout=180).strip()

    if device("get-state") != "device" or device("shell", "getprop", "ro.kernel.qemu") != "1":
        parser.error("Process tests require an authorized disposable emulator")
    run = ROOT / ".tools/device-runs" / (time.strftime("%Y%m%d-%H%M%S-process-") + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    print("Process receipts: " + str(run), flush=True)
    command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
               "--console=plain", "-PverificationApplication=true",
               "-Pandroid.testInstrumentationRunnerArguments.class=com.constructioncost.nativeapp.WorkflowRestartTest#prepare"]
    if os.name != "nt":
        command.insert(0, "bash")
    hashes = {}
    for base in (ROOT / "android-native/app/src", ROOT / "android-native/app/schemas", ROOT / "gradle", ROOT / "android-native/gradle", ROOT / "android-native/scripts"):
        for path in base.rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts:
                hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    for relative in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "android-native/app/build.gradle.kts"):
        path = ROOT / relative
        hashes[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    identity = dict(serial=args.serial, api=device("shell", "getprop", "ro.build.version.sdk"),
                    model=device("shell", "getprop", "ro.product.model"), expected_application_id=PACKAGE,
                    command=command, source_sha256=hashes)
    (run / "identity.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
    summary = dict(tests=0, passed=0, failures=0, errors=0, skipped=0, phases=[], complete=False)
    try:
        # One Gradle invocation owns the shared build outputs; installation begins only after it exits.
        with (run / "gradle.log").open("w", encoding="utf-8") as log:
            build = subprocess.run(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
        if build.returncode:
            raise RuntimeError("Verification build failed; see gradle.log")
        apk_root = ROOT / "android-native/app/build/outputs/apk"
        for metadata, expected in ((apk_root / "debug/output-metadata.json", PACKAGE),
                                   (apk_root / "androidTest/debug/output-metadata.json", PACKAGE + ".test")):
            apk = verification_apk(metadata, expected)
            shutil.copy2(metadata, run / (expected + "-metadata.json"))
            identity.setdefault("apk_sha256", {})[expected] = hashlib.sha256(apk.read_bytes()).hexdigest()
            output = device("install", "-r", "-t", str(apk))
            (run / (expected + "-install.log")).write_text(output, encoding="utf-8")
            if not re.search(r"^Success$", output, re.MULTILINE):
                raise RuntimeError("Verification APK installation did not report success")
        registered = device("shell", "pm", "list", "instrumentation")
        (run / "instrumentation.txt").write_text(registered, encoding="utf-8")
        if "instrumentation:" + RUNNER + " (target=" + PACKAGE + ")" not in registered:
            raise RuntimeError("Installed instrumentation must target only the verification app")
        for index, (name, method, extra) in enumerate(PHASES):
            if index in (1, 3):
                action = "force-stop" if index == 1 else "kill"
                output = device("shell", "am", action, PACKAGE)
                (run / (name + "-" + action + ".log")).write_text(output, encoding="utf-8")
            selector = "com.constructioncost.nativeapp." + name + "#" + method
            phase_command = [adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", selector, *extra, RUNNER]
            result = subprocess.run(phase_command, capture_output=True, text=True, timeout=180)
            output = result.stdout + result.stderr
            (run / (name + "-" + method + ".log")).write_text(output, encoding="utf-8")
            if result.returncode:
                raise RuntimeError("Instrumentation command failed: " + selector)
            require_one_pass(output)
            summary["tests"] += 1
            summary["passed"] += 1
            summary["phases"].append(dict(selector=selector, tests=1, passed=1, command=phase_command))
        summary["complete"] = True
        return 0
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as failure:
        summary["errors"] += 1
        summary["error"] = str(failure)
        print(str(failure), file=sys.stderr)
        return 1
    finally:
        (run / "identity.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
        (run / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    sys.exit(main())
