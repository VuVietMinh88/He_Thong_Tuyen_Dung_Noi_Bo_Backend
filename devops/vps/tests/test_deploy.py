"""Fault-injection checks; Docker itself is not invoked by these unit tests."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

MODULE_PATH = Path(__file__).resolve().parents[1] / "deploy.py"
spec = importlib.util.spec_from_file_location("deployment", MODULE_PATH)
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class DockerDouble:
    def __init__(self, root, fail_at=None):
        self.root = root
        self.fail_at = fail_at
        self.calls = []
        self.starts = 0
        self.restored = False
        self.web_open = False
        active = root / 'runtime/backend.jar'
        self.old_jar = active.read_bytes() if active.exists() else None

    def __call__(self, args, stdin=None, stdout=None):
        args = [str(arg) for arg in args]
        if args[:2] == ["docker", "image"]:
            return b"[]"
        action = args[args.index("-f") + 2:]
        self.calls.append(action)
        if action[:1] == ["config"]:
            if self.fail_at == "missing-env-value":
                raise deployment.DeploymentError("missing MAIL_PASSWORD")
            return json.dumps({"services": {
                "backend": {"environment": {
                    "AUTH_JWT_SECRET": base64.b64encode(b"x" * 48).decode(),
                    "BOOTSTRAP_ADMIN_PASSWORD": "TestOnly123!",
                    "BOOTSTRAP_ADMIN_EMAIL": "admin@example.test",
                }}, "database": {"environment": {"POSTGRES_DB": "recruitment"}}
            }}).encode()
        if action[:1] == ["stop"]:
            self.web_open = False
        if "pg_dump" in " ".join(action):
            if self.fail_at == "backup":
                raise deployment.DeploymentError("disk full")
            stdout.write(b"fake-database-snapshot")
        if "--clean" in " ".join(action):
            self.restored = True
            if self.fail_at == "restore":
                raise deployment.DeploymentError("restore unavailable")
        if action[:1] == ["up"] and "--force-recreate" in action:
            self.starts += 1
            if self.starts == 1 and self.fail_at in {"health", "restore"}:
                raise deployment.DeploymentError("new backend unhealthy")
            if self.starts > 1:
                assert (self.root / "runtime/backend.jar").read_bytes() == self.old_jar
        if action == ["up", "-d", "--no-deps", "web"]:
            if self.fail_at == "web":
                raise deployment.DeploymentError("web start failed")
            self.web_open = True
        if action[:4] == ['exec', '-T', 'backend', 'curl']:
            return b'{"status":"DOWN"}' if self.fail_at == 'health-body' and self.starts == 1 else b'{"status":"UP"}'
        return b"ok"


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "runtime").mkdir()
        (self.root / "frontend").mkdir()
        (self.root / "frontend/index.html").write_text("frontend")
        (self.root / ".env").write_text("new-env")
        with zipfile.ZipFile(self.root / 'runtime/backend.jar', 'w') as old:
            old.writestr('BOOT-INF/classes/db/migration/V1__initial.sql', 'CREATE TABLE old_data(id int);')
        self.old_jar = (self.root / 'runtime/backend.jar').read_bytes()
        (self.root / 'runtime/last-success.jar').write_bytes(self.old_jar)
        (self.root / "runtime/last-success.env").write_text("old-env")
        self.candidate = self.root / "candidate.jar"
        with zipfile.ZipFile(self.candidate, "w") as jar:
            jar.writestr("BOOT-INF/classes/vn/ttcs/recruitment/RecruitmentApplication.class", b"class")
            jar.writestr('BOOT-INF/classes/db/migration/V1__initial.sql', 'CREATE TABLE old_data(id int);')
            jar.writestr('BOOT-INF/classes/db/migration/V2__new.sql', 'CREATE TABLE new_data(id int);')
        self.checksum = hashlib.sha256(self.candidate.read_bytes()).hexdigest()

    def execute(self, fail_at=None, checksum=None):
        docker = DockerDouble(self.root, fail_at)
        instance = deployment.Deployer(self.root)
        with patch.object(deployment, "run", docker):
            try:
                instance.deploy(self.candidate, "test-release", checksum or self.checksum)
            except Exception as error:
                return docker, error
        return docker, None

    def test_missing_env_never_stops_server(self):
        (self.root / ".env").unlink()
        docker, error = self.execute()
        self.assertIsNotNone(error)
        self.assertEqual(docker.calls, [])
        self.assertEqual((self.root / "runtime/backend.jar").read_bytes(), self.old_jar)

    def test_missing_required_value_never_stops_server(self):
        docker, error = self.execute("missing-env-value")
        self.assertIsNotNone(error)
        self.assertFalse(any(call[0] in {"stop", "up"} for call in docker.calls))

    def test_wrong_jar_checksum_never_stops_server(self):
        docker, error = self.execute(checksum="0" * 64)
        self.assertIsNotNone(error)
        self.assertEqual(docker.calls, [])

    def test_failed_backup_keeps_old_jar_and_reopens_old_server(self):
        docker, error = self.execute("backup")
        self.assertIsNotNone(error)
        self.assertTrue(docker.web_open)
        self.assertFalse(docker.restored)
        self.assertEqual((self.root / "runtime/backend.jar").read_bytes(), self.old_jar)

    def test_unhealthy_release_restores_db_env_jar_before_opening_web(self):
        docker, error = self.execute("health")
        self.assertIsNotNone(error)  # rollback success MUST still fail the CI run
        self.assertTrue(docker.restored)
        self.assertTrue(docker.web_open)
        self.assertEqual((self.root / ".env").read_text(), "old-env")
        self.assertEqual((self.root / "runtime/backend.jar").read_bytes(), self.old_jar)
        self.assertEqual(docker.starts, 2)
        dump_index = next(i for i, args in enumerate(docker.calls) if "pg_dump" in " ".join(args))
        self.assertIn(["stop", "web", "backend"], docker.calls[:dump_index])
        restore_index = next(i for i, args in enumerate(docker.calls) if "--clean" in " ".join(args))
        self.assertNotIn(["up", "-d", "--no-deps", "web"], docker.calls[:restore_index])

    def test_failed_restore_does_not_reopen_web_or_report_success(self):
        docker, error = self.execute("restore")
        self.assertIsNotNone(error)
        self.assertFalse(docker.web_open)
        self.assertEqual(docker.starts, 1)

    def test_http_200_with_down_body_is_not_a_successful_deployment(self):
        docker, error = self.execute('health-body')
        self.assertIsNotNone(error)
        self.assertTrue(docker.restored)
        self.assertTrue(docker.web_open)
        self.assertEqual((self.root / 'runtime/backend.jar').read_bytes(), self.old_jar)

    def test_success_saves_previous_backup_and_promotes_new_release(self):
        docker, error = self.execute()
        self.assertIsNone(error)
        self.assertTrue(docker.web_open)
        self.assertFalse(docker.restored)
        self.assertEqual((self.root / "runtime/backend.jar").read_bytes(), self.candidate.read_bytes())
        backup = next((self.root / "backups").iterdir())
        self.assertEqual((backup / "previous.jar").read_bytes(), self.old_jar)
        self.assertEqual((backup / "requested.env").read_text(), "new-env")
        self.assertGreater((backup / "database.dump").stat().st_size, 0)

    def test_failed_first_deploy_restores_empty_db_and_keeps_web_stopped(self):
        for child in (self.root / "runtime").iterdir():
            child.unlink()
        docker, error = self.execute("health")
        self.assertIsNotNone(error)
        self.assertTrue(docker.restored)
        self.assertFalse(docker.web_open)
        self.assertFalse((self.root / "runtime/backend.jar").exists())

    def test_web_start_failure_never_discards_possible_new_writes(self):
        docker, error = self.execute("web")
        self.assertIsNotNone(error)
        self.assertFalse(docker.restored)
        self.assertEqual((self.root / "runtime/backend.jar").read_bytes(), self.candidate.read_bytes())

    def test_interruption_during_health_check_attempts_rollback(self):
        docker = DockerDouble(self.root)
        original = docker.__call__
        raised = False
        def interrupted(args, **kwargs):
            nonlocal raised
            if "--force-recreate" in args and not raised:
                raised = True
                raise KeyboardInterrupt()
            return original(args, **kwargs)
        with patch.object(deployment, "run", interrupted):
            with self.assertRaises(KeyboardInterrupt):
                deployment.Deployer(self.root).deploy(self.candidate, "interrupt", self.checksum)
        self.assertTrue(docker.restored)
        self.assertTrue(docker.web_open)
        self.assertEqual((self.root / "runtime/backend.jar").read_bytes(), self.old_jar)

    def test_older_develop_missing_migration_cannot_downgrade_live_app(self):
        with zipfile.ZipFile(self.candidate, 'w') as jar:
            jar.writestr('BOOT-INF/classes/vn/ttcs/recruitment/RecruitmentApplication.class', b'class')
        self.checksum = hashlib.sha256(self.candidate.read_bytes()).hexdigest()
        docker, error = self.execute()
        self.assertIsNotNone(error)
        self.assertFalse(any(call[0] in {'stop', 'up'} for call in docker.calls))
        self.assertEqual((self.root / 'runtime/backend.jar').read_bytes(), self.old_jar)

    def test_edited_migration_is_rejected_before_stopping_old_app(self):
        with zipfile.ZipFile(self.candidate, 'w') as jar:
            jar.writestr('BOOT-INF/classes/vn/ttcs/recruitment/RecruitmentApplication.class', b'class')
            jar.writestr('BOOT-INF/classes/db/migration/V1__initial.sql', 'DROP TABLE old_data;')
        self.checksum = hashlib.sha256(self.candidate.read_bytes()).hexdigest()
        docker, error = self.execute()
        self.assertIsNotNone(error)
        self.assertFalse(any(call[0] in {'stop', 'up'} for call in docker.calls))

    def test_low_disk_space_never_stops_server(self):
        usage = shutil.disk_usage(self.root)._replace(free=0)
        with patch.object(deployment.shutil, "disk_usage", return_value=usage):
            docker, error = self.execute()
        self.assertIn("free disk space", str(error))
        self.assertFalse(any(call[0] in {"stop", "up"} for call in docker.calls))
        self.assertFalse((self.root / "backups").exists())

    def test_success_keeps_only_newest_backups(self):
        old = [self.root / "backups" / f"20000101T0000{i:02d}Z-old" for i in range(deployment.BACKUP_KEEP + 2)]
        for folder in old:
            folder.mkdir(parents=True)
        docker, error = self.execute()
        self.assertIsNone(error)
        remaining = sorted((self.root / "backups").iterdir())
        self.assertEqual(len(remaining), deployment.BACKUP_KEEP)
        self.assertTrue(remaining[-1].name.endswith("-test-release"))
        self.assertFalse(old[0].exists())

    def test_full_disk_during_jar_copy_still_rolls_back_without_new_space(self):
        (self.root / ".env").write_text("old-env")  # unchanged configuration
        real_copy = shutil.copyfile
        disk_full = False
        def copyfile(source, target):
            nonlocal disk_full
            if Path(target).name.endswith(".next") and (disk_full or Path(source) == self.candidate):
                disk_full = True
                Path(target).write_bytes(b"partial")
                raise OSError(28, "No space left on device")
            return real_copy(source, target)
        with patch.object(deployment.shutil, "copyfile", copyfile):
            docker, error = self.execute()
        self.assertIsInstance(error, OSError)
        self.assertTrue(docker.web_open)
        self.assertFalse((self.root / "runtime/backend.jar.next").exists())
        self.assertEqual((self.root / "runtime/backend.jar").read_bytes(), self.old_jar)

    def test_second_signal_cannot_interrupt_database_restore(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            self.addCleanup(signal.signal, signum, signal.getsignal(signum))
        docker = DockerDouble(self.root, "health")
        seen = []
        def recording(args, **kwargs):
            if "--clean" in " ".join(map(str, args)):
                seen.append((signal.getsignal(signal.SIGTERM), signal.getsignal(signal.SIGINT)))
            return docker(args, **kwargs)
        with patch.object(deployment, "run", recording):
            with self.assertRaises(deployment.DeploymentError):
                deployment.Deployer(self.root).deploy(self.candidate, "signal", self.checksum)
        self.assertEqual(seen, [(signal.SIG_IGN, signal.SIG_IGN)])
        self.assertTrue(docker.web_open)

    def test_failed_command_names_the_step_without_output(self):
        failed = subprocess.CompletedProcess([], 1, b"", b"private-test-value")
        with patch.object(deployment.subprocess, "run", return_value=failed):
            with self.assertRaises(deployment.DeploymentError) as raised:
                deployment.run(["docker", "compose", "--project-directory", "/x", "--env-file", "/x/.env",
                                "-f", "/x/compose.yaml", "exec", "-T", "database", "sh", "-c",
                                'pg_restore -U "$POSTGRES_USER" --clean'])
        self.assertIn("exec -T database pg_restore", str(raised.exception))
        self.assertNotIn("private-test-value", str(raised.exception))
        self.assertNotIn("--clean", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
