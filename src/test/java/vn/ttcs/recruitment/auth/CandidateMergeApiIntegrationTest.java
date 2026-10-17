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
        "app.candidate-duplicates.poll-delay-ms=3600000", "app.candidate-merges.enabled=true",
        "app.cv-contact-confirmation.enabled=true", "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CandidateMergeApiIntegrationTest extends CvApplicationTestSupport {
    @Autowired DuplicateDetectionWorker detector;

    @Test void previewMergeReplayAndReadKeepApplicationsUnchanged() throws Exception {
        UUID second=pair();var before=jdbc.queryForList("SELECT row_to_json(a)::text FROM job_applications a ORDER BY id",String.class);
        var preview=preview(second,recruiterToken);assertThat(preview.statusCode()).isEqualTo(200);
        var body=json.readTree(preview.body());assertThat(body.path("members").size()).isEqualTo(2);
        assertThat(body.path("matches").get(0).path("phone").asBoolean()).isTrue();
        var request=request(second,body.path("fingerprint").asText());
        var merged=http("POST","/api/v1/candidate-merges",request,recruiterToken);
        assertThat(merged.statusCode()).isEqualTo(200);assertThat(merged.headers().firstValue("Cache-Control")).contains("no-store");
        var replay=http("POST","/api/v1/candidate-merges",request,recruiterToken);
        assertThat(replay.statusCode()).isEqualTo(200);assertThat(replay.body()).isEqualTo(merged.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT row_to_json(a)::text FROM job_applications a ORDER BY id",String.class)).isEqualTo(before);
        var id=json.readTree(merged.body()).path("id").asText();
        assertThat(http("GET","/api/v1/candidate-merges/"+id,null,recruiterToken).statusCode()).isEqualTo(200);
    }

    @Test void inaccessiblePreviewAndMergeDoNotExposeOrMutate() throws Exception {
        UUID second=pair();var preview=preview(second,recruiterToken);
        var request=request(second,json.readTree(preview.body()).path("fingerprint").asText());
        assertThat(preview(second,otherToken).statusCode()).isEqualTo(403);
        var denied=http("POST","/api/v1/candidate-merges",request,otherToken);
        assertThat(denied.statusCode()).isEqualTo(403);assertThat(denied.body()).doesNotContain(app.toString(),second.toString(),"one@example.test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations",Integer.class)).isZero();
    }

    @Test void changedMembershipMakesPreviewStaleAndRetryRechecksScope() throws Exception {
        UUID second=pair();var p=preview(second,recruiterToken);
        var request=request(second,json.readTree(p.body()).path("fingerprint").asText());
        jdbc.update("UPDATE candidate_profiles SET revision=revision+1 WHERE id=?",app);
        assertThat(http("POST","/api/v1/candidate-merges",request,recruiterToken).statusCode()).isEqualTo(409);
        p=preview(second,recruiterToken);request=request(second,json.readTree(p.body()).path("fingerprint").asText());
        assertThat(http("POST","/api/v1/candidate-merges",request,recruiterToken).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=?",requisition);
        assertThat(http("POST","/api/v1/candidate-merges",request,recruiterToken).statusCode()).isEqualTo(403);
    }

    @Test void reusedKeyDifferentPayloadIsRejectedWithoutSecondMerge() throws Exception {
        UUID second=pair();var p=preview(second,recruiterToken);
        var values=new java.util.HashMap<>(request(second,json.readTree(p.body()).path("fingerprint").asText()));
        assertThat(http("POST","/api/v1/candidate-merges",values,recruiterToken).statusCode()).isEqualTo(200);
        values.put("representativeApplicationId",second);
        assertThat(http("POST","/api/v1/candidate-merges",values,recruiterToken).statusCode()).isEqualTo(409);
    }

    protected UUID pair() { UUID second=application("two@example.test");detector.processBatch();return second; }
    protected java.net.http.HttpResponse<String> preview(UUID second,String token) throws Exception {
        return http("POST","/api/v1/candidate-merges/preview",Map.of("leftApplicationId",app,"rightApplicationId",second),token);
    }
    protected Map<String,Object> request(UUID second,String fingerprint) {
        return Map.of("requestKey",UUID.randomUUID(),"leftGroupId",app,"rightGroupId",second,
                "resultGroupId",app,"representativeApplicationId",app,"expectedFingerprint",fingerprint);
    }
    private java.net.http.HttpResponse<String> http(String method,String path,Object payload,String token) throws Exception {
        return api.request(method,path,payload==null?null:json.writeValueAsString(payload),token);
    }
}
