package vn.ttcs.recruitment.application;

/**
 * Task 346 (story S4-06): the emails sent after a candidate withdraws (V35 application_notice_outbox.kind, CHECK
 * application_notice_outbox_kind). Every switch on it is exhaustive without default, so a new kind does not compile
 * until someone decides who receives it and what it may say.
 */
enum ApplicationNoticeKind {
    // To the requisition's PRIMARY recruiter, grouped per (recipient, requisition); never quotes applicant text.
    RECRUITER_WITHDRAWAL,
    // To the application's own address; no code, no reason, no link.
    CANDIDATE_WITHDRAWAL
}
