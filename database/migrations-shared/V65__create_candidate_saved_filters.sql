-- Task 394 (story S5-05, "Lưu lại được bộ lọc hay dùng"): personal saved candidate-search filters. A filter belongs to
-- exactly one account, the JWT subject that created it, and is never shared. It follows its owner with CASCADE: no API
-- deletes accounts, but a manual cleanup must not be blocked by a user's own settings. criteria is the validated
-- CandidateSearchCriteria as JSON; the scope is applied again every time the filter is used, never stored.
CREATE TABLE candidate_saved_filters (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    -- NFC and Locale.ROOT lower case of name: "Java" and "java" are one name for the same owner.
    normalized_name VARCHAR(160) NOT NULL,
    criteria JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT candidate_saved_filters_name CHECK (name = btrim(name) AND name ~ '[^[:space:]]'),
    CONSTRAINT candidate_saved_filters_normalized_name CHECK (normalized_name ~ '[^[:space:]]'),
    CONSTRAINT candidate_saved_filters_criteria CHECK (jsonb_typeof(criteria) = 'object'
        AND octet_length(criteria::text) <= 8192),
    CONSTRAINT candidate_saved_filters_version CHECK (version >= 0),
    CONSTRAINT candidate_saved_filters_times CHECK (updated_at >= created_at),
    CONSTRAINT candidate_saved_filters_owner_name_key UNIQUE (owner_id, normalized_name)
);
