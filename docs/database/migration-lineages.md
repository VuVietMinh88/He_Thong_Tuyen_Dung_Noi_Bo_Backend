# Migration histories during candidate integration

Set `APP_MIGRATION_LINEAGE=feedback` for existing develop databases where V26 unpublishes postings and V27 expires them. Set `s5` for candidate databases where V26 creates applications and V27 records IP quotas. Never switch an applied history or edit its SQL. New databases default to s5. Both paths preserve their original scripts and append byte-identical bridges for the other features.

At this checkpoint feedback adds application/CV, intake quota and confirmation outbox as V42–V44. S5 adds unpublish/expiry as V42–V43. Subsequent candidate subtasks add their own migrations and corresponding feedback bridges. Always back up a real database and rehearse its upgrade separately; publication does not authorize production migration.

## PR #130: CV extraction and confirmed contacts

On 2026-10-21 the team confirmed that the proposed V29/V30 CV scripts have not been applied to **any** database. A read-only check of the VPS found the S5 history through V56, 46 successful rows, with no V29/V30. These unpublished migrations therefore move, with SQL bytes unchanged, to shared V59 (extraction jobs/attempts) and V60 (confirmed contacts/history). Existing applied migrations and checksums are not edited. Both lineage locations include these shared scripts, after applications, CVs and receipt tables exist.

Versions 46–54 are already reserved by the candidate feedback bridges on the ordered Backend branches; shared 55–58 are already reserved by interviews/invitations. Do not allocate those numbers to the CV feature. Reserve 59/60 for this PR and coordinate other open branches before merging or releasing.

**Release ordering matters:** before deploying 59/60 to a persistent database, integrate every intended predecessor migration (including 57/58 if that invitation release is intended) or move any still-unapplied predecessor feature to newly coordinated versions above the deployed maximum. Otherwise a later release containing previously absent migrations below 60 will correctly fail validation. Do not enable outOfOrder, ignore missing/ignored migrations, baseline/repair history, or recreate application data to bypass this check.

This PR alone is not an artifact for the current VPS: it does not yet contain every historical migration currently applied there (for example the candidate/interview layers through V56). Integrate/review the intended release first and compare its complete migration inventory to the real history. Merge readiness of the source change is separate from approval to deploy that release.

Tests cover fresh S5/feedback databases, appending 59/60 to each predecessor history available in this branch without changing prior checksums or user/permission rows, and upgrading 59 to 60 while preserving submitted applications, original CV bytes, extraction jobs and encrypted receipts. They do **not** claim to rehearse the complete VPS V56 schema. The user confirmation about V29/V30 is a prerequisite; if another environment is later discovered with either script applied, stop and reconcile that evidence before deployment.
