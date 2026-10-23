package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Task 352 (story S4-07, "Người giới thiệu theo dõi được trạng thái ở mức tổng quát"): the referrer's own list,
 * GET /api/v1/referrals/my-referrals, newest first. Each item is the {@link ApplicationReferralView} the referral's
 * 201 answered (task 351), read again: the candidate's name as the referrer typed it, the posting-title snapshot, the
 * receipt, the public status of task 340 and the referrer's own note.
 * <ul>
 * <li>Who: an unexpired token of an active session and account (401), then SELF_PROFILE_READ read again from the
 * database (403), like AvatarService's reader. Every internal role holds it; being a referrer grants no candidate
 * read (CANDIDATES_READ_*).</li>
 * <li>What: only the applications the caller referred, filtered in SQL by application_referrals.referrer_id = the
 * caller; anyone else, the assigned recruiter included, gets []. Query parameters are not read: the caller cannot ask
 * for another referrer's list.</li>
 * <li>Never: the candidate's email, phone or CV, the internal stage or its name, the source label, staff, the
 * withdrawal reason, the tracking code or link. The query reads only the columns the view shows (and the stage,
 * which leaves this class only through PublicApplicationStatus.of).</li>
 * </ul>
 * One REPEATABLE READ read-only transaction: the reader check and the list see one snapshot.
 */
@Service
public class ApplicationReferralStatusService {
    // The referral row decides membership; the application row gives what the view shows.
    private static final String MY_REFERRALS = """
            SELECT a.id, a.full_name, a.posting_title, a.received_at, a.stage, r.note
            FROM application_referrals r JOIN job_applications a ON a.id = r.application_id
            WHERE r.referrer_id = :caller
            ORDER BY a.received_at DESC, a.id DESC
            """;

    private final AuthService auth;
    private final PermissionService permissions;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    ApplicationReferralStatusService(AuthService auth, PermissionService permissions, NamedParameterJdbcTemplate jdbc,
                                     Clock clock) {
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ApplicationReferralView> myReferrals(Jwt jwt) {
        UUID caller = requireReader(jwt);
        return jdbc.query(MY_REFERRALS, new MapSqlParameterSource("caller", caller),
                (row, number) -> ApplicationReferralView.of(row.getObject("id", UUID.class), row.getString("full_name"),
                        row.getString("posting_title"), row.getTimestamp("received_at").toInstant(),
                        ApplicationStage.valueOf(row.getString("stage")), row.getString("note")));
    }

    // The matcher already admitted PERM_SELF_PROFILE_READ; the code is read again here, before the list, so a grant
    // removed from role_permissions counts even for a request already past the filter (403 before anything is read).
    private UUID requireReader(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account caller = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(caller.getId()).contains("SELF_PROFILE_READ")) {
            throw new AccessDeniedException("The referral list requires SELF_PROFILE_READ");
        }
        return caller.getId();
    }
}
