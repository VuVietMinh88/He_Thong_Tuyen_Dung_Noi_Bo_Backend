package vn.ttcs.recruitment.auth;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import vn.ttcs.recruitment.candidateduplicate.DuplicateDetectionWorker;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=", "app.bootstrap.enabled=false", "logging.level.root=WARN", "debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.candidate-duplicates.enabled=true",
        "app.candidate-duplicates.poll-delay-ms=3600000",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateMergeDisabledIntegrationTest extends CvApplicationTestSupport {
    @Autowired DuplicateDetectionWorker detector;
    @Test void absentFlagDisablesPreviewAndMergeWithoutWriting() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();
        assertThat(api.request("POST","/api/v1/candidate-merges/preview",json.writeValueAsString(Map.of("leftApplicationId",app,"rightApplicationId",second)),recruiterToken).statusCode()).isEqualTo(503);
        var request=Map.of("requestKey",UUID.randomUUID(),"leftGroupId",app,"rightGroupId",second,"resultGroupId",app,
                "representativeApplicationId",app,"expectedFingerprint","0".repeat(64));
        assertThat(api.request("POST","/api/v1/candidate-merges",json.writeValueAsString(request),recruiterToken).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_undo_events",Integer.class)).isZero();
    }
}
