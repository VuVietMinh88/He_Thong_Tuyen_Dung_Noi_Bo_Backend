package vn.ttcs.recruitment.interview;

import java.util.List;
import java.util.UUID;

/** Saved response is replayed for an identical request without scheduling or queuing mail again. */
public record InterviewBulkResult(UUID requestId, List<InterviewView> items) { }
