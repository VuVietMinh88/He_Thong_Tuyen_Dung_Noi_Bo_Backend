# Candidate merge APIs

Merge links applications to one candidate group. It does not delete applications, combine pipeline stages,
rewrite submitted or confirmed contacts, change CVs, deduplication keys, tracking codes or queued emails.
The representative application supplies the group's display contact; new recruitment emails still use
the effective contact of the particular application.

## Deployment gate

`app.candidate-merges.enabled` / `CANDIDATE_MERGES_ENABLED` defaults to **false**. Preview, merge and
operation details require this flag. Apply migrations through V32 and complete identity backfill
before opting in. Duplicate detection has a separate existing flag. Enabling either flag does not enable
the public submission API. No environment file or development database is changed by this implementation.
Do not enable merge until the subsequent undo implementation and its review are complete.

## Contract

All routes require an active authenticated session and return `Cache-Control: no-store` on successful responses.
UUIDs are opaque. Caller identity is derived from the session, never from request body fields.

| Method and path | Request | Response |
| --- | --- | --- |
| POST `/api/v1/candidate-merges/preview` | `leftApplicationId`, `rightApplicationId` | Current groups/revisions/representatives, all members with effective contacts, cross-group matches, fingerprint |
| POST `/api/v1/candidate-merges` | `requestKey`, `leftGroupId`, `rightGroupId`, `resultGroupId`, `representativeApplicationId`, `expectedFingerprint` | Operation summary |
| GET `/api/v1/candidate-merges/{id}` | — | Operation summary, including whether undone |

Preview accepts application IDs from duplicate warnings, resolves their **current** memberships, and requires
two different nonempty groups. The result group is one of the two source groups. The chosen representative
must be a member of their union. `expectedFingerprint` is the exact 64-character SHA-256 value from preview;
it binds group revisions, representatives, history heads and every member/contact revision.

Preview reads effective contacts directly, not the background detector cache. Email uses persisted trim/ROOT
lowercase normalization; dots and plus-tags remain significant. Phones are already separator-normalized at
submission/confirmation; `0…` and `+84…` remain different. Unconfirmed OCR is not identity evidence.
At most 200 matched pairs are returned, with `matchesTruncated=true` when more exist; membership is complete.
Matching is advisory. The backend never automatically merges groups or infers transitive identity.

The summary contains only operation ID, result group, representative, merge timestamp, undo deadline and
`undone`; it never exposes raw audit snapshots, actor session data or newly merged group contents.

## Authorization and transactions

Preview/details require current READ scope on every affected application. Merge requires both READ and
WRITE on every affected application. ALL and SCOPED are resolved separately: WRITE does not imply READ.
SCOPED covers current primary/supporting recruiter assignment and, for a hiring manager, their managed
departments and descendants. Merge never grants access to other applications.

Writes lock account, session, actor/request-key advisory lock, canonical UUID-ordered profiles, requisitions,
and applications/memberships. After blocking locks, the service re-reads session validity, grants, role,
managed departments and assignments. Fresh JDBC queries avoid stale ORM account/role snapshots.
READ_COMMITTED writes protect against changes committed while waiting; repeatable-read preview/detail
uses one consistent view. Lock/statement/transaction timeouts fail without partial mutation.

Merge uses the journal's trusted authorization callback both after source locks and on idempotent replay.
The legacy unauthenticated append primitive remains internal/test-only. Controllers never call it directly.
Audit and membership changes commit atomically. No SMTP/OCR/network work is done inside this transaction.

`requestKey` is a client-generated UUID scoped to actor and operation type. The same key and payload returns
the original operation/event, after checking current access to **all** recorded members. A changed payload
is rejected. Deleted members fail closed; replay does not resurrect them or silently authorize a subset.
Replaying an already undone merge reports its original operation with `undone=true`, without merging again.

## Undo

Undo is not exposed by this branch. The existing audit schema preserves the data required by its subsequent implementation.

## Errors

- 400: malformed or invalid request/UUID, invalid representative or equal source groups.
- 401: absent, expired, revoked or inactive session/account.
- 403: missing module permission or any application outside current scope; no member IDs/PII/count disclosed.
- 404: missing preview source or operation detail.
- 409 `CANDIDATE_MERGE_STALE`: preview no longer usable, including a source consumed by a concurrent merge.
- 409 `IDEMPOTENCY_KEY_REUSED`: same actor/key with a different payload.
- 503 `CANDIDATE_MERGE_UNAVAILABLE`: disabled, lock/statement timeout or database failure; no partial save.

The existing generic duplicate warning outside scope remains unchanged; these APIs do not return hidden
member counts or broaden access. Full unified-candidate screens, interview scheduling suppression and
timeline aggregation remain separate integrations.
