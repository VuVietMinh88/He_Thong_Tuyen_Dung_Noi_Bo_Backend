package vn.ttcs.recruitment.messaging;

import vn.ttcs.recruitment.mail.MailBody;

import java.util.List;

/**
 * Task 532/538: the body of the emails that are only their template, the thanks after an interview and the rejection
 * letter. MailLayout adds the support footer; there is no link and no personal code in them.
 */
public final class CandidateNoticeMailContent {
    private CandidateNoticeMailContent() {
    }

    public static MailBody body(List<String> paragraphs) {
        MailBody.Builder body = MailBody.builder();
        paragraphs.forEach(body::paragraph);
        return body.build();
    }
}
