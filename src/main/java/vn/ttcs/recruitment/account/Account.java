package vn.ttcs.recruitment.account;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
public class Account {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Column(length = 120)
    private String displayTitle;

    private UUID departmentId;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private int failedLoginAttempts;

    private Instant lockedUntil;

    private Instant adminLockedAt;

    @Column(length = 500)
    private String adminLockReason;

    private UUID adminLockedBy;

    @Column(nullable = false)
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Set<Role> roles = new HashSet<>();

    protected Account() {
    }

    public Account(String email, String fullName, String passwordHash, Set<Role> roles, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.roles = new HashSet<>(roles);
        this.createdAt = createdAt;
        this.enabled = true;
    }

    public static Account pendingActivation(String email, String fullName, String passwordHash,
                                             Set<Role> roles, Instant createdAt) {
        Account account = new Account(email, fullName, passwordHash, roles, createdAt);
        account.enabled = false;
        return account;
    }

    public void activate() {
        if (isAdministrativelyLocked()) {
            throw new InvalidActivationTokenException();
        }
        enabled = true;
        clearLoginFailures();
    }

    public boolean isAdministrativelyLocked() {
        return adminLockedAt != null;
    }

    public boolean isAccessAllowed() {
        return enabled && !isAdministrativelyLocked();
    }

    public void lockByAdministrator(String reason, UUID actorId, Instant now) {
        // Repeating a lock keeps the original administrator, time and reason.
        if (!isAdministrativelyLocked()) {
            adminLockReason = reason;
            adminLockedBy = actorId;
            adminLockedAt = now;
        }
    }

    public void unlockByAdministrator() {
        adminLockedAt = null;
        adminLockReason = null;
        adminLockedBy = null;
    }

    public boolean isLoginLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void resetExpiredLock(Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            clearLoginFailures();
        }
    }

    public void recordFailedLogin(Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
        }
    }

    public void clearLoginFailures() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    public void resetPassword(String encodedPassword) {
        passwordHash = encodedPassword;
        clearLoginFailures();
    }

    public void updateProfile(String fullName, String phone, String displayTitle) {
        this.fullName = fullName;
        this.phone = phone;
        this.displayTitle = displayTitle;
    }

    public void assignDepartment(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    public void removeRole(Role role) {
        roles.remove(role);
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public String getDisplayTitle() { return displayTitle; }
    public UUID getDepartmentId() { return departmentId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public Instant getAdminLockedAt() { return adminLockedAt; }
    public String getAdminLockReason() { return adminLockReason; }
    public UUID getAdminLockedBy() { return adminLockedBy; }
    public Set<Role> getRoles() { return Set.copyOf(roles); }
}
