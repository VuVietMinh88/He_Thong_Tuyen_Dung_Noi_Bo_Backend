package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.requisition.ActivePipeline;
import vn.ttcs.recruitment.requisition.RequisitionPipelineSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.UUID;

@Component
public class ApplicationPipelineSource implements RequisitionPipelineSource {
    private final JdbcTemplate jdbc;

    public ApplicationPipelineSource(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ActivePipeline activePipeline(UUID requisitionId) {
        // No CV join, no extra locks: the status-change caller already holds the requisition lock.
        var counts = new EnumMap<ApplicationStage, Integer>(ApplicationStage.class);
        jdbc.query("SELECT stage, count(*) AS total FROM job_applications WHERE requisition_id = ? GROUP BY stage",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> counts.put(ApplicationStage.valueOf(row.getString("stage")),
                        Math.toIntExact(row.getLong("total"))), requisitionId);
        var stages = new ArrayList<ActivePipeline.Stage>();
        for (ApplicationStage stage : ApplicationStage.values()) {
            int count = counts.getOrDefault(stage, 0);
            if (stage.active() && count > 0) stages.add(new ActivePipeline.Stage(stage.name(), stage.displayName(), count));
        }
        return new ActivePipeline(stages.stream().mapToInt(ActivePipeline.Stage::count).sum(), stages);
    }
}
