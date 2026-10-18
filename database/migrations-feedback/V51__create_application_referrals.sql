-- Task 350 (story S4-07): an application a staff member submitted for a candidate ("Giới thiệu nội bộ"): who referred,
-- the referral note and when the referrer declared the candidate's consent, one row per application, written in the
-- application's transaction. The label "Giới thiệu nội bộ" follows from this row, not from the CANDIDATE_SOURCE
-- catalog (V10): HR may rename or deactivate catalog values, and a source chosen on the public form is self-declared.
-- The referred application's source_id stays empty.
CREATE TABLE application_referrals (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    -- RESTRICT like every staff reference: no API deletes accounts.
    referrer_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    note VARCHAR(2000) NOT NULL,
    -- Task 351 refuses a referral without the declaration; the time is the application's receipt.
    consent_declared_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_referrals_visible_note CHECK (note ~ '[^[:space:]]')
);
-- Serves "my referrals" (task 352) and the RESTRICT check.
CREATE INDEX application_referrals_referrer_idx ON application_referrals(referrer_id);
