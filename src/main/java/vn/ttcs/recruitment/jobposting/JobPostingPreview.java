package vn.ttcs.recruitment.jobposting;

import java.util.UUID;

// Task 309: the authors' and reviewers' preview of a posting in any status. content is the public posting itself
// (PublicJobPostingView.of, the same builder as the portal); status and revision are the editor data around it. HR
// approves and publishes with expectedRevision = revision, so the step applies to exactly the content previewed.
public record JobPostingPreview(UUID id, JobPostingStatus status, int revision, PublicJobPostingView content) { }
