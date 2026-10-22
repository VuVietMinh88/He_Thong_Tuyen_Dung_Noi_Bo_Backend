package vn.ttcs.recruitment.cvextraction;

import java.nio.file.Files;
import java.nio.file.Path;

/** Manual sandbox measurement. Test class is never packaged in the production JAR. */
public final class CvExtractionBenchmarkCommand {
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        CvExtractionCommand.main(args);
        long peak = Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/memory.peak")).strip());
        System.err.println("EXTRACTION_METRICS seconds=" + (System.nanoTime() - started) / 1_000_000_000d
                + " peakBytes=" + peak);
    }
}
