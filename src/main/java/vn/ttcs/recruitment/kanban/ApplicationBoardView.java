package vn.ttcs.recruitment.kanban;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApplicationBoardView(UUID positionId, UUID requisitionId, boolean assignedOnly,
                                   int page, int size, List<Column> columns) {
    public record Column(ApplicationStage stage, String stageName, long total, boolean hasNext, List<Card> cards) { }

    // A card discloses just enough profile data to identify an application. Details use the existing profile API.
    public record Card(UUID applicationId, UUID candidateId, UUID requisitionId, UUID positionId,
                       String fullName, String jobTitle, Instant receivedAt, ApplicationStage stage) { }
}
