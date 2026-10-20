package vn.ttcs.recruitment.reporting;

import vn.ttcs.recruitment.security.AccessScope;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static vn.ttcs.recruitment.security.PermissionModule.CANDIDATES;
import static vn.ttcs.recruitment.security.PermissionModule.EVALUATIONS;
import static vn.ttcs.recruitment.security.PermissionModule.INTERVIEWS;
import static vn.ttcs.recruitment.security.PermissionModule.OFFERS;
import static vn.ttcs.recruitment.security.PermissionModule.REPORTS;
import static vn.ttcs.recruitment.security.PermissionModule.REQUISITIONS;

/**
 * Stories 81-85: who reads a report and how far each module grant reaches, read again from the database on every
 * request (PermissionService). Report numbers are built from requisitions and their applications, so a row is visible
 * without a filter only when REPORTS, REQUISITIONS and CANDIDATES are all ALL: a broad REPORTS grant never widens a
 * narrow candidate grant. Otherwise the row must belong to a requisition the viewer is assigned to now or to a
 * department the viewer manages (ReportingAccess.SCOPED_ROWS), the same rule as CandidateAccess.READABLE.
 */
public record ReportViewer(UUID id, AccessScope reports, AccessScope reportsWrite, AccessScope requisitions,
                           AccessScope candidates, AccessScope interviews, AccessScope offers,
                           AccessScope evaluations, AccessScope evaluationsWrite, Set<String> roles) {
    public ReportViewer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reports, "reports");
        Objects.requireNonNull(reportsWrite, "reportsWrite");
        Objects.requireNonNull(requisitions, "requisitions");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(interviews, "interviews");
        Objects.requireNonNull(offers, "offers");
        Objects.requireNonNull(evaluations, "evaluations");
        Objects.requireNonNull(evaluationsWrite, "evaluationsWrite");
        roles = Set.copyOf(roles);
    }

    static ReportViewer of(UUID id, Collection<String> grants) {
        return of(id, grants, Set.of());
    }

    static ReportViewer of(UUID id, Collection<String> grants, Set<String> roles) {
        return new ReportViewer(id, AccessScope.read(grants, REPORTS), AccessScope.write(grants, REPORTS),
                AccessScope.read(grants, REQUISITIONS), AccessScope.read(grants, CANDIDATES),
                AccessScope.read(grants, INTERVIEWS), AccessScope.read(grants, OFFERS),
                AccessScope.read(grants, EVALUATIONS), AccessScope.write(grants, EVALUATIONS), roles);
    }

    /** May the viewer open a report at all: a read grant of REPORTS, REQUISITIONS and CANDIDATES (any scope). */
    boolean readsReports() {
        return reports != AccessScope.NONE && requisitions != AccessScope.NONE && candidates != AccessScope.NONE;
    }

    /** Every requisition and application, without the assignment or department filter. */
    boolean unrestricted() {
        return reports == AccessScope.ALL && requisitions == AccessScope.ALL && candidates == AccessScope.ALL;
    }

    /** Interview numbers skip the row filter only when the interview grant is ALL too. */
    boolean interviewsUnrestricted() {
        return unrestricted() && interviews == AccessScope.ALL;
    }

    boolean offersUnrestricted() {
        return unrestricted() && offers == AccessScope.ALL;
    }

    /**
     * Task 562: may make a hiring decision somewhere, as ApplicationStageChangeService.decide requires: the role
     * HIRING_MANAGER, HR_MANAGER or ADMIN and an EVALUATIONS read grant (a HIRING_MANAGER only for the departments it
     * manages, checked in SQL).
     */
    boolean decidesHiring() {
        return evaluations != AccessScope.NONE
                && (roles.contains("HIRING_MANAGER") || seniorDecider());
    }

    /** ADMIN and HR_MANAGER decide for every department. */
    boolean seniorDecider() {
        return roles.contains("ADMIN") || roles.contains("HR_MANAGER");
    }

    /** The scope shown to the client, so a screen can say "all company" or "your positions". */
    String scopeName() {
        return unrestricted() ? "ALL" : "SCOPED";
    }
}
