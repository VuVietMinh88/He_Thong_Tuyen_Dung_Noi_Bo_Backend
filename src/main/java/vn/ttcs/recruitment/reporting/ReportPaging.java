package vn.ttcs.recruitment.reporting;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded page of a report list: page 0..10000, size 1..maxSize. */
record ReportPaging(int page, int size) {
    static final int MAX_PAGE = 10_000;

    static ReportPaging of(Integer page, Integer size, int defaultSize, int maxSize) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? defaultSize : size;
        Map<String, String> errors = new LinkedHashMap<>();
        if (resolvedPage < 0 || resolvedPage > MAX_PAGE) {
            errors.put("page", "Trang phải từ 0 đến " + MAX_PAGE + ".");
        }
        if (resolvedSize < 1 || resolvedSize > maxSize) {
            errors.put("size", "Số dòng mỗi trang phải từ 1 đến " + maxSize + ".");
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return new ReportPaging(resolvedPage, resolvedSize);
    }

    long offset() {
        return (long) page * size;
    }

    int totalPages(long totalElements) {
        return (int) ((totalElements + size - 1) / size);
    }

    static ApiException invalid(Map<String, String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.", errors);
    }
}
