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
`rating:`, `interview:`, `evaluation:`) distinguish identical UUIDs from different sources.

| Type | Source and meaning |
| --- | --- |
| `APPLICATION_SUBMITTED` | The application's stored receipt time; one event per application. |
| `STAGE_CHANGED` | Immutable staff changes and candidate withdrawals, with their recorded details. |
| `EMAIL_SENT` | Confirmation or withdrawal notice with `status=SENT` and a recorded `sent_at`. |
| `SCREENING_NOTE_ADDED` | An append-only internal screening note with its author and time. |
| `SCREENING_RATING_SNAPSHOT` | The current shared screening score and its last update time. |
| `INTERVIEW_SCHEDULED` | An interview was created; the round name and times are the values recorded then. |
| `INTERVIEW_RESCHEDULED` | An interview was edited; the round name and times are the resulting recorded values. |
| `INTERVIEW_CANCELLED` | An interview was cancelled; its recorded round name and times remain visible. |
| `EVALUATION_SUBMITTED` | An evaluator submitted a form; only its IDs, actor and event time appear. |
| `EVALUATION_REOPENED` | A submitted form was reopened; this does not reveal its current contents or status. |

`details` depends on `type`; consumers should branch on that field:

| Type | `details` fields |
| --- | --- |
| `APPLICATION_SUBMITTED` | `postingTitle` |
| `STAGE_CHANGED` | `previousStage`, `stage`, `reason`, `rejectionReasonId`, `rejectionReasonName`, `interviewSkipped` |
| `EMAIL_SENT` | `mail` (`APPLICATION_CONFIRMATION`, `CANDIDATE_WITHDRAWAL`, `RECRUITER_WITHDRAWAL`), `recipient` (`type`, `id`, `displayName`) |
| `SCREENING_NOTE_ADDED` | `content` |
| `SCREENING_RATING_SNAPSHOT` | `value` (integer 1–5) |
| `INTERVIEW_SCHEDULED`, `INTERVIEW_RESCHEDULED`, `INTERVIEW_CANCELLED` | `interviewId`, `roundName`, `startsAt`, `endsAt` |
| `EVALUATION_SUBMITTED`, `EVALUATION_REOPENED` | `interviewId`, `formId` |

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

Interview and evaluation events come from the append-only `interview_events`
and `evaluation_events` tables (shared migrations V55/V56). Editing a schedule
does not rewrite a previous event's round name or times. Entering the `INTERVIEW`
stage alone never creates a scheduling event. Creating or saving a draft
evaluation produces no timeline event. Submission and reopening events remain
as history even if the form is later reopened or submitted again.

These events expose metadata only. The timeline does not return interview
rooms, meeting links, cancellation/edit reasons, evaluation scores, comments,
conclusions or peer draft state. A `formId` is a reference, not permission to
open the form: the separate evaluation API checks panel membership and its own
visibility rules. Current staff display names still apply to event actors.

Successful reads return `Cache-Control: no-store` and write a `PROFILE` access
audit for the disclosed application in the same transaction. Existing records
outside the reader's scope return 403 and write a denied audit separately.
Anonymous, invalid or revoked sessions return 401; missing read permission
returns 403 before record lookup; an unknown application returns 404.
Invalid pagination or a malformed UUID returns 400.

The route is read-only. No candidate-facing or referrer endpoint gains access
to this internal DTO. Reading it sends no email and starts no worker. The new
source modules add shared migrations V55/V56; both existing Flyway lineages
retain their historical SQL unchanged.
