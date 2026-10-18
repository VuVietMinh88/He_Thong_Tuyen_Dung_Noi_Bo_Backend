package vn.ttcs.recruitment.kanban;

import java.util.List;
import java.util.UUID;

/** A filter option identifies a particular opening as well as its reusable catalog position. */
public record ApplicationBoardPositionsView(int page, int size, long total, boolean hasNext, List<Opening> items) {
    public record Opening(UUID requisitionId, UUID positionId, String positionName, UUID departmentId,
                          String departmentName, boolean assignedToMe, boolean defaultSelected) { }
}
