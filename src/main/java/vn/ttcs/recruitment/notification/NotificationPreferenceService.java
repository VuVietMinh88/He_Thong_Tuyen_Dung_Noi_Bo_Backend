package vn.ttcs.recruitment.notification;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.messaging.MessagingActors;
import vn.ttcs.recruitment.messaging.MessagingBooleanDeserializer;
import vn.ttcs.recruitment.messaging.MessagingTransactions;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 550 (story S8-04, "Cấu hình riêng cho kênh trong ứng dụng và kênh email; Thông báo bắt buộc như yêu cầu chờ
 * duyệt thì không tắt được"): the caller's own notification preferences. The user is always the token's account; a
 * request can neither read nor change anyone else's choices. Turning off a mandatory type in either channel is
 * refused by the server (400 NOTIFICATION_PREFERENCE_MANDATORY) and nothing of the request is stored.
 */
@Service
public class NotificationPreferenceService {
    private final NotificationPreferences preferences;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final Clock clock;

    public NotificationPreferenceService(NotificationPreferences preferences, MessagingActors actors,
                                         MessagingTransactions transactions, Clock clock) {
        this.preferences = preferences;
        this.actors = actors;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** One type in a PUT: both channels are required, true or false. */
    public record Entry(@NotNull String type,
                        @NotNull @JsonDeserialize(using = MessagingBooleanDeserializer.class) Boolean inApp,
                        @NotNull @JsonDeserialize(using = MessagingBooleanDeserializer.class) Boolean email) {
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unsupported field"); }
    }

    public record Update(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Entry> preferences) {
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unsupported field"); }
    }

    public record View(String type, String typeName, boolean mandatory, boolean inApp, boolean email) {
    }

    public List<View> mine(Jwt jwt) {
        UUID me = actors.reader(jwt).getId();
        actors.requireAll(me, "SELF_PROFILE_READ");
        return views(me);
    }

    public List<View> update(Jwt jwt, Update request) {
        Map<NotificationType, Entry> entries = new LinkedHashMap<>();
        Map<String, String> problems = new LinkedHashMap<>();
        for (int index = 0; index < request.preferences().size(); index++) {
            Entry entry = request.preferences().get(index);
            NotificationType type = EnumSet.allOf(NotificationType.class).stream()
                    .filter(candidate -> candidate.name().equals(entry.type())).findFirst().orElse(null);
            String field = "preferences[" + index + "]";
            if (type == null) problems.put(field + ".type", "Loại thông báo không hợp lệ.");
            else if (entries.put(type, entry) != null) problems.put(field + ".type", "Mỗi loại thông báo chỉ gửi một lần.");
            else if (type.mandatory() && (!entry.inApp() || !entry.email())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOTIFICATION_PREFERENCE_MANDATORY",
                        "Thông báo \"" + type.displayName() + "\" là bắt buộc, không thể tắt ở kênh nào.",
                        Map.of(field, "Thông báo bắt buộc không thể tắt."));
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.", problems);
        }
        return transactions.write("NOTIFICATION_PREFERENCE_BUSY", () -> {
            var actor = actors.lockWriter(jwt);
            actors.requireAll(actor.id(), "SELF_PROFILE_READ", "SELF_PROFILE_WRITE");
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            entries.forEach((type, entry) -> {
                preferences.store(actor.id(), type, NotificationPreferences.Channel.IN_APP, entry.inApp(), now);
                preferences.store(actor.id(), type, NotificationPreferences.Channel.EMAIL, entry.email(), now);
            });
            return views(actor.id());
        });
    }

    private List<View> views(UUID user) {
        return preferences.of(user).entrySet().stream().map(choice -> new View(choice.getKey().name(),
                choice.getKey().displayName(), choice.getKey().mandatory(), choice.getValue().inApp(),
                choice.getValue().email())).toList();
    }
}
