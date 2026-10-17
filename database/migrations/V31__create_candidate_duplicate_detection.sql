-- Detection only: each application starts in its own candidate group. Never merge automatically.
CREATE TABLE candidate_profiles (
    id UUID PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE candidate_memberships (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    candidate_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE RESTRICT,
    UNIQUE (candidate_id, application_id)
);

-- Reuse the persisted Locale.ROOT email normalization; PostgreSQL lower() is locale-dependent.
-- No OCR output participates. This view is internal, never an HTTP response.
CREATE VIEW candidate_effective_contacts AS
SELECT a.id AS application_id, a.requisition_id, a.posting_title, a.received_at,
       COALESCE(c.full_name, a.full_name) AS full_name,
       COALESCE(c.email, a.email) AS email,
       COALESCE(c.normalized_email, a.normalized_email) AS normalized_email,
       COALESCE(c.phone, a.phone) AS phone,
       COALESCE(c.revision, 0) AS contact_revision
FROM job_applications a LEFT JOIN application_confirmed_contacts c ON c.application_id = a.id;

CREATE TABLE candidate_contact_index (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    candidate_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE RESTRICT,
    contact_revision BIGINT NOT NULL CHECK (contact_revision >= 0),
    normalizer_version VARCHAR(64) NOT NULL,
    normalized_email VARCHAR(512) NOT NULL CHECK (length(btrim(normalized_email)) > 0),
    phone VARCHAR(16) NOT NULL CHECK (phone ~ '^\+?[0-9]{8,15}$'),
    indexed_at TIMESTAMPTZ NOT NULL
);
-- Non-unique: a match is evidence for a warning, never proof of a shared identity.
CREATE INDEX candidate_contact_index_email_idx ON candidate_contact_index(normalized_email);
CREATE INDEX candidate_contact_index_phone_idx ON candidate_contact_index(phone);

CREATE TABLE candidate_duplicate_pairs (
    left_application_id UUID NOT NULL REFERENCES candidate_contact_index(application_id) ON DELETE CASCADE,
    right_application_id UUID NOT NULL REFERENCES candidate_contact_index(application_id) ON DELETE CASCADE,
    left_revision BIGINT NOT NULL CHECK (left_revision >= 0),
    right_revision BIGINT NOT NULL CHECK (right_revision >= 0),
    email_match BOOLEAN NOT NULL,
    phone_match BOOLEAN NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (left_application_id, right_application_id),
    CHECK (left_application_id < right_application_id),
    CHECK (email_match OR phone_match)
);
CREATE INDEX candidate_duplicate_pairs_right_idx ON candidate_duplicate_pairs(right_application_id);
