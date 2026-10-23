package vn.ttcs.recruitment.kanban;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ApplicationBoardService {
    private static final List<ApplicationStage> STAGES = List.of(ApplicationStage.NEW, ApplicationStage.SCREENING,
            ApplicationStage.INTERVIEW, ApplicationStage.OFFER, ApplicationStage.HIRED, ApplicationStage.ONBOARDED,
            ApplicationStage.REJECTED);
    private final CandidateAccess access;
    private final ApplicationBoardRepository repository;
    private final TransactionTemplate reads;

    ApplicationBoardService(CandidateAccess access, ApplicationBoardRepository repository,
                            PlatformTransactionManager transactions) {
        this.access = access;
        this.repository = repository;
        this.reads = new TransactionTemplate(transactions);
        // Count, page, assignment scope and the disclosure audit belong to one consistent snapshot.
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ApplicationBoardView board(Jwt jwt, UUID positionId, UUID requisitionId, int page, int size) {
        CandidateReader reader = access.requireReader(jwt);
        ApplicationBoardQuery query = new ApplicationBoardQuery(positionId, requisitionId, page, size);
        return Objects.requireNonNull(reads.execute(status -> {
            requireFilters(reader, query);
            boolean assignedOnly = query.usesDefault() && reader.scope() == AccessScope.SCOPED;
            Map<ApplicationStage, Long> totals = repository.totals(reader, query, assignedOnly);
            List<ApplicationBoardView.Card> cards = repository.cards(reader, query, assignedOnly);
            // PROFILE means these candidate names/ids were disclosed. Counts and empty pages disclose no profile.
            cards.stream().collect(Collectors.groupingBy(ApplicationBoardView.Card::candidateId)).forEach(
                    (candidate, shown) -> access.recordGranted(reader, CandidateAccessKind.PROFILE, candidate,
                            shown.stream().map(ApplicationBoardView.Card::applicationId).toList()));
            List<ApplicationBoardView.Column> columns = STAGES.stream().map(stage -> {
                long total = totals.getOrDefault(stage, 0L);
                return new ApplicationBoardView.Column(stage, stage.displayName(), total,
                        query.offset() + size < total, cards.stream().filter(card -> card.stage() == stage).toList());
            }).toList();
            return new ApplicationBoardView(positionId, requisitionId, assignedOnly, page, size, columns);
        }));
    }

    private void requireFilters(CandidateReader reader, ApplicationBoardQuery query) {
        if (query.requisitionId() != null) {
            boolean readable = repository.readableOpening(reader, query.requisitionId()).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng."));
            if (!readable) throw new AccessDeniedException("Opening is outside candidate read scope");
        }
        if (query.positionId() != null) {
            if (!repository.positionExists(query.positionId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Không tìm thấy vị trí tuyển dụng.");
            }
            if (reader.scope() != AccessScope.ALL && !repository.hasReadablePosition(reader, query.positionId())) {
                throw new AccessDeniedException("No readable opening for this position");
            }
        }
    }
}
