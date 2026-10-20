# Shared migrations from V55 onwards

The two immutable histories converge to the same business schema through their bridges:
`migrations` (S5 through V45) and `migrations-feedback` (feedback through V54).

V55 adds interview schedules, panels and events. V56 adds the evaluation form snapshot/event schema.
V57–V58 add interview invitations and delivery state. V59 stores snapshots of questions marked as asked;
V60 stores immutable hiring decisions and the submitted evaluation evidence used for each decision.
See `docs/api/interviews.md`, `interview-evaluations.md`, `interview-question-usage.md` and
`hiring-decisions.md` for their contracts.

Candidate extensions (`docs/api/candidate-extensions.md`): V65 stores personal saved candidate-search filters; V65_1 records who entered an application by hand; V65_2 indexes application phones in their national form for the manual duplicate check; V65_3 stores the required criteria (experience, education, skills) of a position; V65_4 stores the qualifications staff record for an application and its fit result; V65_5 stores the talent pool (entries with evidence, reason, title interests and skills).

V66 and its V66_x follow-ups hold candidate email templates, the email log, in-app notifications,
notification preferences and stage SLA (`docs/api/messaging.md`).

Reporting (stories S8-05..S8-09, `docs/api/reporting.md`): V67 adds the indexes of the dashboard's bounded windows
(applications by receipt time, open requisitions by needed-by date, closure events by time, sent offers by deadline).
V67_1 adds `recruitment_costs`, the hand-entered advertising and referral-bonus costs per requisition: entries are
never edited (trigger), only voided once with who, when and why.

Add the next migration here as **V61** or later. Do not reuse V46–V54 for S5, edit old scripts,
or copy future migrations into both legacy directories. Runtime loads this directory together with exactly one
legacy directory, selected by `APP_MIGRATION_LINEAGE`.

This file keeps the shared classpath location present before its first SQL migration. Flyway ignores Markdown.
See `docs/database/migration-lineages.md` for upgrading an existing database.
