package vn.ttcs.recruitment.reporting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Task 581 (story S8-09): which source brings the best candidates, not merely the most. Pure, so the rule is
 * unit-tested.
 * <p>
 * A source is ranked only when it is a real source (a catalog source or the internal referrals, never the unknown
 * bucket) and brought at least minApplications applications, so one lucky application cannot top the list. Ranked
 * sources are ordered by hire rate (hired / applications), then screening pass rate (passed / applications), both
 * compared exactly, then by more applications, then by name and key. Rank 1 is the best source.
 */
final class SourceRanking {
    static final String RULE = "HIRE_RATE_THEN_SCREENING_PASS_RATE_THEN_APPLICATIONS_MIN_SAMPLE";

    private SourceRanking() {
    }

    /** key identifies the effective source (SourceReportService); rankable is false for the unknown bucket. */
    record Counts(String key, String name, boolean rankable, long applications, long passed, long hired) { }

    record Ranked(Counts counts, BigDecimal screeningPassRate, BigDecimal hireRate, boolean meetsMinimumSample,
                  Integer qualityRank) { }

    static List<Ranked> rank(List<Counts> sources, int minApplications) {
        Comparator<Counts> quality = (left, right) -> {
            int hire = compare(right.hired(), right.applications(), left.hired(), left.applications());
            if (hire != 0) {
                return hire;
            }
            int pass = compare(right.passed(), right.applications(), left.passed(), left.applications());
            if (pass != 0) {
                return pass;
            }
            return Long.compare(right.applications(), left.applications());
        };
        Comparator<Counts> byName = Comparator.comparing(Counts::name).thenComparing(Counts::key);
        List<Counts> eligible = sources.stream().filter(source -> eligible(source, minApplications))
                .sorted(quality.thenComparing(byName)).toList();
        List<Counts> others = sources.stream().filter(source -> !eligible(source, minApplications))
                .sorted(Comparator.comparingLong(Counts::applications).reversed().thenComparing(byName)).toList();
        List<Ranked> ranked = new ArrayList<>();
        for (int index = 0; index < eligible.size(); index++) {
            ranked.add(ranked(eligible.get(index), minApplications, index + 1));
        }
        for (Counts source : others) {
            ranked.add(ranked(source, minApplications, null));
        }
        return List.copyOf(ranked);
    }

    private static boolean eligible(Counts source, int minApplications) {
        return source.rankable() && source.applications() >= minApplications;
    }

    private static Ranked ranked(Counts source, int minApplications, Integer rank) {
        return new Ranked(source, ReportRates.rate(source.passed(), source.applications()),
                ReportRates.rate(source.hired(), source.applications()), source.applications() >= minApplications, rank);
    }

    // Sign of a/b - c/d without division or rounding; b and d are positive for an eligible source.
    private static int compare(long a, long b, long c, long d) {
        return Long.compare(Math.multiplyExact(a, d), Math.multiplyExact(c, b));
    }
}
