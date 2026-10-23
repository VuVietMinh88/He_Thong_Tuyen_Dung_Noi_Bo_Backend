package vn.ttcs.recruitment.kanban;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;

import java.util.Objects;

@Service
public class ApplicationBoardPositionsService {
    private final CandidateAccess access;
    private final ApplicationBoardPositionsRepository repository;
    private final TransactionTemplate reads;

    ApplicationBoardPositionsService(CandidateAccess access, ApplicationBoardPositionsRepository repository,
                                     PlatformTransactionManager transactions) {
        this.access = access;
        this.repository = repository;
        this.reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    public ApplicationBoardPositionsView positions(Jwt jwt, int page, int size) {
        CandidateReader reader = access.requireReader(jwt);
        ApplicationBoardQuery query = new ApplicationBoardQuery(null, null, page, size);
        return Objects.requireNonNull(reads.execute(status -> {
            long total = repository.total(reader);
            return new ApplicationBoardPositionsView(page, size, total, query.offset() + size < total,
                    repository.openings(reader, query));
        }));
    }
}
