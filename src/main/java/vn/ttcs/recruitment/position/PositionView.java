package vn.ttcs.recruitment.position;

import java.time.Instant;
import java.util.UUID;

public record PositionView(UUID id, String code, String name, String level, long salaryMin, long salaryMax,
                           boolean active, Instant createdAt, Instant updatedAt) {

    static PositionView from(Position position) {
        return new PositionView(position.getId(), position.getCode(), position.getName(), position.getLevel(),
                position.getSalaryMin(), position.getSalaryMax(), position.isActive(),
                position.getCreatedAt(), position.getUpdatedAt());
    }
}
