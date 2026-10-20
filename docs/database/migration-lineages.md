# Migration histories during candidate integration

Set `APP_MIGRATION_LINEAGE=feedback` for existing develop databases where V26 unpublishes postings and V27 expires them. Set `s5` for candidate databases where V26 creates applications and V27 records IP quotas. Never switch an applied history or edit its SQL. New databases default to s5. Both paths preserve their original scripts and append byte-identical bridges for the other features.

At this checkpoint feedback adds application/CV, intake quota and confirmation outbox as V42–V44. S5 adds unpublish/expiry as V42–V43. Subsequent candidate subtasks add their own migrations and corresponding feedback bridges. Always back up a real database and rehearse its upgrade separately; publication does not authorize production migration.
