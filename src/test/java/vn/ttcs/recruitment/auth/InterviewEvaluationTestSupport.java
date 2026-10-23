package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Synthetic ended interview and active weighted framework; never touches a configured development database. */
abstract class InterviewEvaluationTestSupport extends ScreeningTestSupport {
    UUID interview, panel, peer, framework, criterionA, criterionB, position;
    String panelToken, peerToken;

    @BeforeEach
    void evaluationFixture() {
        panel = applications.account("Evaluation One", "evaluation-one@example.test", Role.INTERVIEWER);
        peer = applications.account("Evaluation Two", "evaluation-two@example.test", Role.INTERVIEWER);
        panelToken = token(panel); peerToken = token(peer);
        position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        framework = UUID.randomUUID(); criterionA = UUID.randomUUID(); criterionB = UUID.randomUUID();
        Timestamp now = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO competency_frameworks(id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Interview Framework','ACTIVE',?,?)
                """, framework, framework.toString(), now, now);
        jdbc.update("INSERT INTO competency_criteria(id,framework_id,name,description,weight,sort_order) VALUES (?,?,'Technical','Technical snapshot',30,1)",
                criterionA, framework);
        jdbc.update("INSERT INTO competency_criteria(id,framework_id,name,description,weight,sort_order) VALUES (?,?,'Collaboration','Team snapshot',70,2)",
                criterionB, framework);
        jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id=?", framework, position);
        interview = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    created_by,created_at,updated_at) VALUES (?,?,'Technical round',?,?,'ONSITE','Interview Room','interview room',?,?,?)
                """, interview, first.applicationId(), Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(5400)),
                Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(1800)), hr, now, now);
        jdbc.update("INSERT INTO interview_participants(interview_id,user_id) VALUES (?,?),(?,?)", interview, panel, interview, peer);
    }

    String ownPath() { return ownPath(interview); }
    String ownPath(UUID id) { return "/api/v1/interviews/" + id + "/evaluations/me"; }
    String peersPath() { return "/api/v1/interviews/" + interview + "/evaluations"; }
    String reopenPath(UUID form) { return peersPath() + "/" + form + "/reopen"; }
    JsonNode form(String token) throws Exception { return ok(call("POST", ownPath(), null, token)); }
    Map<String, Object> payload(long version, Integer scoreA, Integer scoreB, String conclusion) {
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedVersion", version);
        body.put("items", List.of(answer(criterionA, scoreA, "Technical note"), answer(criterionB, scoreB, "Team note")));
        body.put("conclusion", conclusion);
        return body;
    }
    Map<String, Object> answer(UUID criterion, Integer score, String comment) {
        var item = new LinkedHashMap<String, Object>();
        item.put("criterionId", criterion); item.put("score", score); item.put("comment", comment);
        return item;
    }
}
