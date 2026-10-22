import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("cv_runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ProcessDouble:
    def __init__(self, output, code=0, timeout=False):
        self.stdout = io.BytesIO(output)
        self.returncode = code
        self.timeout = timeout

    def wait(self, timeout=None):
        if self.timeout:
            self.timeout = False
            raise subprocess.TimeoutExpired("docker", timeout)
        return self.returncode

    def poll(self):
        return None if self.timeout else self.returncode

    def kill(self):
        self.returncode = -9


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.pdf = Path(self.temp.name) / "cv.pdf"
        self.pdf.write_bytes(b"%PDF-test")

    def invoke(self, process):
        with patch.object(runner.subprocess, "Popen", return_value=process) as start, \
                patch.object(runner.subprocess, "run") as cleanup:
            result = runner.extract(self.pdf)
        return result, start, cleanup

    def test_sandbox_arguments_and_single_readonly_input(self):
        expected = runner.failure("PDF_INVALID")
        result, start, cleanup = self.invoke(ProcessDouble(json.dumps(expected).encode()))
        self.assertEqual(result, expected)
        command = start.call_args.args[0]
        for flag in ["--network", "--read-only", "--cap-drop", "--memory", "--memory-swap", "--cpus", "--pids-limit"]:
            self.assertIn(flag, command)
        self.assertIn("none", command)
        self.assertIn("--log-driver", command)
        self.assertEqual(sum("target=/input/cv.pdf,readonly" in arg for arg in command), 1)
        self.assertFalse(start.call_args.kwargs.get("shell", False))
        self.assertTrue(cleanup.called)

    def test_failed_process_does_not_expose_output(self):
        result, _, _ = self.invoke(ProcessDouble(b"private file and parser diagnostics", 137))
        self.assertEqual(result["warnings"], ["SANDBOX_TERMINATED"])

    def test_bounded_output(self):
        result, _, _ = self.invoke(ProcessDouble(b"a" * (runner.MAX_OUTPUT + 1)))
        self.assertEqual(result["warnings"], ["OUTPUT_LIMIT"])

    def test_invalid_json(self):
        result, _, _ = self.invoke(ProcessDouble(b"not JSON"))
        self.assertEqual(result["warnings"], ["EXTRACTION_UNAVAILABLE"])

    def test_invalid_protocol(self):
        result, _, _ = self.invoke(ProcessDouble(b'{"extractorVersion":"other"}'))
        self.assertEqual(result["warnings"], ["INVALID_RESULT"])

    def test_deadline_cleanup_uses_only_generated_name(self):
        result, _, cleanup = self.invoke(ProcessDouble(b"", timeout=True))
        self.assertEqual(result["warnings"], ["SANDBOX_TIMEOUT"])
        self.assertRegex(cleanup.call_args.args[0][-1], r"^cv-extraction-[a-f0-9]{32}$")

    def test_unavailable_docker_and_cleanup_do_not_leak_exception(self):
        with patch.object(runner.subprocess, "Popen", side_effect=OSError("private executable")):
            self.assertEqual(runner.extract(self.pdf)["warnings"], ["EXTRACTION_UNAVAILABLE"])

    def test_cleanup_failure_does_not_replace_valid_result(self):
        expected = runner.failure("PDF_INVALID")
        with patch.object(runner.subprocess, "Popen", return_value=ProcessDouble(json.dumps(expected).encode())), \
                patch.object(runner.subprocess, "run", side_effect=OSError("private diagnostic")):
            self.assertEqual(runner.extract(self.pdf), expected)

    def test_mount_delimiter_in_path_is_rejected(self):
        path = Path(self.temp.name) / "cv,readonly.pdf"
        path.write_bytes(b"PDF")
        with patch.object(runner.subprocess, "Popen") as start:
            self.assertEqual(runner.extract(path)["warnings"], ["INPUT_INVALID"])
            start.assert_not_called()

    def test_file_budget_is_checked_before_docker(self):
        with self.pdf.open("wb") as output:
            output.truncate(10 * 1024 * 1024 + 1)
        with patch.object(runner.subprocess, "Popen") as start:
            self.assertEqual(runner.extract(self.pdf)["warnings"], ["FILE_LIMIT"])
            start.assert_not_called()

    def test_cleanup_timeout_does_not_mask_original_deadline(self):
        process = ProcessDouble(b"", timeout=True)
        with patch.object(runner.subprocess, "Popen", return_value=process), \
                patch.object(runner.subprocess, "run", side_effect=subprocess.TimeoutExpired("docker", 10)):
            self.assertEqual(runner.extract(self.pdf)["warnings"], ["SANDBOX_TIMEOUT"])


if __name__ == "__main__":
    unittest.main()
