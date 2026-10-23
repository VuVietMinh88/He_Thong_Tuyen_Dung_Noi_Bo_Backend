package vn.ttcs.recruitment.candidatesearch;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionTimedOutException;
import vn.ttcs.recruitment.common.ApiException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateSearchBusyTest {
    @Test
    void timeoutsLockWaitsAndDeadlocksAreBusy() {
        assertThat(CandidateSearchService.busy(new QueryTimeoutException("timeout"))).isTrue();
        assertThat(CandidateSearchService.busy(new TransactionTimedOutException("deadline"))).isTrue();
        for (String state : new String[] {"57014", "55P03", "40P01", "40001"}) {
            assertThat(CandidateSearchService.busy(new UncategorizedSQLException("search", "SELECT",
                    new SQLException("busy", state)))).as(state).isTrue();
        }
        assertThat(CandidateSearchService.busy(new UncategorizedSQLException("search", "SELECT",
                new SQLException("syntax", "42601")))).isFalse();
        assertThat(CandidateSearchService.busy(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void aBusySearchIsAnswered503WithoutDetail() {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        assertThatThrownBy(() -> CandidateSearchService.searching(transactions, status -> {
            throw new QueryTimeoutException("canceling statement due to statement timeout");
        })).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus().value()).isEqualTo(503);
            assertThat(error.getCode()).isEqualTo("CANDIDATE_SEARCH_BUSY");
            assertThat(error.getMessage()).doesNotContain("statement");
        });
        assertThatThrownBy(() -> CandidateSearchService.searching(transactions, status -> {
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class);
    }
}
