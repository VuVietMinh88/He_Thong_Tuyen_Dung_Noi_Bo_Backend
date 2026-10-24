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

/** Story S8-09: the quality of candidate sources (tasks 580-582). */
@RestController
public class SourceReportController {
    private final SourceReportService service;
    private final SourceWorkbook workbook;

    public SourceReportController(SourceReportService service, SourceWorkbook workbook) {
        this.service = service;
        this.workbook = workbook;
    }

    @GetMapping("/api/v1/reports/sources")
    public ResponseEntity<SourceReportView> report(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) UUID requisitionId, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID positionId, @RequestParam(required = false) Integer minApplications) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report(jwt, from, to,
                requisitionId, departmentId, positionId, minApplications));
    }

    /** Task 582: the same report, same parameters, as an .xlsx attachment. */
    @GetMapping("/api/v1/reports/sources/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) UUID requisitionId, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID positionId, @RequestParam(required = false) Integer minApplications) {
        SourceReportView report = service.report(jwt, from, to, requisitionId, departmentId, positionId, minApplications);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(ReportSpreadsheet.MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(SourceWorkbook.filename(report)).build().toString())
                .body(workbook.workbook(report));
    }
}
