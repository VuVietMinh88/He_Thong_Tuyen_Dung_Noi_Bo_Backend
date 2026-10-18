package vn.ttcs.recruitment.jobposting;

import java.util.UUID;

// Task 315: one value of a public filter list. Only the id (sent back as the filter) and the name candidates read; no
// code and no active flag.
public record PublicJobPostingFilterOption(UUID id, String name) { }
