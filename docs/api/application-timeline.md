# Application timeline

Tasks TKNHTTDNB1-403 and TKNHTTDNB1-404, story S5-07 (TKNHTTDNB1-55).

`GET /api/v1/applications/{id}/timeline?page=0&size=20`

Requires a live staff bearer session and `CANDIDATES_READ_ALL` or
`CANDIDATES_READ_SCOPED`. A scoped reader must currently be assigned to the
application's requisition, or manage its department (including an ancestor),
as defined by `CandidateAccess`. The response concerns exactly one application;
sharing a candidate or email address never grants access to another application.

The response has `applicationId`, `items`, `totalElements`, `page` and `size`.
Page numbering starts at zero. Page must be 0–1000000 and size 1–100; defaults
are 0 and 20. A page beyond the last item is empty but retains the total count.
Items are ordered by `occurredAt DESC`, then the stable source-prefixed `id ASC`.
The count, page and record scope use one database snapshot. Separate requests
can observe newly added events, so offset pages can shift while data changes.

Each item has `id`, `type`, `applicationId`, `occurredAt`, `actor` and `details`.
The `actor` has `type` (`CANDIDATE`, `STAFF` or `SYSTEM`), `id` and `displayName`.
Candidate/system identities have null IDs and names. Times use UTC ISO 8601.
For a referral, the submission actor is the staff member recorded as its
referrer; a directly submitted application has a candidate actor. The referral
note is not exposed by this endpoint.
The event ID prefixes (`submission:`, `stage:`, `email:`, `notice:`, `note:`,
`rating:`) distinguish identical UUIDs from different sources.

| Type | Source and meaning |
| --- | --- |
| `APPLICATION_SUBMITTED` | The application's stored receipt time; one event per application. |
| `STAGE_CHANGED` | Immutable staff changes and candidate withdrawals, with their recorded details. |
| `EMAIL_SENT` | Confirmation or withdrawal notice with `status=SENT` and a recorded `sent_at`. |
| `SCREENING_NOTE_ADDED` | An append-only internal screening note with its author and time. |
| `SCREENING_RATING_SNAPSHOT` | The current shared screening score and its last update time. |

`details` depends on `type`; consumers should branch on that field:

| Type | `details` fields |
| --- | --- |
| `APPLICATION_SUBMITTED` | `postingTitle` |
| `STAGE_CHANGED` | `previousStage`, `stage`, `reason`, `rejectionReasonId`, `rejectionReasonName`, `interviewSkipped` |
| `EMAIL_SENT` | `mail` (`APPLICATION_CONFIRMATION`, `CANDIDATE_WITHDRAWAL`, `RECRUITER_WITHDRAWAL`), `recipient` (`type`, `id`, `displayName`) |
| `SCREENING_NOTE_ADDED` | `content` |
| `SCREENING_RATING_SNAPSHOT` | `value` (integer 1–5) |

For example, the note item below belongs only to the requested application:

```json
{
  "applicationId": "11111111-1111-1111-1111-111111111111",
  "items": [{
    "id": "note:22222222-2222-2222-2222-222222222222",
    "type": "SCREENING_NOTE_ADDED",
    "applicationId": "11111111-1111-1111-1111-111111111111",
    "occurredAt": "2026-10-18T07:00:00Z",
    "actor": {"type": "STAFF", "id": "33333333-3333-3333-3333-333333333333", "displayName": "Nguyễn Văn A"},
    "details": {"content": "Cần hỏi thêm về kinh nghiệm Java."}
  }],
  "totalElements": 2,
  "page": 0,
  "size": 1
}
```

Email events show a send acknowledgement recorded after SMTP accepted the
message, not proof of delivery or reading. Queued, retrying and failed messages
are excluded. Encrypted bodies, tracking credentials, message IDs and recipient
addresses are never returned. Staff display names are current account names,
not historical name snapshots. Note text and stage-change reasons are internal
data; frontend consumers must render them as text.

Screening ratings overwrite their previous value in the existing source table.
The timeline therefore labels the one surviving value as a snapshot. It does
not reconstruct old scores or treat that score as an interview evaluation.
Tags are current selections and are not presented as historical events.

Interview scheduling and interview evaluation modules are not implemented in
this backend yet. Their events must be integrated when those source modules
exist. Entering the `INTERVIEW` stage does not mean an interview was scheduled
or held. This delivery does not complete those two source requirements of
the parent story, and introduces no fabricated events.

Successful reads return `Cache-Control: no-store` and write a `PROFILE` access
audit for the disclosed application in the same transaction. Existing records
outside the reader's scope return 403 and write a denied audit separately.
Anonymous, invalid or revoked sessions return 401; missing read permission
returns 403 before record lookup; an unknown application returns 404.
Invalid pagination or a malformed UUID returns 400.

The route is read-only. No candidate-facing or referrer endpoint gains access
to this internal DTO. No SQL migration, email send, or background worker is
introduced; both existing Flyway lineages retain their histories unchanged.
