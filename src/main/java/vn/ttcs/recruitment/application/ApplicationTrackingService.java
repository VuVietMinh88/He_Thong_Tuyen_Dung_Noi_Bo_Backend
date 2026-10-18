package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.ApiException;

import java.util.Map;

/**
 * Task 339 (story S4-05): a candidate looks up an application with its tracking code and the email it was sent from.
 * Order: canonical code (400) -> client address (400) -> quota (429/503) -> one SQL read (404/200). Every admitted
 * well-formed attempt counts, found or not; a malformed one never does. An unknown code, a wrong email and a revoked
 * code all answer the same 404, so the API never tells which factor was wrong. Anything unexpected is masked as 503.
 */
@Service
public class ApplicationTrackingService {
    private final ApplicationClientIpResolver addresses;
    private final PostgresApplicationTrackingQuota quota;
    private final PublicApplicationReader reader;

    ApplicationTrackingService(ApplicationClientIpResolver addresses, PostgresApplicationTrackingQuota quota,
            PublicApplicationReader reader) {
        this.addresses = addresses; this.quota = quota; this.reader = reader;
    }

    // Call only after @Valid has accepted the request: both fields are then present and well formed.
    public PublicApplicationView lookup(ApplicationLookupRequest request, HttpServletRequest http) {
        try {
            var credential = credential(request);
            quota.checkAndRecord(bucket(http));
            return reader.find(credential).orElseThrow(ApplicationTrackingService::notFound);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // A statement timeout or any other failure: no SQL, class name or submitted value reaches the client.
            throw PostgresApplicationTrackingQuota.unavailable();
        }
    }

    private static TrackingCredential credential(ApplicationLookupRequest request) {
        byte[] hash = TrackingCode.hash(request.trackingCode()).orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.",
                Map.of("trackingCode", "Mã tra cứu không hợp lệ.")));
        return new TrackingCredential(hash, ApplicationSubmission.normalizeEmail(request.email()));
    }

    private String bucket(HttpServletRequest http) {
        String address;
        try {
            address = addresses.resolve(http);
        } catch (ApiException exception) {
            // The resolver's message speaks of submitting an application; a lookup gets a neutral one.
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPLICATION_CLIENT_IP_INVALID",
                    "Không thể xác định địa chỉ truy cập. Vui lòng thử lại sau.");
        }
        return TrackingBuckets.of(address);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_LOOKUP_NOT_FOUND", "Mã tra cứu hoặc email không đúng.");
    }
}
