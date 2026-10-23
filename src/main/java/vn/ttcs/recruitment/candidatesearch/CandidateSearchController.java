package vn.ttcs.recruitment.candidatesearch;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Task 392 (story S5-05): the candidate search. Lists repeat their parameter (stage=NEW&stage=SCREENING). */
@RestController
public class CandidateSearchController {
    private final CandidateSearchService service;

    public CandidateSearchController(CandidateSearchService service) { this.service = service; }

    @GetMapping("/api/v1/candidate-search")
    public ResponseEntity<CandidateSearchView> search(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID requisitionId,
            @RequestParam(required = false) UUID positionId,
            @RequestParam(name = "stage", required = false) List<ApplicationStage> stages,
            @RequestParam(name = "sourceId", required = false) List<UUID> sourceIds,
            @RequestParam(name = "tagId", required = false) List<UUID> tagIds,
            @RequestParam(required = false) TagMatch tagMatch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedTo,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String coverLetterKeyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var criteria = new CandidateSearchCriteria(requisitionId, positionId, stages, sourceIds, tagIds, tagMatch,
                submittedFrom, submittedTo, minRating, maxRating, keyword, coverLetterKeyword);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(jwt, criteria, page, size));
    }
}
