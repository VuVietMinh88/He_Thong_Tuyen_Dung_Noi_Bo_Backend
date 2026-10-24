package vn.ttcs.recruitment.reporting;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Story S8-08: time to hire and cost per hire (tasks 574-576). */
@RestController
public class TimeCostReportController {
    private final TimeCostReportService service;
    private final TimeCostWorkbook workbook;

    public TimeCostReportController(TimeCostReportService service, TimeCostWorkbook workbook) {
        this.service = service;
        this.workbook = workbook;
    }

    @GetMapping("/api/v1/reports/time-cost")
    public ResponseEntity<TimeCostReportView> report(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) UUID requisitionId, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID positionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.report(jwt, from, to, requisitionId, departmentId, positionId));
    }

    /** Task 576: the same report, same parameters, as an .xlsx attachment. */
    @GetMapping("/api/v1/reports/time-cost/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) UUID requisitionId, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID positionId) {
        TimeCostReportView report = service.report(jwt, from, to, requisitionId, departmentId, positionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(ReportSpreadsheet.MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(TimeCostWorkbook.filename(report)).build().toString())
                .body(workbook.workbook(report));
    }
}
