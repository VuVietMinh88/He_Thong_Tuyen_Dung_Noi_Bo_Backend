package vn.ttcs.recruitment.sla;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.messaging.MessagingActors;
import vn.ttcs.recruitment.messaging.MessagingTransactions;
import vn.ttcs.recruitment.position.WholeVndDeserializer;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 418 (story S5-10): the SLA threshold of each active stage. Everyone who reads candidates may read them (the
 * flags they cause are shown on applications); only CANDIDATES_WRITE_ALL (HR_MANAGER, ADMIN) changes them. A value is
 * a whole number of calendar days from 1 to 365, or null to have no SLA for the stage; a save is guarded by the
 * stage's version. maxDays is required in every save: clearing a stage's SLA takes an explicit null, so a client that
 * forgets the field gets 400 instead of silently removing the SLA.
 */
@Service
public class SlaThresholdService {
    static final int MAX_DAYS = 365;
    private final JdbcTemplate jdbc;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final Clock clock;

    public SlaThresholdService(JdbcTemplate jdbc, MessagingActors actors, MessagingTransactions transactions, Clock clock) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The body of a save. A class, not a record, because a record cannot tell a missing maxDays from an explicit
     * null: Jackson calls setMaxDays for "maxDays": null too, so maxDaysGiven is false only when the field is absent.
     */
    public static final class Update {
        @NotNull @Min(0) private Long expectedVersion;
        @Min(1) @Max(MAX_DAYS) private Long maxDays;
        private boolean maxDaysGiven;

        @JsonDeserialize(using = WholeVndDeserializer.class)
        public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

        @JsonDeserialize(using = WholeVndDeserializer.class)
        public void setMaxDays(Long maxDays) {
            this.maxDays = maxDays;
            this.maxDaysGiven = true;
        }

        public Long expectedVersion() { return expectedVersion; }
        public Long maxDays() { return maxDays; }
        public boolean maxDaysGiven() { return maxDaysGiven; }

        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unsupported field"); }
    }

    public record View(String stage, String stageName, Integer maxDays, long version, Instant updatedAt,
                       UUID updatedBy, String updatedByName) {
    }

    public List<View> list(Jwt jwt) {
        UUID me = actors.reader(jwt).getId();
        AccessScope.read(actors.grants(me), PermissionModule.CANDIDATES).orDeny();
        return views();
    }

    public View update(Jwt jwt, String stageName, Update request) {
        // With the other body checks (@Valid), before anything is locked or read.
        if (!request.maxDaysGiven()) {
            String message = "Vui lòng nhập số ngày tối đa (1–365), hoặc null để bỏ SLA của giai đoạn.";
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of("maxDays", message));
        }
        return transactions.write("SLA_THRESHOLD_BUSY", () -> {
            var actor = actors.lockWriter(jwt);
            actors.requireAll(actor.id(), "CANDIDATES_WRITE_ALL");
            ApplicationStage stage = stage(stageName);
            Long version = jdbc.query("SELECT version FROM stage_sla_thresholds WHERE stage = ? FOR UPDATE",
                    (row, number) -> row.getLong(1), stage.name()).stream().findFirst()
                    .orElseThrow(SlaThresholdService::notFound);
            actors.requireFresh(jwt, actor);
            actors.requireAll(actor.id(), "CANDIDATES_WRITE_ALL");
            if (version != request.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "SLA_THRESHOLD_VERSION_CONFLICT",
                        "Ngưỡng SLA vừa được người khác cập nhật. Vui lòng tải lại.");
            }
            jdbc.update("""
                    UPDATE stage_sla_thresholds SET max_days = ?, version = version + 1, updated_by = ?, updated_at = ?
                    WHERE stage = ?
                    """, request.maxDays() == null ? null : request.maxDays().intValue(), actor.id(),
                    Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)), stage.name());
            return views().stream().filter(view -> view.stage().equals(stage.name())).findFirst().orElseThrow();
        });
    }

    static ApplicationStage stage(String name) {
        for (ApplicationStage stage : ApplicationStage.values()) {
            if (stage.name().equals(name) && stage.active()) return stage;
        }
        throw notFound();
    }

    private List<View> views() {
        return jdbc.query("""
                SELECT t.stage, t.max_days, t.version, t.updated_at, t.updated_by, u.full_name
                FROM stage_sla_thresholds t LEFT JOIN user_accounts u ON u.id = t.updated_by
                ORDER BY CASE t.stage WHEN 'NEW' THEN 1 WHEN 'SCREENING' THEN 2 WHEN 'INTERVIEW' THEN 3 ELSE 4 END
                """, (row, number) -> new View(row.getString("stage"),
                ApplicationStage.valueOf(row.getString("stage")).displayName(),
                row.getObject("max_days") == null ? null : row.getInt("max_days"), row.getLong("version"),
                row.getTimestamp("updated_at").toInstant(), row.getObject("updated_by", UUID.class),
                row.getString("full_name")));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SLA_STAGE_NOT_FOUND",
                "Chỉ cấu hình SLA cho giai đoạn đang xử lý: NEW, SCREENING, INTERVIEW, OFFER.");
    }
}
