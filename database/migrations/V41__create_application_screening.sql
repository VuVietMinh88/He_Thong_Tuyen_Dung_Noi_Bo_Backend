-- S5-04: assessments belong to one application, never to the unverified email linking candidate profiles.
ALTER TABLE recruitment_catalog_items DROP CONSTRAINT valid_recruitment_catalog_type;
ALTER TABLE recruitment_catalog_items ADD CONSTRAINT valid_recruitment_catalog_type CHECK (catalog_type IN (
    'CANDIDATE_SOURCE', 'REJECTION_REASON', 'WORK_LOCATION', 'EMPLOYMENT_TYPE', 'CANDIDATE_TAG'
));
-- Composite FK below ensures that a source/rejection reason cannot be attached as a candidate tag.
ALTER TABLE recruitment_catalog_items ADD CONSTRAINT recruitment_catalog_items_id_type_key UNIQUE(id, catalog_type);

CREATE TABLE application_screening_tags (
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL,
    catalog_type VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE_TAG' CHECK (catalog_type = 'CANDIDATE_TAG'),
    added_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(application_id, tag_id),
    FOREIGN KEY(tag_id, catalog_type) REFERENCES recruitment_catalog_items(id, catalog_type) ON DELETE RESTRICT
);
CREATE INDEX application_screening_tags_catalog_idx ON application_screening_tags(tag_id);

-- The latest shared rating is authoritative; this is not an average of staff votes.
CREATE TABLE application_screening_ratings (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    updated_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE application_screening_notes (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    content VARCHAR(2000) NOT NULL CHECK (content ~ '[^[:space:]]'),
    author_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX application_screening_notes_application_idx
    ON application_screening_notes(application_id, created_at DESC, id DESC);
-- Notes have no edit/delete API. SQL cleanup remains possible through the application CASCADE.
CREATE FUNCTION reject_application_screening_note_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'application_screening_notes rows are never changed' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER application_screening_notes_immutable
    BEFORE UPDATE ON application_screening_notes
    FOR EACH ROW EXECUTE FUNCTION reject_application_screening_note_update();
