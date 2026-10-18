package vn.ttcs.recruitment.jobposting;

// Task 303: text rules shared by the job posting request bodies.
final class JobPostingText {
    // PostgreSQL TEXT cannot store the NUL character (code 0, sometimes pasted from other files), so the insert would
    // fail with a 500. Every other character is allowed, including tabs and line breaks. @Pattern skips null.
    // A copy of RequisitionRequest.NO_NUL_CHARACTER, which is private to the requisition package.
    static final String NO_NUL_CHARACTER = "[^\\x00]*";

    private JobPostingText() {
    }
}
