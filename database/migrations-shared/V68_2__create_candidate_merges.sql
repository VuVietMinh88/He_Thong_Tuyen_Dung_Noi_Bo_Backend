-- Task 369 (story S5-01, "Lưu dấu vết gộp và dữ liệu phục vụ hoàn tác"): merging one candidate profile (the source)
-- into another (the target) and everything an undo within the deadline needs to restore. A merge moves the source's
-- candidate_applications rows (V32) to the target; every application keeps its own rows and history.
--
-- A merged candidate keeps its row and says where it went: merged_into and merged_at, both set or both null, never
-- itself. Its normalized email stays unique, so a later application with that email still finds the row and follows
-- merged_into to the target (CandidateLinks). A merge re-points the candidates already merged into its source, so
-- merged_into is always one hop. No ON DELETE action: candidate rows are never deleted while they are referenced.
ALTER TABLE candidates
    ADD COLUMN merged_into UUID REFERENCES candidates(id),
    ADD COLUMN merged_at TIMESTAMPTZ,
    ADD CONSTRAINT candidates_not_merged_into_itself CHECK (merged_into <> id),
    ADD CONSTRAINT candidates_merge_complete CHECK ((merged_into IS NULL) = (merged_at IS NULL));
CREATE INDEX candidates_merged_into_idx ON candidates(merged_into) WHERE merged_into IS NOT NULL;

-- One row per merge: who merged which source into which target, when, why (optional), until when it may be undone,
-- and who undid it and when. undone_at falls before the deadline. A source is merged away at most once at a time:
-- the partial unique index frees it again only when its merge is undone. Staff references stay RESTRICT like every
-- staff trail; the candidates never go away (RESTRICT).
CREATE TABLE candidate_merges (
    id UUID PRIMARY KEY,
    target_candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    source_candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    reason VARCHAR(1000),
    merged_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    merged_at TIMESTAMPTZ NOT NULL,
    undo_deadline TIMESTAMPTZ NOT NULL,
    undone_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    undone_at TIMESTAMPTZ,
    CONSTRAINT candidate_merges_distinct CHECK (target_candidate_id <> source_candidate_id),
    CONSTRAINT candidate_merges_reason CHECK (reason IS NULL OR reason ~ '[^[:space:]]'),
    CONSTRAINT candidate_merges_deadline CHECK (undo_deadline > merged_at),
    CONSTRAINT candidate_merges_undo CHECK ((undone_at IS NULL) = (undone_by IS NULL)
        AND (undone_at IS NULL OR (undone_at >= merged_at AND undone_at < undo_deadline)))
);
CREATE UNIQUE INDEX candidate_merges_live_source_idx ON candidate_merges(source_candidate_id) WHERE undone_at IS NULL;
CREATE INDEX candidate_merges_source_idx ON candidate_merges(source_candidate_id, merged_at);
CREATE INDEX candidate_merges_target_idx ON candidate_merges(target_candidate_id, merged_at);
CREATE INDEX candidate_merges_merged_by_idx ON candidate_merges(merged_by);
CREATE INDEX candidate_merges_undone_by_idx ON candidate_merges(undone_by) WHERE undone_by IS NOT NULL;

-- The applications a merge moved from the source to the target, each with the time it had been linked to the source,
-- so an undo moves exactly these back and restores their link time. Ids only, without a key: an application deleted
-- later (its link cascades, V32) keeps its id in the history and an undo skips it.
CREATE TABLE candidate_merge_applications (
    merge_id UUID NOT NULL REFERENCES candidate_merges(id) ON DELETE RESTRICT,
    application_id UUID NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merge_id, application_id)
);
CREATE INDEX candidate_merge_applications_application_idx ON candidate_merge_applications(application_id);

-- The candidates that had been merged into the source and were re-pointed to the target, each with the time of its
-- own merge, so an undo points them back at the source with that time.
CREATE TABLE candidate_merge_redirects (
    merge_id UUID NOT NULL REFERENCES candidate_merges(id) ON DELETE RESTRICT,
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    merged_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merge_id, candidate_id)
);
CREATE INDEX candidate_merge_redirects_candidate_idx ON candidate_merge_redirects(candidate_id);

-- The only change a merge accepts is its undo, once: undone_by and undone_at filled, every other value as recorded.
-- Nothing deletes a merge or changes its details (like V67_1 and the append-only histories of V20 and V33). Test
-- fixtures empty the tables with TRUNCATE, which fires no row trigger.
CREATE FUNCTION protect_candidate_merge() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.undone_at IS NULL AND NEW.undone_at IS NOT NULL
        AND ROW(NEW.id, NEW.target_candidate_id, NEW.source_candidate_id, NEW.reason, NEW.merged_by, NEW.merged_at,
                NEW.undo_deadline)
            IS NOT DISTINCT FROM ROW(OLD.id, OLD.target_candidate_id, OLD.source_candidate_id, OLD.reason,
                OLD.merged_by, OLD.merged_at, OLD.undo_deadline) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'candidate_merges is append-only: a merge is only ever undone, once' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER candidate_merges_append_only BEFORE UPDATE OR DELETE ON candidate_merges
    FOR EACH ROW EXECUTE FUNCTION protect_candidate_merge();

CREATE FUNCTION reject_candidate_merge_detail_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP USING ERRCODE = '23514';
END $$;
CREATE TRIGGER candidate_merge_applications_append_only BEFORE UPDATE OR DELETE ON candidate_merge_applications
    FOR EACH ROW EXECUTE FUNCTION reject_candidate_merge_detail_change();
CREATE TRIGGER candidate_merge_redirects_append_only BEFORE UPDATE OR DELETE ON candidate_merge_redirects
    FOR EACH ROW EXECUTE FUNCTION reject_candidate_merge_detail_change();
