package vn.ttcs.recruitment.fit;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.application.ApplicationStoredEvent;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * Task 413 (story S5-09, "Hồ sơ mới được gắn điểm phù hợp"): every new application, whatever its path (public form,
 * referral, manual intake), gets its fit result in the transaction that stores it. A new application has no recorded
 * qualifications yet, so its configured criteria are UNKNOWN (score 0) until a staff member records them; a position
 * without criteria gives NO_CRITERIA. The listener runs synchronously inside ApplicationStorageService.store.
 */
@Component
class ApplicationFitHook {
    private final FitResults results;
    private final Clock clock;

    ApplicationFitHook(FitResults results, Clock clock) {
        this.results = results;
        this.clock = clock;
    }

    @EventListener
    void stored(ApplicationStoredEvent event) {
        results.evaluate(event.applicationId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
