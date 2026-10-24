package vn.ttcs.recruitment.reporting;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stories S8-05..S8-09: the pure scope and paging rules shared by every report. */
class ReportingPolicyTest {
    private static final UUID ID = UUID.randomUUID();

    @Test
    void aReportNeedsReadGrantsOfReportsRequisitionsAndCandidates() {
        assertThat(ReportViewer.of(ID, Set.of("REPORTS_READ_ALL", "REQUISITIONS_READ_ALL", "CANDIDATES_READ_ALL"))
                .readsReports()).isTrue();
        assertThat(ReportViewer.of(ID, Set.of("REPORTS_READ_SCOPED", "REQUISITIONS_READ_SCOPED", "CANDIDATES_READ_SCOPED"))
                .readsReports()).isTrue();
        assertThat(ReportViewer.of(ID, Set.of("REQUISITIONS_READ_ALL", "CANDIDATES_READ_ALL")).readsReports()).isFalse();
        assertThat(ReportViewer.of(ID, Set.of("REPORTS_READ_ALL", "CANDIDATES_READ_ALL")).readsReports()).isFalse();
        assertThat(ReportViewer.of(ID, Set.of("REPORTS_READ_ALL", "REQUISITIONS_READ_ALL")).readsReports()).isFalse();
        // WRITE never implies READ.
        assertThat(ReportViewer.of(ID, Set.of("REPORTS_WRITE_ALL", "REQUISITIONS_READ_ALL", "CANDIDATES_READ_ALL"))
                .readsReports()).isFalse();
    }

    @Test
    void onlyThreeAllGrantsLiftTheRowScopeAndABroadReportGrantNeverWidensANarrowOne() {
        ReportViewer hr = ReportViewer.of(ID, Set.of("REPORTS_READ_ALL", "REQUISITIONS_READ_ALL", "CANDIDATES_READ_ALL",
                "INTERVIEWS_READ_ALL", "OFFERS_READ_ALL"));
        assertThat(hr.unrestricted()).isTrue();
        assertThat(hr.interviewsUnrestricted()).isTrue();
        assertThat(hr.offersUnrestricted()).isTrue();
        assertThat(hr.scopeName()).isEqualTo("ALL");

        // The seeded APPROVER: REPORTS_READ_ALL, but scoped requisition and candidate reads.
        ReportViewer approver = ReportViewer.of(ID, Set.of("REPORTS_READ_ALL", "REQUISITIONS_READ_SCOPED",
                "CANDIDATES_READ_SCOPED", "OFFERS_READ_SCOPED"));
        assertThat(approver.unrestricted()).isFalse();
        assertThat(approver.scopeName()).isEqualTo("SCOPED");
        assertThat(approver.interviews()).isEqualTo(AccessScope.NONE);

        // The seeded RECRUITER reads every interview, but its report rows stay scoped.
        ReportViewer recruiter = ReportViewer.of(ID, Set.of("REPORTS_READ_SCOPED", "REQUISITIONS_READ_SCOPED",
                "CANDIDATES_READ_SCOPED", "INTERVIEWS_READ_ALL"));
        assertThat(recruiter.interviewsUnrestricted()).isFalse();

        ReportViewer narrowInterviews = ReportViewer.of(ID, Set.of("REPORTS_READ_ALL", "REQUISITIONS_READ_ALL",
                "CANDIDATES_READ_ALL", "INTERVIEWS_READ_SCOPED", "OFFERS_READ_SCOPED"));
        assertThat(narrowInterviews.unrestricted()).isTrue();
        assertThat(narrowInterviews.interviewsUnrestricted()).isFalse();
        assertThat(narrowInterviews.offersUnrestricted()).isFalse();
    }

    @Test
    void pagingIsBoundedAndDefaulted() {
        assertThat(ReportPaging.of(null, null, 10, 50)).isEqualTo(new ReportPaging(0, 10));
        ReportPaging page = ReportPaging.of(3, 20, 10, 50);
        assertThat(page.offset()).isEqualTo(60);
        assertThat(page.totalPages(0)).isZero();
        assertThat(page.totalPages(41)).isEqualTo(3);
        for (int[] invalid : new int[][] {{-1, 10}, {10_001, 10}, {0, 0}, {0, 51}}) {
            assertThatThrownBy(() -> ReportPaging.of(invalid[0], invalid[1], 10, 50))
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
                        assertThat(exception.getFieldErrors()).isNotEmpty();
                    });
        }
    }
}
