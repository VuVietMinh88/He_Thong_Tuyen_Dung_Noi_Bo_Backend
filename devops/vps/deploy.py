"""One VPS, one application database. No traffic while a new schema is tested.

Run through deploy.sh (flock). All failures return nonzero, including a successful
rollback. Database restore is allowed only after web AND backend have stopped.
"""
import base64
from datetime import datetime, timezone
import filecmp
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import zipfile


# Newest backup folders kept after a successful deployment (same disk as the database).
BACKUP_KEEP = 10
# Free space required beyond the JAR copies and the expected database dump.
FREE_SPACE_MARGIN = 1024 ** 3


class DeploymentError(Exception):
    pass


def run(args, *, stdin=None, stdout=None):
    environment = os.environ.copy()
    if args[:2] == ["docker", "compose"]:
        # The saved .env, not an SSH shell's accidental exports, defines a release.
        for key in list(environment):
            if key.startswith(("COMPOSE_", "BOOTSTRAP_ADMIN_")) or key in {
                "DB_PASSWORD", "AUTH_JWT_SECRET", "MAIL_PASSWORD"
            }:
                environment.pop(key)
    result = subprocess.run(args, stdin=stdin, stdout=stdout or subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False, env=environment)
    if result.returncode:
        # Do not expose resolved Compose config, passwords or SQL data in CI logs.
        # Name the step (e.g. "exec -T database pg_restore"); a shell script shows only its program.
        step = [str(arg) for arg in (args[args.index("-f") + 2:] if "-f" in args else args)]
        if step[3:5] == ["sh", "-c"] and len(step) > 5:
            step = step[:3] + step[5].split()[:1]
        raise DeploymentError(f"Command failed ({result.returncode}): {' '.join(step[:8])}")
    return result.stdout


class Deployer:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.env = self.root / ".env"
        self.runtime = self.root / "runtime"
        self.active = self.runtime / "backend.jar"
        self.good_jar = self.runtime / "last-success.jar"
        self.good_env = self.runtime / "last-success.env"
        self.backup = None
        self.had_previous = False
        self.maintenance = False
        self.new_started = False
        self.committed = False
        self.previous_folder = None

    def compose(self, *args, **kwargs):
        return run(["docker", "compose", "--project-directory", str(self.root),
                    "--env-file", str(self.env), "-f", str(self.root / "compose.yaml"),
                    *args], **kwargs)

    def validate(self, candidate, expected_hash):
        if not self.env.is_file():
            raise DeploymentError("Missing .env; current server was not changed.")
        if not candidate.is_file():
            raise DeploymentError("Candidate JAR missing.")
        with candidate.open("rb") as source:
            actual_hash = hashlib.file_digest(source, "sha256").hexdigest()
        if actual_hash != expected_hash:
            raise DeploymentError("Candidate JAR missing or SHA256 does not match.")
        with zipfile.ZipFile(candidate) as jar:
            if "BOOT-INF/classes/vn/ttcs/recruitment/RecruitmentApplication.class" not in jar.namelist():
                raise DeploymentError("Candidate is not the application JAR.")
        config = json.loads(self.compose("config", "--format", "json"))
        env = config["services"]["backend"]["environment"]
        if len(base64.b64decode(env["AUTH_JWT_SECRET"], validate=True)) < 32:
            raise DeploymentError("AUTH_JWT_SECRET must decode to at least 32 bytes.")
        password = env["BOOTSTRAP_ADMIN_PASSWORD"]
        if not (8 <= len(password) and len(password.encode()) <= 72
                and re.search(r"[A-Za-z]", password) and re.search(r"[0-9]", password)):
            raise DeploymentError("Invalid bootstrap password format.")
        if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", env["BOOTSTRAP_ADMIN_EMAIL"]):
            raise DeploymentError("Invalid bootstrap email.")
        self.had_previous = self.good_jar.is_file()
        if self.had_previous:
            if not self.good_env.is_file() or not self.active.is_file():
                raise DeploymentError("Incomplete previous release; manual recovery required.")
            with zipfile.ZipFile(self.good_jar) as old, zipfile.ZipFile(candidate) as new:
                # develop may lag the initially packaged Sprint 2 integration.
                # Never silently downgrade or rewrite an already deployed migration.
                for name in old.namelist():
                    if name.startswith("BOOT-INF/classes/db/migration/") and name.endswith(".sql"):
                        if name not in new.namelist() or old.read(name) != new.read(name):
                            raise DeploymentError("Candidate removes or changes a deployed migration; keep the existing release.")
            previous = json.loads(run(["docker", "compose", "--project-directory", str(self.root),
                "--env-file", str(self.good_env), "-f", str(self.root / "compose.yaml"),
                "config", "--format", "json"]))
            if previous["services"]["database"]["environment"] != config["services"]["database"]["environment"]:
                raise DeploymentError("Database credentials changed; rotate them separately before deployment.")
        elif self.active.exists():
            raise DeploymentError("Existing JAR has no known-good snapshot; manual recovery required.")
        self.check_free_space(candidate)
        # Static web and image must exist before entering the maintenance window.
        if not (self.root / "frontend" / "index.html").is_file():
            raise DeploymentError("Missing built frontend/index.html.")
        run(["docker", "image", "inspect", "ttcs-backend-runtime:java21"])
        self.compose("run", "--rm", "--no-deps", "web", "caddy", "validate",
                     "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile")

    def check_free_space(self, candidate):
        # Backup, JAR copies and a later rollback all need space on this disk.
        dumps = sorted((self.root / "backups").glob("*/database.dump"))
        needed = (2 * candidate.stat().st_size + FREE_SPACE_MARGIN
                  + (self.good_jar.stat().st_size if self.had_previous else 0)
                  + (dumps[-1].stat().st_size if dumps else 0))
        if shutil.disk_usage(self.root).free < needed:
            raise DeploymentError(f"Not enough free disk space (need {needed // 1024 ** 2} MiB); current server was not changed.")

    def prune_backups(self, current):
        folders = sorted(p for p in (self.root / "backups").iterdir() if p.is_dir())
        for folder in folders[:-BACKUP_KEEP]:
            if folder != current:
                shutil.rmtree(folder)

    @staticmethod
    def copy_atomic(source, target, mode=0o600):
        temporary = target.with_name(target.name + ".next")
        try:
            shutil.copyfile(source, temporary)
            os.chmod(temporary, mode)
            os.replace(temporary, target)
        except BaseException:
            # A half-written copy must not use the space a rollback needs.
            temporary.unlink(missing_ok=True)
            raise

    def restore_copy(self, source, target, mode=0o600):
        # Rollback must work on a full disk when the old file is still in place.
        if target.is_file() and filecmp.cmp(source, target, shallow=False):
            os.chmod(target, mode)
        else:
            self.copy_atomic(source, target, mode)

    def backup_database(self, folder):
        dump = folder / "database.dump"
        with dump.open("xb") as out:
            self.compose("exec", "-T", "database", "sh", "-c",
                         'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom', stdout=out)
        if dump.stat().st_size == 0:
            raise DeploymentError("Database backup is empty.")
        with dump.open("rb") as source:
            self.compose("exec", "-T", "database", "pg_restore", "--list", stdin=source)
        self.backup = dump

    def restore_database(self):
        # --create connects initially to postgres and recreates only recruitment,
        # whose name is embedded in our pg_dump. No application can write now.
        with self.backup.open("rb") as source:
            self.compose("exec", "-T", "database", "sh", "-c",
                'pg_restore -U "$POSTGRES_USER" --dbname=postgres --clean --if-exists '
                '--create --exit-on-error --no-owner --no-privileges', stdin=source)

    def start_backend(self):
        self.compose("up", "-d", "--no-build", "--force-recreate", "--wait",
                     "--wait-timeout", "240", "backend")
        health = json.loads(self.compose("exec", "-T", "backend", "curl", "--fail", "--silent",
                            "--max-time", "10", "http://127.0.0.1:8080/api/v1/health"))
        if health.get("status") != "UP":
            raise DeploymentError("Backend health body does not report UP.")
        # Also require a real query, since the application's /health is liveness.
        self.compose("exec", "-T", "database", "sh", "-c",
            'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 '
            '-c "SELECT count(*) FROM flyway_schema_history WHERE success = true"')

    def rollback(self):
        self.compose("stop", "web", "backend")
        if self.new_started and self.backup:
            self.restore_database()
        if self.had_previous:
            self.restore_copy(self.previous_folder / "previous.env", self.env)
            self.restore_copy(self.previous_folder / "previous.jar", self.active, 0o644)
            self.start_backend()
            self.restore_copy(self.active, self.good_jar)
            self.restore_copy(self.env, self.good_env)
            self.compose("up", "-d", "--no-deps", "web")
            print("ROLLBACK_OK: previous JAR, database and .env restored.", flush=True)
        else:
            self.active.unlink(missing_ok=True)
            print("FIRST_DEPLOY_FAILED: no previous release; web remains stopped.", flush=True)

    def deploy(self, candidate, release, expected_hash):
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}", release):
            raise DeploymentError("Invalid release ID.")
        if not re.fullmatch(r"[a-f0-9]{64}", expected_hash):
            raise DeploymentError("Invalid SHA256.")
        self.validate(candidate, expected_hash)
        self.runtime.mkdir(mode=0o700, exist_ok=True)
        folder = self.root / "backups" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + release)
        folder.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.previous_folder = folder
        shutil.copyfile(self.env, folder / "requested.env")
        if self.had_previous:
            shutil.copyfile(self.good_env, folder / "previous.env")
            shutil.copyfile(self.good_jar, folder / "previous.jar")
        try:
            self.compose("up", "-d", "--wait", "--wait-timeout", "90", "database")
            self.maintenance = True
            self.compose("stop", "web", "backend")
            print("BACKUP: web/backend stopped; taking a consistent database snapshot.", flush=True)
            self.backup_database(folder)
            self.copy_atomic(candidate, self.active, 0o644)
            self.new_started = True
            self.start_backend()
            self.copy_atomic(self.active, self.good_jar)
            self.copy_atomic(self.env, self.good_env)
            (self.runtime / "release.txt").write_text(release + "\n", encoding="utf-8")
            # Once traffic may resume, never restore an earlier DB snapshot:
            # it could erase writes accepted by the healthy new application.
            self.committed = True
            self.compose("up", "-d", "--no-deps", "web")
            print(f"DEPLOY_OK: {release}. Backup: {folder.name}", flush=True)
        except BaseException:
            if self.committed:
                print("WEB_START_FAILED: new backend is healthy; retry starting web. No database rollback.", file=sys.stderr)
            elif self.maintenance:
                # A second TERM/Ctrl+C must not cut pg_restore off after it dropped the database.
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
                signal.signal(signal.SIGINT, signal.SIG_IGN)
                try:
                    self.rollback()
                except BaseException as rollback_error:
                    print("ROLLBACK_FAILED: keep web stopped; recover from backups on the VPS.", file=sys.stderr, flush=True)
                    raise DeploymentError("Deployment and automatic rollback both failed.") from rollback_error
            raise
        try:
            self.prune_backups(folder)
        except OSError as error:
            print(f"WARNING: could not remove old backups ({type(error).__name__}); check disk space.", flush=True)


def main():
    if len(sys.argv) != 4:
        raise SystemExit("Usage: bash deploy.sh candidate.jar release-id sha256")
    os.umask(0o077)
    def interrupted(signum, frame):
        raise DeploymentError("Deployment interrupted; attempting rollback.")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        Deployer(Path(__file__).resolve().parent).deploy(Path(sys.argv[1]).resolve(), sys.argv[2], sys.argv[3])
    except Exception as error:
        print(f"FAILED: {type(error).__name__}: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
