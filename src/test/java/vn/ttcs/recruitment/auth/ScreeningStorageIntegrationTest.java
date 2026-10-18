package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.screening.ScreeningRepository;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScreeningStorageIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationStorageService storage;
    @Autowired CvValidator validator;
    @Autowired AuthIntegrationTest.MutableClock clock;
    @Autowired ScreeningRepository screenings;
    private UUID application, actor, tag;
    private final Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        var applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        application = applications.stored(applications.publishedPosting("Java"), "lan@example.test").applicationId();
        actor = applications.account("Người đánh giá", "reviewer@example.test", Role.HR_MANAGER);
        tag = catalog("CANDIDATE_TAG");
    }

    @Test
    void tagsMustExistHaveCorrectCatalogTypeAndBeUniquePerApplication() {
        attach(tag);
        assertThatThrownBy(() -> attach(tag)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> attach(UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        UUID source = catalog("CANDIDATE_SOURCE");
        assertThatThrownBy(() -> attach(source)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM recruitment_catalog_items WHERE id = ?", tag))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ratingRangeIsEnforcedAndExactlyOneSharedRatingExists() {
        for (int invalid : new int[]{0, 6, -1}) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO application_screening_ratings VALUES (?,?,?,?)",
                    application, invalid, actor, at)).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("INSERT INTO application_screening_ratings VALUES (?,?,?,?)", application, 5, actor, at);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO application_screening_ratings VALUES (?,?,?,?)",
                application, 3, actor, at)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id = ?", actor))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noteKeepsAuthorAndTimeIsImmutableAndHasBoundedPagedReads() {
        UUID note = UUID.randomUUID();
        addNote(note, "Nội dung nội bộ");
        addNote(UUID.randomUUID(), "Ghi chú mới");
        assertThatThrownBy(() -> jdbc.update("UPDATE application_screening_notes SET content = 'changed' WHERE id = ?", note))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> addNote(UUID.randomUUID(), "  "))
                .isInstanceOf(DataIntegrityViolationException.class);
        var view = new TransactionTemplate(transactions).execute(status -> screenings.view(application, 0, 1));
        assertThat(view.totalNotes()).isEqualTo(2);
        assertThat(view.notes()).hasSize(1);
        assertThat(view.notes().getFirst().authorId()).isEqualTo(actor);
        assertThat(view.notes().getFirst().authorName()).isEqualTo("Người đánh giá");
        assertThat(view.notes().getFirst().createdAt()).isEqualTo(ApplicationTestSupport.NOW);
        assertThat(view.toString()).doesNotContain("Nội dung");
    }

    @Test
    void deletingApplicationCascadesAssessmentWithoutDeletingCatalogOrActor() {
        attach(tag);
        jdbc.update("INSERT INTO application_screening_ratings VALUES (?,?,?,?)", application, 2, actor, at);
        addNote(UUID.randomUUID(), "Ghi chú riêng");
        jdbc.update("DELETE FROM job_applications WHERE id = ?", application);
        for (String table : new String[]{"application_screening_tags", "application_screening_ratings", "application_screening_notes"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items WHERE id = ?", Integer.class, tag)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts WHERE id = ?", Integer.class, actor)).isEqualTo(1);
    }

    private UUID catalog(String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items VALUES (?,?,?,'Nhãn',0,true,?,?)", id, type, id.toString(), at, at);
        return id;
    }
    private void attach(UUID tagId) {
        jdbc.update("INSERT INTO application_screening_tags(application_id,tag_id,added_by,added_at) VALUES (?,?,?,?)",
                application, tagId, actor, at);
    }
    private void addNote(UUID id, String text) {
        jdbc.update("INSERT INTO application_screening_notes VALUES (?,?,?,?,?)", id, application, text, actor, at);
    }
}
