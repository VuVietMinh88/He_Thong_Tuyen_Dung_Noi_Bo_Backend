"""Manual fault injection and cgroup measurement on the real extraction image; synthetic CVs only."""
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
import importlib.util
from unittest.mock import patch

IMAGE = "recruitment-cv-extractor:local"


def run_case(pdf, test_classes, replacement=None, benchmark=False):
    name = "task333-sandbox-" + uuid.uuid4().hex
    entrypoint = json.loads(subprocess.check_output(
        ["docker", "image", "inspect", IMAGE, "--format", "{{json .Config.Entrypoint}}"], text=True))
    if benchmark:
        entrypoint = [arg.replace(".CvExtractionCommand", ".CvExtractionBenchmarkCommand") for arg in entrypoint]
        entrypoint.insert(entrypoint.index("-cp"), "-Dloader.path=/tests")
    command = ["docker", "create", "--name", name, "--network", "none", "--memory", "768m",
               "--memory-swap", "768m", "--cpus", "1", "--pids-limit", "64", "--cap-drop", "ALL",
               "--security-opt", "no-new-privileges:true", "--read-only", "--log-driver", "none",
               "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m,mode=1777", "--mount",
               f"type=bind,source={pdf.resolve()},target=/input/cv.pdf,readonly",
               "--mount", f"type=bind,source={test_classes.resolve()},target=/tests,readonly"]
    if replacement:
        command.extend(["--mount", f"type=bind,source={replacement.resolve()},target=/usr/bin/tesseract,readonly"])
    command.extend(["--entrypoint", entrypoint[0], IMAGE, *entrypoint[1:], "/input/cv.pdf"])
    started = time.monotonic()
    try:
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30)
        completed = subprocess.run(["docker", "start", "--attach", name], capture_output=True, timeout=100)
        result = json.loads(completed.stdout.decode("utf-8"))
        state = json.loads(subprocess.check_output(["docker", "inspect", name], text=True))[0]
        config = state["HostConfig"]
        limits = (config["Memory"] == 768 * 1024 * 1024 and config["MemorySwap"] == config["Memory"]
                  and config["ReadonlyRootfs"] and config["NetworkMode"] == "none"
                  and state["Config"]["User"] == "10002:10002" and not state["NetworkSettings"]["Ports"])
        metrics = re.search(rb"EXTRACTION_METRICS seconds=([0-9.]+) peakBytes=([0-9]+)", completed.stderr)
        return {"result": result, "seconds": round(time.monotonic() - started, 3),
                "limitsVerified": limits, "oomKilled": state["State"]["OOMKilled"],
                "stderrSafe": b"private-cv-marker" not in completed.stderr,
                "processingSeconds": float(metrics[1]) if metrics else None,
                "peakBytes": int(metrics[2]) if metrics else None}
    finally:
        subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)


def verify(fixtures, test_classes):
    summary = []
    baseline = run_case(fixtures / "vi-scan.pdf", test_classes, benchmark=True)
    summary.append({"case": "scan-cgroup-measurement", "passed": baseline["limitsVerified"]
                    and baseline["result"]["outcome"] == "COMPLETE" and not baseline["oomKilled"]
                    and baseline["peakBytes"] is not None and baseline["peakBytes"] < 768 * 1024 * 1024,
                    **{k: v for k, v in baseline.items() if k != "result"}})
    with tempfile.TemporaryDirectory(prefix="task333-fault-") as directory:
        binary = Path(directory) / "tesseract"
        for case, script, warning in [
                ("ocr-timeout", "#!/bin/sh\nsleep 60\n", "OCR_TIMEOUT"),
                ("ocr-stderr-private", "#!/bin/sh\nprintf private-cv-marker >&2\nexit 1\n", "OCR_FAILED"),
                ("ocr-output-limit", "#!/bin/sh\nhead -c 5000000 /dev/zero\n", None)]:
            binary.write_bytes(script.encode("utf-8"))
            result = run_case(fixtures / "vi-scan.pdf", test_classes, replacement=binary)
            passed = result["limitsVerified"] and result["stderrSafe"] and result["result"]["outcome"] == "FAILED"
            if warning:
                passed = passed and warning in result["result"]["warnings"]
            summary.append({"case": case, "passed": passed,
                            "warnings": result["result"]["warnings"],
                            **{k: v for k, v in result.items() if k != "result"}})
    spec = importlib.util.spec_from_file_location("cv_runner_deadline", Path(__file__).with_name("run.py"))
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    original = subprocess.Popen
    owned = []

    def capture(command, *args, **kwargs):
        if "--name" in command:
            owned.append(command[command.index("--name") + 1])
        return original(command, *args, **kwargs)

    with patch.object(subprocess, "Popen", side_effect=capture):
        cancelled = runner.extract(fixtures / "vi-scan.pdf", deadline=1)
    removed = all(subprocess.run(["docker", "inspect", name], stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL).returncode != 0 for name in owned)
    summary.append({"case": "runner-deadline-removes-container", "passed": bool(owned) and removed
                    and cancelled["warnings"] == ["SANDBOX_TIMEOUT"]})
    return {"passed": all(item["passed"] for item in summary), "results": summary}


if __name__ == "__main__":
    report = verify(Path(sys.argv[1]), Path(sys.argv[2]))
    print(json.dumps(report, indent=2))
    sys.exit(0 if report["passed"] else 1)
