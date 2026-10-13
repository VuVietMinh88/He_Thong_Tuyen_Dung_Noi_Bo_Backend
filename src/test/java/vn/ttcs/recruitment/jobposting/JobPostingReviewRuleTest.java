package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 308: nobody approves a job posting they wrote, edited or sent for review last.
class JobPostingReviewRuleTest {
    private final UUID creator = UUID.randomUUID();
    private final UUID editor = UUID.randomUUID();
    private final UUID earlierSubmitter = UUID.randomUUID();
    private final UUID latestSubmitter = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    // The creator is always an author; the editor became one by saving the posting.
    private final Set<UUID> authors = Set.of(creator, editor);

    @Test
    void theAuthorsAndTheLatestSubmitterAreRefused() {
        assertThat(JobPostingReviewRule.mayApprove(creator, authors, latestSubmitter)).isFalse();
        // An author who only edited, for example HR fixing the text after asking for changes, stays refused.
        assertThat(JobPostingReviewRule.mayApprove(editor, authors, latestSubmitter)).isFalse();
        assertThat(JobPostingReviewRule.mayApprove(latestSubmitter, authors, latestSubmitter)).isFalse();
        // The creator is refused even when someone else submitted.
        assertThat(JobPostingReviewRule.mayApprove(creator, authors, reviewer)).isFalse();
    }

    @Test
    void othersMayApprove() {
        // An earlier submitter who never wrote the content: only the latest submission counts.
        assertThat(JobPostingReviewRule.mayApprove(earlierSubmitter, authors, latestSubmitter)).isTrue();
        // A reviewer who only asked for a revision, or who will publish, is not an author.
        assertThat(JobPostingReviewRule.mayApprove(reviewer, authors, latestSubmitter)).isTrue();
        // Before the first submission there is no submitter.
        assertThat(JobPostingReviewRule.mayApprove(reviewer, authors, null)).isTrue();
    }
}
