-- Interview question bank (Jira TKNHTTDNB1-220, story S2-07). Each question belongs to exactly one criterion of a
-- competency framework (V8), so interviewers find the questions of a position through
-- positions.competency_framework_id -> competency_criteria.framework_id -> interview_questions.criterion_id.
-- Content and answer hint are free text that may span several lines, so they are TEXT. The API (later tasks) sets
-- their maximum length. Repeated content is allowed on purpose: there is no UNIQUE on content because B-tree index
-- entries are limited to about 2.7 KB and TEXT is unbounded here. Duplicate checks, if wanted, belong to the API
-- (task 222) or to a later unique index on md5(content).
-- A question that is no longer used is kept with active = FALSE instead of being deleted, so it stays as history.
CREATE TABLE interview_questions (
    id UUID PRIMARY KEY,
    -- RESTRICT: a criterion that still has questions (active or not) cannot be deleted. Deleting it would either
    -- lose the questions (CASCADE) or leave them without a criterion, which the story does not allow.
    criterion_id UUID NOT NULL REFERENCES competency_criteria(id) ON DELETE RESTRICT,
    content TEXT NOT NULL,
    difficulty VARCHAR(20) NOT NULL,
    -- What a good answer should contain, shown to the interviewer. Optional.
    answer_hint TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_interview_question_content CHECK (
        content <> '' AND content !~ '^[[:space:]]|[[:space:]]$'
    ),
    CONSTRAINT valid_interview_question_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT valid_interview_question_answer_hint CHECK (
        answer_hint IS NULL OR (answer_hint <> '' AND answer_hint !~ '^[[:space:]]|[[:space:]]$')
    )
);

-- Filters questions by criterion, and lets PostgreSQL check the foreign key quickly when a criterion is deleted.
CREATE INDEX interview_questions_criterion_id_idx ON interview_questions(criterion_id);
