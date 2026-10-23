package vn.ttcs.recruitment.invitation;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.mail.MailBody;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewMailContentTest {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    // 09:00 to 10:30 on Tuesday 20 October 2026 in Vietnam.
    private static final Instant STARTS = Instant.parse("2026-10-20T02:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-10-20T03:30:00Z");
    private static final String MEETING = "https://meet.example.com/abc-defg-hij";
    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    private static final String REPLY = "https://tuyendung.example.vn/api/v1/public/interview-invitations/reply#token="
            + TOKEN;

    private static InterviewMailPayload payload(String kind, String replyUrl) {
        return new InterviewMailPayload("ung.vien@example.com", "Nguyễn Văn A", "Kỹ sư Java", "Vòng 1",
                STARTS, ENDS, "ONLINE", "Phòng 301", MEETING, "Mang theo CMND.", kind, replyUrl, "Người phỏng vấn bận");
    }

    private static Map<String, String> fields(MailBody body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (MailBody.Block block : body.blocks()) {
            if (block instanceof MailBody.Field field) {
                fields.put(field.label(), field.value());
            }
        }
        return fields;
    }

    private static List<String> links(MailBody body) {
        return body.blocks().stream().filter(MailBody.Link.class::isInstance).map(MailBody.Link.class::cast)
                .map(MailBody.Link::url).toList();
    }

    private static String allText(MailBody body) {
        return body.blocks().stream().map(block -> switch (block) {
            case MailBody.Paragraph paragraph -> paragraph.text();
            case MailBody.Field field -> field.label() + ": " + field.value();
            case MailBody.Link link -> link.label() + " " + link.url();
        }).collect(Collectors.joining("\n"));
    }

    @Test
    void subjectsAreFixedPerKindAndNeverContainUserValues() {
        assertThat(InterviewMailContent.subject(payload("INVITATION", REPLY))).isEqualTo("Thư mời phỏng vấn");
        assertThat(InterviewMailContent.subject(payload("UPDATED", REPLY))).isEqualTo("Cập nhật lịch phỏng vấn");
        assertThat(InterviewMailContent.subject(payload("CANCELLED", null))).isEqualTo("Thông báo hủy lịch phỏng vấn");
        assertThat(InterviewMailContent.subject(payload("REMOVED", null)))
                .isEqualTo("Thông báo thay đổi người tham gia phỏng vấn");

        InterviewMailPayload hostile = new InterviewMailPayload("a@example.com", "X\r\nBcc: b@example.com",
                "Title\r\nBcc: c@example.com", "Round", STARTS, ENDS, "ONLINE", null, null, null, "INVITATION", null,
                null);
        assertThat(InterviewMailContent.subject(hostile)).isEqualTo("Thư mời phỏng vấn").doesNotContain("\n", "\r");
    }

    @Test
    void unknownKindAndInvertedSlotAreRejected() {
        assertThatThrownBy(() -> InterviewMailContent.subject(payload("invitation", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewMailContent.body(payload("OTHER", null), VIETNAM))
                .isInstanceOf(IllegalArgumentException.class);
        InterviewMailPayload inverted = new InterviewMailPayload("a@example.com", "A", "T", "R", ENDS, STARTS,
                "ONLINE", null, null, null, "INVITATION", null, null);
        assertThatThrownBy(() -> InterviewMailContent.body(inverted, VIETNAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invitationShowsTheExactSlotWithAnExplicitTimeZone() {
        Map<String, String> fields = fields(InterviewMailContent.body(payload("INVITATION", REPLY), VIETNAM));

        assertThat(fields).containsEntry("Vị trí", "Kỹ sư Java")
                .containsEntry("Vòng phỏng vấn", "Vòng 1")
                .containsEntry("Bắt đầu", "09:00 Thứ Ba, ngày 20/10/2026")
                .containsEntry("Kết thúc", "10:30 Thứ Ba, ngày 20/10/2026")
                .containsEntry("Hình thức", "Trực tuyến")
                .containsEntry("Địa điểm", "Phòng 301");
        assertThat(fields.get("Múi giờ")).contains("Giờ Việt Nam", "Asia/Ho_Chi_Minh", "UTC+07:00");
    }

    @Test
    void slotFollowsTheGivenZone() {
        Map<String, String> fields = fields(InterviewMailContent.body(payload("INVITATION", null), ZoneId.of("UTC")));

        assertThat(fields).containsEntry("Bắt đầu", "02:00 Thứ Ba, ngày 20/10/2026")
                .containsEntry("Kết thúc", "03:30 Thứ Ba, ngày 20/10/2026");
        assertThat(fields.get("Múi giờ")).contains("UTC").doesNotContain("Việt Nam");
    }

    @Test
    void candidateInvitationLinksTheMeetingAndTheReplyPage() {
        MailBody body = InterviewMailContent.body(payload("INVITATION", REPLY), VIETNAM);

        assertThat(links(body)).containsExactly(MEETING, REPLY);
        assertThat(allText(body)).contains("Mang theo CMND.", "Xin chào Nguyễn Văn A,");
    }

    @Test
    void updateKeepsTheReplyLinkForTheCandidate() {
        MailBody body = InterviewMailContent.body(payload("UPDATED", REPLY), VIETNAM);

        assertThat(links(body)).containsExactly(MEETING, REPLY);
        assertThat(allText(body)).contains("đã được cập nhật");
    }

    @Test
    void staffInvitationHasNoReplyLink() {
        MailBody body = InterviewMailContent.body(payload("INVITATION", null), VIETNAM);

        assertThat(links(body)).containsExactly(MEETING);
        assertThat(allText(body)).doesNotContain("interview-invitations", "token", "Phản hồi lời mời");
    }

    @Test
    void cancellationOffersNoLinkNorVenueButShowsTheReason() {
        for (String kind : List.of("CANCELLED", "REMOVED")) {
            MailBody body = InterviewMailContent.body(payload(kind, REPLY), VIETNAM);
            String text = allText(body);

            assertThat(links(body)).as(kind).isEmpty();
            assertThat(text).as(kind).doesNotContain(MEETING, TOKEN, "Phòng 301", "Xác nhận", "xác nhận tham dự")
                    .contains("Lý do: Người phỏng vấn bận", "09:00 Thứ Ba, ngày 20/10/2026");
        }
    }

    @Test
    void userValuesStayRawTextForMailLayoutToEscape() {
        InterviewMailPayload payload = new InterviewMailPayload("a@example.com", "<b>An</b> & \"Bình\"",
                "<script>alert(1)</script> & Co", "Vòng <i>2</i>", STARTS, ENDS, "ONSITE", "Tầng 3 <A&B>", null,
                "Dòng 1 <b>\nDòng 2 &amp;", "INVITATION", null, null);
        MailBody body = InterviewMailContent.body(payload, VIETNAM);

        assertThat(fields(body)).containsEntry("Vị trí", "<script>alert(1)</script> & Co")
                .containsEntry("Vòng phỏng vấn", "Vòng <i>2</i>")
                .containsEntry("Địa điểm", "Tầng 3 <A&B>")
                .containsEntry("Hình thức", "Trực tiếp");
        // Not escaped here, so not escaped twice by MailLayout; the free text keeps its line break.
        assertThat(allText(body)).contains("Xin chào <b>An</b> & \"Bình\",", "Dòng 1 <b>\nDòng 2 &amp;")
                .doesNotContain("&lt;", "&gt;", "&quot;");
        assertThat(links(body)).isEmpty();
    }

    @Test
    void lineBreaksInOneLineValuesCannotForgeFields() {
        InterviewMailPayload payload = new InterviewMailPayload("a@example.com", "A\nB",
                "Dev\r\nLiên kết: https://evil.example", "R S", STARTS, ENDS, "ONLINE", "P\0Q", null, null,
                "INVITATION", null, null);
        Map<String, String> fields = fields(InterviewMailContent.body(payload, VIETNAM));

        assertThat(fields).containsEntry("Vị trí", "Dev Liên kết: https://evil.example")
                .containsEntry("Vòng phỏng vấn", "R S")
                .containsEntry("Địa điểm", "P Q");
    }

    @Test
    void onlyPlainHttpsMeetingUrlsBecomeLinks() {
        for (String url : List.of("javascript:alert(1)", "http://meet.example.com/x", "https://user@evil.example/x",
                "https://meet.example.com/a b", "data:text/html,x", "https:///nohost")) {
            InterviewMailPayload payload = new InterviewMailPayload("a@example.com", "A", "T", "R", STARTS, ENDS,
                    "ONLINE", null, url, null, "INVITATION", null, null);
            MailBody body = InterviewMailContent.body(payload, VIETNAM);

            assertThat(links(body)).as(url).isEmpty();
            assertThat(allText(body)).as(url).doesNotContain(url).contains("không hợp lệ");
        }
    }

    @Test
    void invalidReplyUrlIsABugAndIsNotSent() {
        assertThatThrownBy(() -> InterviewMailContent.body(payload("INVITATION", "http://example.com/r#token=x"),
                VIETNAM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewMailContent.body(payload("INVITATION", "javascript:alert(1)"), VIETNAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankPreparationGetsTheDefaultInstruction() {
        for (String preparation : new String[] {null, "", "  \n\t "}) {
            InterviewMailPayload payload = new InterviewMailPayload("a@example.com", "A", "T", "R", STARTS, ENDS,
                    "ONSITE", "Phòng 1", null, preparation, "INVITATION", null, null);

            assertThat(allText(InterviewMailContent.body(payload, VIETNAM)))
                    .contains("Hướng dẫn chuẩn bị:\n" + InterviewMailContent.DEFAULT_PREPARATION);
        }
    }

    @Test
    void blankNameGetsAGenericGreeting() {
        InterviewMailPayload payload = new InterviewMailPayload("a@example.com", "  ", "T", "R", STARTS, ENDS,
                "ONSITE", null, null, null, "INVITATION", null, null);

        MailBody.Block first = InterviewMailContent.body(payload, VIETNAM).blocks().get(0);
        assertThat(first).isEqualTo(new MailBody.Paragraph("Xin chào,"));
    }

    @Test
    void toStringLeavesOutPersonalDataAndLinks() {
        String text = payload("INVITATION", REPLY).toString();

        assertThat(text).contains("INVITATION")
                .doesNotContain("ung.vien@example.com", "Nguyễn Văn A", TOKEN, "https://", MEETING, "Phòng 301",
                        "Mang theo", "Người phỏng vấn bận");
    }
}
