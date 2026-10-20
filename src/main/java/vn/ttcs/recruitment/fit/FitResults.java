package vn.ttcs.recruitment.fit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 413 (story S5-09): writes application_fit_results. A stored result names the criteria version and the
 * qualifications version it was computed from, and a newer computation never loses to an older one
 * (FitRepository.storeResult). No lock is taken here: a criteria save that commits meanwhile only makes a row stale.
 * Readers compare the versions and evaluate live when a row is stale (ApplicationFitService, FitRankingService), and
 * FitRefreshWorker rewrites stale rows in small batches outside any position lock, so a criteria save never does work
 * proportional to the number of applications. Nothing here reads or changes a stage.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class FitResults {
    private final FitRepository fits;
    private final RequiredCriteriaRepository criteria;

    FitResults(FitRepository fits, RequiredCriteriaRepository criteria) {
        this.fits = fits;
        this.criteria = criteria;
    }

    // The application's fit from the criteria and qualifications as they are now, stored with their versions.
    FitRepository.StoredFit evaluate(UUID applicationId, Instant now) {
        UUID position = fits.positionOf(applicationId).orElseThrow();
        RequiredCriteria required = criteria.find(position).orElse(null);
        Qualifications qualifications = fits.qualifications(applicationId).orElse(null);
        FitResult result = FitEvaluator.evaluate(required, qualifications);
        long criteriaVersion = required == null ? 0 : required.version();
        long qualificationsVersion = qualifications == null ? 0 : qualifications.version();
        fits.storeResult(applicationId, position, criteriaVersion, qualificationsVersion, result, now);
        return new FitRepository.StoredFit(position, criteriaVersion, qualificationsVersion, result, now);
    }
}
