package vn.ttcs.recruitment.kanban;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.util.UUID;

/** Pagination applies to each column, not to the combined board. */
record ApplicationBoardQuery(UUID positionId, UUID requisitionId, int page, int size) {
    ApplicationBoardQuery {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang phải từ 0 đến 1000000; kích thước trang phải từ 1 đến 100.");
        }
    }

    long offset() { return (long) page * size; }
    boolean usesDefault() { return positionId == null && requisitionId == null; }
}
