package vn.ttcs.recruitment.offer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ttcs.recruitment.mail.MailBody;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfferMailContentTest {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String REPLY_URL = "https://tuyendung.example.vn/offers/reply#token=abc123";
    // 17:00 in Vietnam on Friday 30/10/2026.
    private static final Instant DEADLINE = Instant.parse("2026-10-30T10:00:00Z");

    @Test
    void showsTheTermsTheGreetingAndTheReplyLink() {
        MailBody body = OfferMailContent.body(payload(15_000_000L, 2_500_000L, REPLY_URL), VIETNAM);

        assertThat(OfferMailContent.subject(payload(15_000_000L, 2_500_000L, REPLY_URL)))
                .isEqualTo("Thư mời nhận việc");
        assertThat(paragraphs(body)).first().isEqualTo("Xin chào Nguyễn Văn An,");
        assertThat(field(body, "Vị trí")).isEqualTo("Kỹ sư phần mềm");
        assertThat(field(body, "Lương cơ bản hằng tháng")).isEqualTo("15.000.000 VND");
        assertThat(field(body, "Phụ cấp hằng tháng")).isEqualTo("2.500.000 VND");
        assertThat(field(body, "Ngày bắt đầu làm việc")).isEqualTo("Thứ Hai, ngày 02/11/2026");
        assertThat(paragraphs(body)).anyMatch(text -> text.contains("chấp nhận hoặc từ chối"));
        assertThat(links(body)).containsExactly(new MailBody.Link("Chấp nhận hoặc từ chối lời mời", REPLY_URL));
    }

    @Test
    void writesAmountsWithVietnameseGroupingAtTheBoundaries() {
        assertThat(OfferMailContent.money(0, "salary")).isEqualTo("0 VND");
        assertThat(OfferMailContent.money(999, "salary")).isEqualTo("999 VND");
        assertThat(OfferMailContent.money(1_000, "salary")).isEqualTo("1.000 VND");
        assertThat(OfferMailContent.money(1_000_000_000_000L, "salary")).isEqualTo("1.000.000.000.000 VND");

        MailBody body = OfferMailContent.body(payload(0, 1_000_000_000_000L, null), VIETNAM);
        assertThat(field(body, "Lương cơ bản hằng tháng")).isEqualTo("0 VND");
        assertThat(field(body, "Phụ cấp hằng tháng")).isEqualTo("1.000.000.000.000 VND");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 1_000_000_000_001L, Long.MIN_VALUE, Long.MAX_VALUE})
    void rejectsAmountsOutsideTheRange(long amount) {
        assertThatThrownBy(() -> OfferMailContent.body(payload(amount, 0, null), VIETNAM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfferMailContent.body(payload(0, amount, null), VIETNAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesTheDeadlineInTheGivenZoneAndNamesTheZone() {
        assertThat(field(OfferMailContent.body(payload(1, 1, null), VIETNAM), "Hạn phản hồi"))
                .isEqualTo("17:00 Thứ Sáu, ngày 30/10/2026 (Giờ Việt Nam, Asia/Ho_Chi_Minh, UTC+07:00)");
        assertThat(field(OfferMailContent.body(payload(1, 1, null), ZoneOffset.UTC), "Hạn phản hồi"))
                .isEqualTo("10:00 Thứ Sáu, ngày 30/10/2026 (UTC)");
        assertThat(field(OfferMailContent.body(payload(1, 1, null), ZoneId.of("America/New_York")), "Hạn phản hồi"))
                .isEqualTo("06:00 Thứ Sáu, ngày 30/10/2026 (America/New_York, UTC-04:00)");

        OfferMailPayload lateEvening = new OfferMailPayload("an@example.vn", "An", "Kỹ sư", 1, 1,
                LocalDate.of(2026, 11, 2), Instant.parse("2026-10-30T20:00:00Z"), null);
        assertThat(field(OfferMailContent.body(lateEvening, VIETNAM), "Hạn phản hồi"))
                .startsWith("03:00 Thứ Bảy, ngày 31/10/2026");
        assertThatThrownBy(() -> OfferMailContent.body(lateEvening, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void keepsHtmlAsRawTextForTheLayoutToEscape() {
        OfferMailPayload payload = new OfferMailPayload("an@example.vn", "<b>An</b> & co", "<script>x()</script>",
                1, 1, LocalDate.of(2026, 11, 2), DEADLINE, null);

        MailBody body = OfferMailContent.body(payload, VIETNAM);

        assertThat(paragraphs(body)).first().isEqualTo("Xin chào <b>An</b> & co,");
        assertThat(field(body, "Vị trí")).isEqualTo("<script>x()</script>");
        assertThat(OfferMailContent.subject(payload)).doesNotContain("<", "An");
    }

    @Test
    void putsEveryValueOnOneLineSoNoValueLooksLikeAnotherField() {
        OfferMailPayload payload = new OfferMailPayload("an@example.vn", "An\r\nLương cơ bản hằng tháng: 999",
                "Kỹ sư\nPhụ cấp hằng tháng: 1 Hạn\u0000phản‮hồi\t", 1, 1, LocalDate.of(2026, 11, 2),
                DEADLINE, null);

        MailBody body = OfferMailContent.body(payload, VIETNAM);

        assertThat(paragraphs(body)).first().isEqualTo("Xin chào An Lương cơ bản hằng tháng: 999,");
        assertThat(field(body, "Vị trí")).isEqualTo("Kỹ sư Phụ cấp hằng tháng: 1 Hạn phản hồi");
        assertThat(body.blocks()).filteredOn(MailBody.Field.class::isInstance)
                .extracting(block -> ((MailBody.Field) block).label())
                .containsExactly("Vị trí", "Lương cơ bản hằng tháng", "Phụ cấp hằng tháng", "Ngày bắt đầu làm việc",
                        "Hạn phản hồi");
        assertThat(body.blocks()).filteredOn(MailBody.Field.class::isInstance)
                .allSatisfy(block -> assertThat(((MailBody.Field) block).value()).doesNotContain("\n", "\r"));
    }

    @Test
    void rejectsAMissingPositionOrDate() {
        assertThatThrownBy(() -> OfferMailContent.body(new OfferMailPayload("a@b.vn", "An", " \n ", 1, 1,
                LocalDate.of(2026, 11, 2), DEADLINE, null), VIETNAM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfferMailContent.body(new OfferMailPayload("a@b.vn", "An", "Kỹ sư", 1, 1,
                null, DEADLINE, null), VIETNAM)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OfferMailContent.body(new OfferMailPayload("a@b.vn", "An", "Kỹ sư", 1, 1,
                LocalDate.of(2026, 11, 2), null, null), VIETNAM)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPreviewIsTheSentBodyWithoutTheReplyLink() {
        MailBody preview = OfferMailContent.body(payload(15_000_000L, 0, null), VIETNAM);
        MailBody sent = OfferMailContent.body(payload(15_000_000L, 0, REPLY_URL), VIETNAM);

        assertThat(links(preview)).isEmpty();
        assertThat(links(sent)).hasSize(1);
        assertThat(preview.blocks()).isEqualTo(sent.blocks().stream()
                .filter(block -> !(block instanceof MailBody.Link)).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://tuyendung.example.vn/offers/reply#token=abc123",
            "https://tuyendung.example.vn:8443/offers/reply#token=abc123",
            "https://[2001:db8::1]/offers/reply#token=abc123",
            "http://localhost:8080/offers/reply#token=abc123",
            "http://127.0.0.1/offers/reply#token=abc123",
            "http://[::1]:5173/offers/reply#token=abc123"})
    void acceptsHttpsAndLoopbackHttpReplyLinks(String url) {
        assertThat(links(OfferMailContent.body(payload(1, 1, url), VIETNAM)))
                .extracting(MailBody.Link::url).containsExactly(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "tuyendung.example.vn/offers/reply#token=abc123",
            "javascript:alert(1)//#token",
            "ftp://tuyendung.example.vn/offers/reply#token=abc123",
            "HTTPS://tuyendung.example.vn/offers/reply#token=abc123",
            "http://tuyendung.example.vn/offers/reply#token=abc123",
            "http://127.0.0.2.example.vn/offers/reply#token=abc123",
            "https://user@tuyendung.example.vn/offers/reply#token=abc123",
            "https://user:secret@tuyendung.example.vn/offers/reply#token=abc123",
            "https://tuyendung.example.vn@evil.example/offers/reply#token=abc123",
            "http://user@localhost/offers/reply#token=abc123",
            "https:///offers/reply#token=abc123",
            "https://exa_mple.vn/offers/reply#token=abc123",
            "https://-example.vn/offers/reply#token=abc123",
            "https://example..vn/offers/reply#token=abc123",
            "https://999.1.1.1/offers/reply#token=abc123",
            "https://1.2.3/offers/reply#token=abc123",
            "https://tuyendung.example.vn:0/offers/reply#token=abc123",
            "https://tuyendung.example.vn/offers/reply",
            "https://tuyendung.example.vn/offers/reply#",
            "https://tuyendung.example.vn/offers/reply?token=abc123",
            "https://tuyendung.example.vn/offers/reply?token=abc123#token=abc123",
            "https://tuyendung.example.vn:8443/offers/reply?lang=vi#token=abc123",
            " https://tuyendung.example.vn/offers/reply#token=abc123",
            "https://tuyendung.example.vn/offers/reply#token=abc123\n",
            "https://tuyendung.example.vn/offers/re\u0000ply#token=abc123",
            "https://tuyendung.example.vn/offers/rép#token=abc123"})
    void rejectsInvalidReplyLinks(String url) {
        assertThatThrownBy(() -> OfferMailContent.body(payload(1, 1, url), VIETNAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsTheReplyLinkTo2048Characters() {
        String prefix = "https://tuyendung.example.vn/offers/reply#token=";
        String longest = prefix + "a".repeat(2048 - prefix.length());

        assertThat(links(OfferMailContent.body(payload(1, 1, longest), VIETNAM))).hasSize(1);
        assertThatThrownBy(() -> OfferMailContent.body(payload(1, 1, longest + "a"), VIETNAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringHidesEveryValue() {
        String text = payload(15_000_000L, 2_500_000L, REPLY_URL).toString();

        assertThat(text).startsWith("OfferMailPayload[").contains("[redacted]")
                .doesNotContain("an.nguyen@example.vn", "Nguyễn", "Kỹ sư", "15000000", "2500000", "2026", "abc123",
                        "https", "tuyendung");
    }

    private static OfferMailPayload payload(long salary, long allowance, String replyUrl) {
        return new OfferMailPayload("an.nguyen@example.vn", "Nguyễn Văn An", "Kỹ sư phần mềm", salary, allowance,
                LocalDate.of(2026, 11, 2), DEADLINE, replyUrl);
    }

    private static String field(MailBody body, String label) {
        return body.blocks().stream()
                .filter(MailBody.Field.class::isInstance)
                .map(MailBody.Field.class::cast)
                .filter(field -> field.label().equals(label))
                .map(MailBody.Field::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No field " + label));
    }

    private static List<String> paragraphs(MailBody body) {
        return body.blocks().stream()
                .filter(MailBody.Paragraph.class::isInstance)
                .map(block -> ((MailBody.Paragraph) block).text())
                .toList();
    }

    private static List<MailBody.Link> links(MailBody body) {
        return body.blocks().stream()
                .filter(MailBody.Link.class::isInstance)
                .map(MailBody.Link.class::cast)
                .toList();
    }
}
