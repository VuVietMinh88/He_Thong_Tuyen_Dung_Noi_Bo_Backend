package vn.ttcs.recruitment.notification;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.offer.OfferAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 544: who may still receive a notification, decided from current data when the worker plans it (an event can be
 * processed a while after it committed, or again after a failure). A recipient must be an active account (enabled, not
 * locked by an administrator) and must still hold what the screen the notification opens requires, checked with that
 * screen's own rule: a recipient who lost the role or the permission gets nothing.
 */
@Component
public class NotificationRecipients {
    private static final String ACTIVE =
            "SELECT id FROM user_accounts WHERE enabled AND admin_locked_at IS NULL AND id IN (:ids)";
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final PermissionService permissions;
    private final OfferAccess offers;

    public NotificationRecipients(NamedParameterJdbcTemplate jdbc, AccountRepository accounts,
                                  PermissionService permissions, OfferAccess offers) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.permissions = permissions;
        this.offers = offers;
    }

    /** The active accounts among ids, in their given order, each once. */
    public List<UUID> active(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Set<UUID> found = Set.copyOf(jdbc.queryForList(ACTIVE, Map.of("ids", ids), UUID.class));
        return new LinkedHashSet<>(ids).stream().filter(found::contains).toList();
    }

    /**
     * The named approver of an offer's current step may still decide it (OfferService.decide and approver): an active
     * account with the step's role (HR_MANAGER for PENDING_HR, APPROVER for PENDING_HIGHER) and OFFERS read and write.
     */
    public boolean approvesOffer(UUID user, Role role) {
        if (!accounts.findById(user).filter(Account::isAccessAllowed).map(account -> account.getRoles().contains(role))
                .orElse(false)) {
            return false;
        }
        Set<String> granted = permissions.forUser(user);
        return AccessScope.read(granted, PermissionModule.OFFERS) != AccessScope.NONE
                && AccessScope.write(granted, PermissionModule.OFFERS) != AccessScope.NONE;
    }

    /**
     * The user may still read the offers of the application, as the offer screen decides it for anyone who is not its
     * named approver (OfferAccess.scope: OFFERS and CANDIDATES read, and the application in the caller's scope).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean readsOffer(UUID user, UUID applicationId) {
        if (active(List.of(user)).isEmpty()) return false;
        try {
            offers.scope(user, applicationId, false);
            return true;
        } catch (AccessDeniedException | ApiException refused) {
            return false;
        }
    }
}
