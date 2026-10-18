-- Questions asked are historical evidence, independent of later edits/removal in the question bank.
CREATE TABLE interview_question_usage (
    interview_id UUID NOT NULL,
    application_id UUID NOT NULL,
    question_id UUID NOT NULL,
    criterion_id UUID NOT NULL,
    criterion_name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL CHECK (btrim(content) <> ''),
    answer_hint TEXT,
    difficulty VARCHAR(20) NOT NULL CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    round_name VARCHAR(120) NOT NULL,
    interview_starts_at TIMESTAMPTZ NOT NULL,
    asked_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    asked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (interview_id, question_id),
    CONSTRAINT interview_question_usage_application_fk FOREIGN KEY (interview_id, application_id)
        REFERENCES interview_sessions(id, application_id) ON DELETE CASCADE,
    CONSTRAINT interview_question_usage_panel_fk FOREIGN KEY (interview_id, asked_by)
        REFERENCES interview_participants(interview_id, user_id) ON DELETE RESTRICT
);
CREATE INDEX interview_question_usage_history_idx
    ON interview_question_usage(application_id, interview_starts_at, interview_id, question_id);
CREATE FUNCTION reject_interview_question_usage_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'interview_question_usage rows are never changed' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER interview_question_usage_immutable BEFORE UPDATE ON interview_question_usage
    FOR EACH ROW EXECUTE FUNCTION reject_interview_question_usage_change();
