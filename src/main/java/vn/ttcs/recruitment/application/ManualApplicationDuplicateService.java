package vn.ttcs.recruitment.application;

import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Task 408 (story S5-08, "Ứng viên thêm tay vẫn đi qua đúng luồng phát hiện trùng"): the duplicate check a staff member
 * runs before entering a candidate by hand, so the comparison happens before anything is stored. The same caller rule
 * as the entry itself (CANDIDATES read and write, the requisition in scope: ManualApplicationAccess), the storage's own
 * email and phone normalization (ApplicationSubmission), and the report of ManualApplicationDuplicates, in one
 * REPEATABLE READ snapshot together with the PROFILE disclosure log of every application it shows. Nothing is written
 * except that log.
 */
@Service
public class ManualApplicationDuplicateService {
    private final CandidateAccess access;
    private final PermissionService permissions;
    private final ManualApplicationAccess scope;
    private final ManualApplicationDuplicates duplicates;
    private final Validator validator;
    private final TransactionTemplate reads;

    ManualApplicationDuplicateService(CandidateAccess access, PermissionService permissions,
                                      ManualApplicationAccess scope, ManualApplicationDuplicates duplicates,
                                      Validator validator, PlatformTransactionManager transactions) {
        this.access = access;
        this.permissions = permissions;
        this.scope = scope;
        this.duplicates = duplicates;
        this.validator = validator;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(10);
    }

    public ManualDuplicateCheckView check(Jwt jwt, UUID requisitionId, ManualDuplicateCheckRequest request) {
        CandidateReader caller = access.requireReader(jwt);
        AccessScope.write(permissions.forUser(caller.id()), PermissionModule.CANDIDATES).orDeny();
        ApplicationSubmission probe = probe(request);
        return Objects.requireNonNull(reads.execute(status -> {
            CandidateReader reader = scope.requireRequisitionWriter(caller.id(), permissions.forUser(caller.id()),
                    requisitionId);
            var report = duplicates.report(reader, requisitionId, probe.normalizedEmail(), probe.phone(), null);
            recordDisclosed(access, reader, report);
            return new ManualDuplicateCheckView(report.emailInRequisition(), report.sameEmail(), report.samePhone());
        }));
    }

    // Every shown match discloses its candidate profile: one PROFILE row per application, grouped per candidate.
    static void recordDisclosed(CandidateAccess access, CandidateReader reader, ManualApplicationDuplicates.Report report) {
        Stream.concat(report.sameEmail().stream(), report.samePhone().stream())
                .collect(Collectors.groupingBy(ManualApplicationDuplicates.Match::candidateId,
                        Collectors.mapping(ManualApplicationDuplicates.Match::applicationId, Collectors.toList())))
                .forEach((candidate, applications) -> access.recordGranted(reader, CandidateAccessKind.PROFILE,
                        candidate, List.copyOf(new java.util.LinkedHashSet<>(applications))));
    }

    // The email and phone as storage would keep them, checked by the same constraints (400 naming the field).
    private ApplicationSubmission probe(ManualDuplicateCheckRequest request) {
        var submission = new ApplicationSubmission("x", request.email(), request.phone(), null, null);
        var errors = new LinkedHashMap<String, String>();
        Stream.concat(validator.validateProperty(submission, "email").stream(),
                        validator.validateProperty(submission, "phone").stream())
                .forEach(error -> errors.putIfAbsent(error.getPropertyPath().toString(), "Trường không hợp lệ."));
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin hồ sơ.",
                    errors);
        }
        return submission;
    }
}
