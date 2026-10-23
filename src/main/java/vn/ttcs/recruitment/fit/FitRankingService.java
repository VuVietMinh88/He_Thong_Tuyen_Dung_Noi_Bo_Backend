package vn.ttcs.recruitment.fit;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 414 (story S5-09, "Trả danh sách/điểm theo quyền; tính điểm không tự chuyển giai đoạn hoặc tự từ chối"): the
 * ranking suggestion of one requisition, read-only. 401/403 before any lookup (live CANDIDATES read permission), 400 for
 * invalid paging or stages, 404 for an unknown requisition and 403 outside the reader's scope. Then every application
 * of the requisition in the asked stages (by default the active ones: NEW, SCREENING, INTERVIEW, OFFER) is ranked:
 * required criteria met first, then the score, then the earliest submission. A stored result that names the current
 * criteria and qualifications versions is used as it is; a stale or missing one (a criteria save recomputes nothing) is
 * evaluated live from the current data, with the criteria read once, so the ranking is always current. The work is
 * bounded by one requisition. One REPEATABLE READ snapshot with the PROFILE disclosure log of every application shown.
 * Nothing here writes a stage, a result or a decision.
 */
@Service
public class FitRankingService {
    static final List<ApplicationStage> ACTIVE = List.of(ApplicationStage.NEW, ApplicationStage.SCREENING,
            ApplicationStage.INTERVIEW, ApplicationStage.OFFER);
    private static final Comparator<Ranked> ORDER = Comparator
            .comparing((Ranked ranked) -> !ranked.result().requiredMet())
            .thenComparing(ranked -> ranked.result().score() == null ? Integer.MIN_VALUE : ranked.result().score(),
                    Comparator.reverseOrder())
            .thenComparing(ranked -> ranked.row().receivedAt())
            .thenComparing(ranked -> ranked.row().applicationId());
    private final CandidateAccess access;
    private final FitRankingRepository ranking;
    private final RequiredCriteriaRepository criteria;
    private final FitRepository fits;
    private final java.time.Clock clock;
    private final TransactionTemplate reads;

    FitRankingService(CandidateAccess access, FitRankingRepository ranking, RequiredCriteriaRepository criteria,
                      FitRepository fits, java.time.Clock clock, PlatformTransactionManager transactions) {
        this.access = access;
        this.ranking = ranking;
        this.criteria = criteria;
        this.fits = fits;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(10);
    }

    private record Ranked(FitRankingRepository.Row row, FitResult result, Instant evaluatedAt, boolean current) { }

    public FitRankingView rank(Jwt jwt, UUID requisitionId, List<ApplicationStage> stages, boolean requiredMetOnly,
                               int page, int size) {
        CandidateReader reader = access.requireReader(jwt);
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang phải từ 0 đến 1000000; kích thước trang phải từ 1 đến 100.");
        }
        if (stages != null && stages.stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra điều kiện xếp hạng.",
                    Map.of("stage", "Giai đoạn không hợp lệ."));
        }
        List<String> ranked = (stages == null || stages.isEmpty() ? ACTIVE : List.copyOf(new LinkedHashSet<>(stages)))
                .stream().map(Enum::name).toList();
        return Objects.requireNonNull(reads.execute(status -> {
            var opening = ranking.opening(reader, requisitionId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                    "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng."));
            if (!opening.readable()) throw new AccessDeniedException("Requisition is outside candidate read scope");
            RequiredCriteria required = criteria.find(opening.positionId()).orElse(null);
            List<Ranked> all = evaluate(ranking.rows(reader, requisitionId, ranked), opening.positionId(), required);
            List<Ranked> listed = all.stream().filter(item -> !requiredMetOnly || item.result().requiredMet())
                    .sorted(ORDER).toList();
            long offset = (long) page * size;
            List<FitRankingView.Item> items = new ArrayList<>();
            for (long index = offset; index < Math.min(listed.size(), offset + size); index++) {
                Ranked item = listed.get((int) index);
                FitRankingRepository.Row row = item.row();
                items.add(new FitRankingView.Item(index + 1, row.applicationId(), row.candidateId(), row.fullName(),
                        row.stage(), row.stage().displayName(), row.receivedAt(),
                        ApplicationFitView.FitView.of(item.result(), item.evaluatedAt(), item.current())));
            }
            items.stream().collect(Collectors.groupingBy(FitRankingView.Item::candidateId)).forEach((candidate, shown) ->
                    access.recordGranted(reader, CandidateAccessKind.PROFILE, candidate,
                            shown.stream().map(FitRankingView.Item::applicationId).toList()));
            return new FitRankingView(requisitionId, opening.positionId(),
                    RequiredCriteriaView.of(opening.positionId(), required), FitRankingView.ADVICE, items,
                    listed.size(), page, size, offset + items.size() < listed.size());
        }));
    }

    // The stored result when it names the current versions, otherwise a live evaluation (qualifications of the stale
    // rows read in one go).
    private List<Ranked> evaluate(List<FitRankingRepository.Row> rows, UUID positionId, RequiredCriteria required) {
        long criteriaVersion = required == null ? 0 : required.version();
        List<UUID> stale = rows.stream().filter(row -> !current(row, positionId, criteriaVersion))
                .map(FitRankingRepository.Row::applicationId).toList();
        Map<UUID, Qualifications> qualifications = fits.qualificationsOf(stale);
        Instant now = clock.instant();
        return rows.stream().map(row -> current(row, positionId, criteriaVersion)
                ? new Ranked(row, row.stored().result(), row.stored().evaluatedAt(), true)
                : new Ranked(row, FitEvaluator.evaluate(required, qualifications.get(row.applicationId())), now, false))
                .toList();
    }

    private static boolean current(FitRankingRepository.Row row, UUID positionId, long criteriaVersion) {
        return row.stored() != null && row.stored().positionId().equals(positionId)
                && row.stored().criteriaVersion() == criteriaVersion
                && row.stored().qualificationsVersion() == row.qualificationsVersion();
    }
}
