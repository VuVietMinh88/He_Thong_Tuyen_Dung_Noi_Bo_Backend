package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * One report statement's row condition and parameters: the viewer's scope (ReportingAccess) ANDed with the
 * structural filters. Every repository method takes one, so a statement cannot forget either half.
 */
record ReportQuery(ReportViewer viewer, ReportFilters filters, MapSqlParameterSource parameters) {

    static ReportQuery of(ReportViewer viewer, ReportFilters filters) {
        return new ReportQuery(viewer, filters, filters.bind(ReportingAccess.parameters(viewer)));
    }

    /** Requisition and application rows (alias r) the viewer may report on. */
    String rows() {
        return ReportingAccess.SCOPE + filters.sql();
    }

    /** Interview rows: the same requisitions, unfiltered only for a viewer who also reads every interview. */
    String interviewRows() {
        return ReportingAccess.INTERVIEW_SCOPE + filters.sql();
    }

    String offerRows() {
        return ReportingAccess.OFFER_SCOPE + filters.sql();
    }

    ReportQuery with(String name, Object value) {
        parameters.addValue(name, value);
        return this;
    }
}
