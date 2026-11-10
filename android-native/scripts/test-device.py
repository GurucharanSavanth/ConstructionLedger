"""Run one disposable emulator test mode and preserve every receipt before reruns."""
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
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "android-native/app/build/outputs/androidTest-results/connected"
REPORTS = ROOT / "android-native/app/build/reports/androidTests/connected"
EXPECTED_APPLICATION_ID = "com.constructioncost.nativeapp.verification"


def require_verification_command(command):
    properties = [argument for argument in command if argument.startswith("-PverificationApplication")]
    if properties != ["-PverificationApplication=true"]:
        raise ValueError("Device tests require exactly one explicit -PverificationApplication=true; the normal application must never be targeted")


def receipt_counts(paths):
    counts = dict(tests=0, passed=0, failures=0, errors=0, skipped=0)
    cases = []
    for path in paths:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            counts["tests"] += 1
            status = next((key for key in ("failure", "error", "skipped") if case.find(key) is not None), "passed")
            if status != "passed":
                counts[{"failure": "failures", "error": "errors", "skipped": "skipped"}[status]] += 1
            else:
                counts["passed"] += 1
            cases.append(dict(classname=case.get("classname"), name=case.get("name"), status=status))
    if counts["tests"] == 0:
        raise ValueError("No test cases in device receipts; this run cannot pass")
    return counts, cases


def receipt_exit_code(gradle_exit, counts):
    return gradle_exit or int(counts["passed"] == 0 or counts["failures"] > 0 or counts["errors"] > 0 or counts["skipped"] > 0)


def single_test_selector(value):
    if "," in value:
        raise argparse.ArgumentTypeError("Select one class or class#method per run; AGP 9.4.1 truncates comma-valued runner arguments")
    if not re.fullmatch(r"(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*(?:#[A-Za-z_$][A-Za-z0-9_$]*)?", value):
        raise argparse.ArgumentTypeError("Select one fully qualified Java/Kotlin class or class#method using only identifier characters")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--disposable", action="store_true", help="Confirm this emulator installation contains no private ledger")
    parser.add_argument("--mode", choices=("ordinary", "verification", "fixture", "performance"), default="ordinary")
    parser.add_argument("--class", dest="test_class", type=single_test_selector,
                        help="One class or class#method; run multiple selectors separately")
    args = parser.parse_args()
    if not args.disposable:
        parser.error("Pass --disposable only for an emulator installation without private records")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk")
    adb = shutil.which("adb") or str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))

    def device(*command):
        return subprocess.check_output([adb, "-s", args.serial, *command], text=True).strip()

    if device("get-state") != "device" or device("shell", "getprop", "ro.kernel.qemu") != "1":
        parser.error("Device tests require an authorized disposable emulator")
    run = ROOT / ".tools/device-runs" / (time.strftime("%Y%m%d-%H%M%S-") + args.mode + "-" + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    for source in (RESULTS, REPORTS):
        if source.exists():
            shutil.copytree(source, run / "previous" / source.parent.name / source.name)
    hashes = {}
    for base in (ROOT / "android-native/app/src", ROOT / "gradle", ROOT / "android-native/gradle"):
        for path in base.rglob("*"):
            if path.is_file():
                hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    for path in (ROOT / "settings.gradle.kts", ROOT / "build.gradle.kts", ROOT / "gradle.properties", ROOT / "android-native/app/build.gradle.kts",
                 ROOT / "android-native/scripts/test-device.py", ROOT / "android-native/scripts/test_device_receipts_test.py"):
        hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), ":app:connectedDebugAndroidTest", "--console=plain",
               "-PverificationApplication=true"]
    if os.name != "nt":
        command.insert(0, "bash")
    selected = args.test_class
    if args.mode in ("verification", "fixture"):
        selected = selected or ("com.constructioncost.nativeapp.SafV7FixtureTest" if args.mode == "fixture" else "com.constructioncost.nativeapp.ui.RecoveryV7ActivityTest")
    elif args.mode == "performance":
        selected = selected or "com.constructioncost.nativeapp.LedgerPerformanceTest"
    if selected:
        command.append("-Pandroid.testInstrumentationRunnerArguments.class=" + selected)
    else:
        command.append("-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.constructioncost.nativeapp.SeparateVerification")
    if args.mode == "fixture":
        command.append("-Pandroid.testInstrumentationRunnerArguments.v7Saf=prepare")
    require_verification_command(command)
    identity = dict(serial=args.serial, api=device("shell", "getprop", "ro.build.version.sdk"),
                    model=device("shell", "getprop", "ro.product.model"), mode=args.mode,
                    expected_application_id=EXPECTED_APPLICATION_ID, command=command, source_sha256=hashes)
    (run / "identity.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
    environment = {key: value for key, value in os.environ.items()
                   if key.casefold() != "org_gradle_project_verificationapplication"}
    environment["ANDROID_SERIAL"] = args.serial
    jbr = Path("C:/Program Files/Android/Android Studio/jbr")
    if not environment.get("JAVA_HOME") and jbr.is_dir():
        environment["JAVA_HOME"] = str(jbr)
    start = time.time()
    print(f"Device receipts: {run}", flush=True)
    with (run / "gradle.log").open("w", encoding="utf-8") as log:
        result = subprocess.run(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    for source in (RESULTS, REPORTS):
        if source.exists():
            shutil.copytree(source, run / source.parent.name / source.name)
    logs = subprocess.run([adb, "-s", args.serial, "logcat", "-d", "-v", "threadtime", "-t", "3000", "BuildLedger:I", "BuildLedgerPerformance:I", "BuildLedgerStorage:I", "LedgerOperations:I", "AndroidRuntime:E", "*:S"], capture_output=True, text=True)
    (run / "logcat.txt").write_text(logs.stdout, encoding="utf-8")
    if result.returncode:
        try:
            screen = subprocess.run([adb, "-s", args.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=20)
            if screen.returncode == 0:
                (run / "failure.png").write_bytes(screen.stdout)
            tree = subprocess.run([adb, "-s", args.serial, "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, timeout=20)
            (run / "failure-ui.xml").write_bytes(tree.stdout)
        except subprocess.TimeoutExpired:
            pass
    fresh = [path for path in RESULTS.rglob("*.xml") if path.stat().st_mtime >= start - 2]
    try:
        counts, cases = receipt_counts(fresh)
    except (ValueError, ET.ParseError) as failure:
        print(str(failure), file=sys.stderr)
        return result.returncode or 1
    (run / "summary.json").write_text(json.dumps(dict(**counts, cases=cases, gradle_exit=result.returncode), indent=2), encoding="utf-8")
    print(json.dumps(counts), flush=True)
    return receipt_exit_code(result.returncode, counts)


if __name__ == "__main__":
    sys.exit(main())
