package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Task 308: the comment of a submission or a decision counts as none when it has no visible character
// (JobPostingText.invisibleToNull), so it is never stored and REQUEST_REVISION asks for one; a comment with a visible
// character is kept exactly as sent. The posting texts keep blankToNull (JobPostingRequestTest).
class JobPostingCommentTest {

    @Test
    void aCommentWithoutAnyVisibleCharacterIsNone() {
        // Spaces and line breaks (Java and Unicode, non-breaking ones included), U+0085 and U+180E, which PostgreSQL
        // counts as spaces, zero-width and other format characters, and control characters: a comment made only of
        // NUL counts as none too, instead of the NUL error.
        List<String> invisible = List.of("", codePoints(' ', ' '), codePoints('\n', '\r', '\t'), codePoints(0x00A0),
                codePoints(0x2007), codePoints(0x202F), codePoints(0x3000), codePoints(0x2028), codePoints(0x0085),
                codePoints(0x180E), codePoints(0x200B), codePoints(0xFEFF), codePoints(0x2060), codePoints(0x0000),
                codePoints(0x00A0, 0x200B, '\n', 0x0085, 0x202F));
        for (String comment : invisible) {
            String shown = comment.codePoints().mapToObj(Integer::toHexString).toList().toString();
            assertThat(new JobPostingSubmitRequest(1, comment).comment()).as(shown).isNull();
            assertThat(new JobPostingDecisionRequest("REQUEST_REVISION", comment, 2).comment()).as(shown).isNull();
        }
        assertThat(new JobPostingSubmitRequest(1, null).comment()).isNull();
        assertThat(new JobPostingDecisionRequest("APPROVE", null, 2).comment()).isNull();
    }

    @Test
    void aCommentWithAVisibleCharacterIsKeptAsSent() {
        String comment = codePoints(0x00A0) + "  Bổ sung quyền lợi.\r\n\tGhi rõ bảo hiểm.\n" + codePoints(0x200B);
        // One visible character is enough, also outside the Basic Multilingual Plane (an emoji is a surrogate pair).
        String emoji = codePoints(0x00A0, 0x1F44D, 0x200B);

        for (String kept : List.of(comment, emoji, ".")) {
            assertThat(new JobPostingSubmitRequest(1, kept).comment()).isEqualTo(kept);
            assertThat(new JobPostingDecisionRequest("APPROVE", kept, 2).comment()).isEqualTo(kept);
        }
    }

    private static String codePoints(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }
}
