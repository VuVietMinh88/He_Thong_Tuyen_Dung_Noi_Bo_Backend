"""Use the real Compose parser, without starting any container."""
import base64
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('configure', ROOT / 'configure.py')
configure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)


class ComposeEnvironmentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        executable = os.environ.get('TTCS_COMPOSE_CLI')
        cls.command = [executable] if executable else ['docker', 'compose']
        if not executable and not shutil.which('docker'):
            raise unittest.SkipTest('Docker Compose CLI is not installed.')
        subprocess.run([*cls.command, 'version'], check=True, capture_output=True)

    def test_passwords_survive_the_real_dotenv_parser(self):
        samples = ['plain-pass', 'dollar$word${OTHER}# space!', 'quote\'and"double',
                   'back\\slash', 'end\\', 'a\\\'b']
        for password in samples:
            with self.subTest(password=password), tempfile.TemporaryDirectory() as tmp:
                env_file = Path(tmp) / '.env'
                values = dict(DB_PASSWORD='test-only-db', AUTH_JWT_SECRET=base64.b64encode(b'x' * 48).decode(),
                              BOOTSTRAP_ADMIN_EMAIL='admin@example.test', BOOTSTRAP_ADMIN_PASSWORD='TestOnly123!',
                              MAIL_PASSWORD=password)
                env_file.write_text(''.join(f'{k}={configure.dotenv_value(v)}\n' for k,v in values.items()), encoding='utf-8')
                process_env = {key: value for key, value in os.environ.items()
                               if key not in values and not key.startswith('COMPOSE_')}
                result = subprocess.run([*self.command, '--env-file', str(env_file), '-f', str(ROOT / 'compose.yaml'),
                                         'config', '--environment'], check=True, capture_output=True, text=True, env=process_env)
                actual = next(line.partition('=')[2] for line in result.stdout.splitlines() if line.startswith('MAIL_PASSWORD='))
                self.assertEqual(actual, password)

    def test_multiline_values_are_rejected(self):
        for value in ['line\nnext', 'line\rnext', 'line\x00next']:
            with self.assertRaises(ValueError):
                configure.dotenv_value(value)
