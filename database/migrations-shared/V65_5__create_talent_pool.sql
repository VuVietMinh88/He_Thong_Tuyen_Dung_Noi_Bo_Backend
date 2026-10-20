-- Task 398 (story S5-06, "Ứng viên bị loại ở vòng cuối được đánh dấu đưa vào kho tiềm năng kèm lý do"): the talent pool.
-- An entry names one candidate profile and the final-stage rejected application that qualified it, with the evidence
-- of that rejection (a REJECT hiring decision, a declined offer, or a rejection after the interview or offer stage in
-- the stage history), the reason and who added it. Entries are never deleted: a removal keeps the row with who removed
-- it and when, and a candidate has at most one active entry. Rows go with their candidate or application (CASCADE);
-- staff references stay RESTRICT like every staff trail.
CREATE TABLE talent_pool_entries (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE CASCADE,
    source_application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    evidence VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    added_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    added_at TIMESTAMPTZ NOT NULL,
    removed_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    removed_at TIMESTAMPTZ,
    CONSTRAINT talent_pool_entries_evidence CHECK (evidence IN ('HIRING_DECISION', 'OFFER_DECLINED',
        'FINAL_STAGE_REJECTION')),
    CONSTRAINT talent_pool_entries_reason CHECK (reason ~ '[^[:space:]]'),
    CONSTRAINT talent_pool_entries_removal CHECK ((removed_at IS NULL) = (removed_by IS NULL)
        AND (removed_at IS NULL OR removed_at >= added_at))
);
CREATE UNIQUE INDEX talent_pool_entries_active_candidate_idx ON talent_pool_entries(candidate_id)
    WHERE removed_at IS NULL;
CREATE INDEX talent_pool_entries_application_idx ON talent_pool_entries(source_application_id);
CREATE INDEX talent_pool_entries_added_by_idx ON talent_pool_entries(added_by);
CREATE INDEX talent_pool_entries_removed_by_idx ON talent_pool_entries(removed_by) WHERE removed_by IS NOT NULL;

-- The job titles a pooled candidate is interested in and the skills noted for them. interest_key is the case- and
-- accent-insensitive form (CandidateLabels.key) that searches and suggestions compare.
CREATE TABLE talent_pool_interests (
    entry_id UUID NOT NULL REFERENCES talent_pool_entries(id) ON DELETE CASCADE,
    kind VARCHAR(8) NOT NULL,
    interest_key VARCHAR(200) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    sort_order SMALLINT NOT NULL,
    PRIMARY KEY (entry_id, kind, interest_key),
    CONSTRAINT talent_pool_interests_kind CHECK (kind IN ('TITLE', 'SKILL')),
    CONSTRAINT talent_pool_interests_display CHECK (display_name = btrim(display_name) AND display_name <> ''),
    CONSTRAINT talent_pool_interests_key CHECK (interest_key = btrim(interest_key) AND interest_key <> ''),
    CONSTRAINT talent_pool_interests_order CHECK (sort_order BETWEEN 0 AND 29)
);
CREATE INDEX talent_pool_interests_lookup_idx ON talent_pool_interests(kind, interest_key);
