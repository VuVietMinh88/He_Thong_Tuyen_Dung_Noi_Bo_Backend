"""Operator/test CLI for one isolated extraction. Never expose Docker's socket to the web application."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import threading
import uuid

IMAGE = "recruitment-cv-extractor:local"
EXTRACTOR_VERSION = "pdf-contact-v2"
MAX_OUTPUT = 128 * 1024


def failure(code):
    return {"extractorVersion": EXTRACTOR_VERSION, "outcome": "FAILED", "processedPages": 0,
            "totalPages": 0, "suggestions": {"fullName": [], "email": [], "phone": []}, "warnings": [code]}


def remove_container(name):
    try:
        subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        pass  # The in-container group deadline remains in force; do not mask the original result.


def extract(pdf, image=IMAGE, deadline=100):
    path = Path(pdf).resolve(strict=True)
    if not path.is_file() or "," in str(path) or "\n" in str(path) or "\r" in str(path):
        return failure("INPUT_INVALID")
    if not 0 < path.stat().st_size <= 10 * 1024 * 1024:
        return failure("FILE_LIMIT")
    name = "cv-extraction-" + uuid.uuid4().hex
    command = ["docker", "run", "--rm", "--name", name, "--network", "none", "--memory", "768m",
               "--memory-swap", "768m", "--cpus", "1", "--pids-limit", "64", "--cap-drop", "ALL",
               "--security-opt", "no-new-privileges:true", "--read-only", "--log-driver", "none",
               "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m,mode=1777",
               "--mount", f"type=bind,source={path},target=/input/cv.pdf,readonly", image]
    process = None
    data = bytearray()
    overflow = threading.Event()

    def read_output():
        try:
            while True:
                block = process.stdout.read(4096)
                if not block:
                    return
                if len(data) + len(block) > MAX_OUTPUT:
                    overflow.set()
                    process.kill()  # Finally removes our named container, including its tmpfs.
                    return
                elif not overflow.is_set():
                    data.extend(block)
        except (OSError, ValueError):
            return  # Pipe closure during cancellation must not produce a background traceback.

    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL)
        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        process.wait(timeout=deadline)
        reader.join(timeout=2)
        if reader.is_alive() or overflow.is_set():
            return failure("OUTPUT_LIMIT")
        if process.returncode != 0:
            return failure("SANDBOX_TERMINATED")
        result = json.loads(data.decode("utf-8"))
        if not isinstance(result, dict) or result.get("extractorVersion") != EXTRACTOR_VERSION:
            return failure("INVALID_RESULT")
        return result
    except subprocess.TimeoutExpired:
        return failure("SANDBOX_TIMEOUT")
    except (OSError, ValueError):
        return failure("EXTRACTION_UNAVAILABLE")
    finally:
        if process is not None:
            if process.poll() is None:
                remove_container(name)
                try:
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=5)
                except (OSError, subprocess.TimeoutExpired):
                    pass
            # Also handles interruption between daemon start and CLI exit. Only our random name is addressed.
            remove_container(name)
            if process.stdout:
                process.stdout.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdf")
    args = parser.parse_args()
    try:
        print(json.dumps(extract(args.pdf), ensure_ascii=False))
    except (OSError, ValueError):
        print(json.dumps(failure("INPUT_INVALID")))
        sys.exit(1)
