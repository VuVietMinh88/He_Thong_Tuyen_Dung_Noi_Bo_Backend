package vn.ttcs.recruitment.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.offerdelivery.OfferDeliveryCrypto;
import vn.ttcs.recruitment.offerdelivery.OfferDeliveryService;
import vn.ttcs.recruitment.offerdelivery.OfferDeliverySettings;
import vn.ttcs.recruitment.offerdelivery.OfferDeliveryWorker;
import vn.ttcs.recruitment.offer.OfferMailPayload;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties={"app.offers.enabled=true","app.offers.delivery-enabled=false",
        "app.offers.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.offers.page-url=http://localhost:8080/api/v1/public/offers/reply",
        "app.offers.poll-delay-ms=3600000","app.offers.expiry-delay-ms=3600000"})
abstract class OfferDeliveryTestSupport extends ScreeningTestSupport {
    static final Instant NOW=ApplicationTestSupport.NOW;
    static final Instant DEADLINE=NOW.plusSeconds(86400);
    static final String PUBLIC="/api/v1/public/offers";
    @Autowired OfferDeliveryCrypto crypto;
    @Autowired OfferDeliveryService service;
    @Autowired OfferDeliverySettings settings;
    @Autowired OfferDeliveryWorker worker;

    UUID approved() {
        jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id=?",first.applicationId());
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,
                    salary,allowance,start_date,response_deadline,band_min,band_max,outside_band,status,version,
                    approval_round,created_by,submitted_by,approved_at,created_at,updated_at)
                SELECT ?,a.id,ca.candidate_id,r.position_id,a.full_name,a.email,p.name,
                    20000000,2000000,?,?,10000000,30000000,false,'APPROVED',0,1,?,?,?,?,?
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id=a.id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id WHERE a.id=?
                """,id,LocalDate.ofInstant(NOW,ZoneOffset.UTC).plusDays(10),Timestamp.from(DEADLINE),recruiter,recruiter,
                Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW),first.applicationId());
        return id;
    }
    Sent queued() throws Exception {
        UUID id=approved(); JsonNode response=ok(send(id,0,hrToken));
        assertThat(response.path("status").asText()).isEqualTo("SENT");
        OfferMailPayload payload=payload(id);
        return new Sent(id,payload.replyUrl().substring(payload.replyUrl().indexOf("#token=")+7),payload);
    }
    OfferMailPayload payload(UUID offer) {
        return jdbc.queryForObject("SELECT * FROM offer_delivery_outbox WHERE offer_id=?",(r,n)->crypto.decrypt(
                r.getObject("id",UUID.class),offer,r.getString("key_id"),r.getBytes("nonce"),r.getBytes("ciphertext")),offer);
    }
    HttpResponse<String> send(UUID offer,long version,String token) throws Exception {
        return call("POST","/api/v1/offers/"+offer+"/send",json.writeValueAsString(Map.of("expectedVersion",version)),token);
    }
    HttpResponse<String> respond(String token,String action,String note) throws Exception {
        var body=new LinkedHashMap<String,Object>(); body.put("token",token); body.put("action",action); body.put("note",note);
        return call("POST",PUBLIC+"/respond",json.writeValueAsString(body),null);
    }
    HttpResponse<String> lookup(String token) throws Exception {
        return call("POST",PUBLIC+"/lookup",json.writeValueAsString(Map.of("token",token)),null);
    }
    void error(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
    String state(UUID offer) { return jdbc.queryForObject("SELECT status FROM offers WHERE id=?",String.class,offer); }
    String stage() { return jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?",String.class,first.applicationId()); }
    String delivery(UUID offer) { return jdbc.queryForObject("SELECT status FROM offer_delivery_outbox WHERE offer_id=?",String.class,offer); }
    void due(UUID offer) { jdbc.update("UPDATE offer_delivery_outbox SET next_attempt_at=clock_timestamp()-INTERVAL '1 second' WHERE offer_id=?",offer); }
    record Sent(UUID id,String token,OfferMailPayload payload) {
        @Override public String toString() { return "Sent[redacted]"; }
    }
}
