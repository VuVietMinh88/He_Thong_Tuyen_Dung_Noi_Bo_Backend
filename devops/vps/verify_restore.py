"""Test pg_dump/pg_restore using a disposable DB inside the PostgreSQL container.

The deployment backup/restore methods are exercised with the exact container's
client tools. The recruitment database is never the target of this probe.
"""
import os
from pathlib import Path
import tempfile
import uuid
from deploy import Deployer, DeploymentError


class RestoreProbe(Deployer):
    def __init__(self, root, database_name):
        super().__init__(root)
        self.database_name = database_name

    def compose(self, *args, **kwargs):
        if args[:3] == ('exec', '-T', 'database'):
            args = ('exec', '-T', '-e', f'POSTGRES_DB={self.database_name}', 'database', *args[3:])
        return super().compose(*args, **kwargs)


def main():
    os.umask(0o077)
    probe = RestoreProbe(Path(__file__).resolve().parent, 'ttcs_restore_probe_' + uuid.uuid4().hex)
    created = False
    try:
        # createdb fails if this unique name already exists: never adopt/drop an existing DB.
        probe.compose('exec', '-T', 'database', 'sh', '-c', 'createdb -U "$POSTGRES_USER" "$POSTGRES_DB"')
        created = True
        probe.compose('exec', '-T', 'database', 'sh', '-c',
            'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 '
            '-c "CREATE TABLE restore_probe(value text); INSERT INTO restore_probe VALUES (\'before\');"')
        with tempfile.TemporaryDirectory(prefix='ttcs-restore-check-') as temp:
            probe.backup_database(Path(temp))
            probe.compose('exec', '-T', 'database', 'sh', '-c',
                'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 '
                '-c "UPDATE restore_probe SET value=\'after\'; CREATE TABLE new_release_only(id int);"')
            probe.restore_database()
        result = probe.compose('exec', '-T', 'database', 'sh', '-c',
            'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v ON_ERROR_STOP=1 '
            '-c "SELECT value FROM restore_probe; SELECT to_regclass(\'public.new_release_only\') IS NULL;"')
        if result.decode().strip().splitlines() != ['before', 't']:
            raise DeploymentError('Restore probe did not recover original rows/schema.')
        print('RESTORE_CHECK_OK: disposable DB recovered; application DB was not changed.')
    finally:
        if created:
            probe.compose('exec', '-T', 'database', 'sh', '-c',
                          'dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"')


if __name__ == '__main__':
    main()
