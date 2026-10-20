# Shared migrations from V55 onwards

The two immutable histories converge to the same business schema through their bridges:
`migrations` (S5 through V45) and `migrations-feedback` (feedback through V54).

Add every future migration here, starting with **V55**. Do not reuse V46–V54 for S5, edit old scripts,
or copy future migrations into both legacy directories. Runtime loads this directory together with exactly one
legacy directory, selected by `APP_MIGRATION_LINEAGE`.

This file keeps the shared classpath location present before its first SQL migration. Flyway ignores Markdown.
See `docs/database/migration-lineages.md` for upgrading an existing database.
