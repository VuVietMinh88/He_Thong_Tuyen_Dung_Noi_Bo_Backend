-- Task 360 (story S4-09): one candidate profile per person, linked to every application of that person. "The same
-- person" is the same normalized email (V26, Java Locale.ROOT rule); the email is not verified, so the profile keeps
-- every application's own snapshot (name, email, phone, CV) and job_applications keeps its V26 columns: there is still
-- no global email uniqueness on applications. Merging by phone or by hand is S5-01 and moves candidate_applications
-- rows, which is why the link is its own table.
CREATE TABLE candidates (
    id UUID PRIMARY KEY,
    normalized_email VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT candidates_normalized_email_key UNIQUE (normalized_email),
    CONSTRAINT candidates_valid_email CHECK (normalized_email = btrim(normalized_email) AND length(normalized_email) > 0)
);
CREATE TABLE candidate_applications (
    -- CASCADE: an application deleted by hand or on request takes its link with it.
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    -- RESTRICT: a profile with applications never disappears silently.
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    linked_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX candidate_applications_candidate_idx ON candidate_applications(candidate_id);
-- Applications stored before this migration: one profile per normalized email, created at its first application.
INSERT INTO candidates (id, normalized_email, created_at)
SELECT gen_random_uuid(), normalized_email, min(received_at) FROM job_applications GROUP BY normalized_email;
INSERT INTO candidate_applications (application_id, candidate_id, linked_at)
SELECT a.id, c.id, a.received_at FROM job_applications a JOIN candidates c ON c.normalized_email = a.normalized_email;
