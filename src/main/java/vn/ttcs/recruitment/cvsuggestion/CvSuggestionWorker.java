package vn.ttcs.recruitment.cvsuggestion;

import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.cvextraction.CvExtractionResult;

/** Explicit standalone lifecycle: no Spring bean, scheduler, HTTP server, mail or migrations. */
public final class CvSuggestionWorker {
    @FunctionalInterface public interface Sandbox { CvExtractionResult extract(byte[] content) throws Exception; }
    private final CvSuggestionRepository jobs;
    private final TransactionTemplate tx;
    private final Sandbox sandbox;
    private final int maxRunning;
    public CvSuggestionWorker(CvSuggestionRepository jobs,TransactionTemplate tx,Sandbox sandbox,int maxRunning) {
        if (maxRunning<1 || maxRunning>8) throw new IllegalArgumentException("Invalid worker capacity");
        this.jobs=jobs;this.tx=tx;this.sandbox=sandbox;this.maxRunning=maxRunning;
    }
    public boolean processNext() {
        jobs.expire(tx);
        var claimed=jobs.claim(tx,maxRunning);
        if (claimed.isEmpty()) return false;
        var claim=claimed.get();
        var timer=Executors.newSingleThreadScheduledExecutor();
        try {
            var content=jobs.content(claim);
            if (content.isEmpty()) { jobs.finish(tx,claim,null,"CV_CHANGED");return true; }
            timer.scheduleAtFixedRate(() -> {
                try { jobs.renew(claim); } catch (RuntimeException ignored) { /* Fencing rejects a late result. */ }
            },30,30,TimeUnit.SECONDS);
            CvExtractionResult result;
            try { result=CvSuggestionJson.read(CvSuggestionJson.write(sandbox.extract(content.get()))); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();jobs.finish(tx,claim,null,"WORKER_INTERRUPTED");return true;
            } catch (Exception exception) { jobs.finish(tx,claim,null,"EXTRACTION_FAILED");return true; }
            if (result.outcome()==CvExtractionResult.Outcome.FAILED)
                jobs.finish(tx,claim,null,"EXTRACTION_FAILED");
            else jobs.finish(tx,claim,CvSuggestionJson.write(result),null);
            return true;
        } finally { timer.shutdownNow(); }
    }
}
