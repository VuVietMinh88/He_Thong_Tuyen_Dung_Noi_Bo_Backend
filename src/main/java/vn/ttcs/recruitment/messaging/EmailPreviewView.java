package vn.ttcs.recruitment.messaging;

import java.util.UUID;

/**
 * Task 532: what the candidate of applicationId would receive. text is the plain-text part; html the HTML part of the
 * emails that have one (null for APPLICATION_RECEIPT, a plain-text email). Personal links and codes are replaced by
 * bracketed placeholders, and offer terms by a note: a preview never contains a secret or a salary.
 */
public record EmailPreviewView(String type, UUID applicationId, UUID interviewId, boolean draft, boolean autoSend,
                               long templateVersion, String subject, String text, String html) {
}
