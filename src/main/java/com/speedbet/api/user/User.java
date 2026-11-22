package com.speedbet.api.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_email", columnList = "email"),
                @Index(name = "idx_users_phone", columnList = "phone"),
                @Index(name = "idx_users_role", columnList = "role"),
                @Index(name = "idx_users_status", columnList = "status"),
                @Index(name = "idx_users_created_at", columnList = "created_at"),
                @Index(name = "idx_users_updated_at", columnList = "updated_at"),
                @Index(name = "idx_users_created_by_admin_id", columnList = "created_by_admin_id"),
                @Index(name = "idx_users_referred_via_link_id", columnList = "referred_via_link_id"),
                @Index(name = "idx_users_email_verified", columnList = "email_verified"),
                @Index(name = "idx_users_email_verification_due_at", columnList = "email_verification_due_at"),
                @Index(name = "idx_users_verification_token", columnList = "verification_token"),
                @Index(name = "idx_users_reset_token", columnList = "reset_token"),
                @Index(name = "idx_users_role_status", columnList = "role,status"),
                @Index(name = "idx_users_status_created_at", columnList = "status,created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    private String phone;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_by_admin_id")
    private UUID createdByAdminId;

    @Column(name = "referred_via_link_id")
    private UUID referredViaLinkId;

    @Column(name = "theme_preference")
    @Builder.Default
    private String themePreference = "light";

    @Column(name = "win_seen")
    @Builder.Default
    private boolean winSeen = true;

    @JsonIgnore
    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled")
    @Builder.Default
    private boolean totpEnabled = false;

    @JsonIgnore
    @Column(name = "totp_backup_codes", columnDefinition = "TEXT")
    private String totpBackupCodes;

    @Column(name = "email_verified")
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /**
     * MSport only: true when Reoon said this account's email is invalid
     * or disposable (checked at send-verification; registration with a
     * fake email is refused outright instead). A flagged account is
     * logged out of MSport (401 EMAIL_INVALID) until the owner updates
     * to a valid email and verifies it. Other skins never set this.
     */
    @Column(name = "email_invalid")
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean emailInvalid = false;

    /**
     * MSport only: true once Reoon has checked this account's stored
     * email (at signup, at send-verification, or once at login for
     * pre-existing accounts). Prevents the login-time check from
     * burning a Reoon credit on every login — one check per account.
     * Other skins never set this.
     */
    @Column(name = "email_reoon_checked")
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean emailReoonChecked = false;

    /** MSport only: the new email awaiting verification (see /api/auth/update-email-request). Null normally. */
    @JsonIgnore
    @Column(name = "pending_email")
    private String pendingEmail;

    /**
     * MSport only: the deadline by which a non-staff user must verify
     * their email (registration, or first MSport login for pre-existing
     * accounts — the 24h clock starts when they are first told). Null
     * once verified, and for staff and non-MSport accounts. Past this
     * time an unverified account is logged out, blocked and permanently
     * deleted by the hourly expiry job (funded accounts are blocked
     * only — their money records are never deleted). Updating the email
     * does NOT extend the deadline.
     */
    @Column(name = "email_verification_due_at")
    private LocalDateTime emailVerificationDueAt;

    /**
     * MSport only: true once the 24h email-verification deadline has
     * passed without verification. A blocked account cannot log in or
     * call any MSport API (403 ACCOUNT_BLOCKED) until it verifies —
     * verifying clears this flag again. Set by the hourly expiry job
     * (funded accounts stop here, blocked only) and by the delete path.
     * Other skins never set this.
     */
    @Column(name = "account_blocked")
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean accountBlocked = false;

    @JsonIgnore
    @Column(name = "verification_token")
    private String verificationToken;

    @JsonIgnore
    @Column(name = "reset_token")
    private String resetToken;

    @Column(name = "reset_token_expires_at")
    private LocalDateTime resetTokenExpiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Null-safe accessors for the three verification flags. All three
    // were added in a single day (b210363 / e51a75b / 082f974) as
    // primitive booleans while the schema is managed by
    // ddl-auto=update, which adds a column with no default and leaves
    // existing rows NULL. Hibernate cannot load NULL into a primitive,
    // so any pre-existing account 500s at login in
    // DaoAuthenticationProvider.retrieveUser. The live stack trace
    // named accountBlocked, but the same NULL sits in the other two
    // columns for accounts created before those commits, and the
    // 15:56 login 500s predate accountBlocked entirely — so all three
    // are Boolean now, NULL reads as false, and PostLoad normalises
    // NULL to false in memory so the next save backfills the column
    // (the expiry job query only matches real false values).
    public boolean isEmailInvalid() { return Boolean.TRUE.equals(emailInvalid); }
    public void setEmailInvalid(boolean emailInvalid) { this.emailInvalid = emailInvalid; }
    public boolean isEmailReoonChecked() { return Boolean.TRUE.equals(emailReoonChecked); }
    public void setEmailReoonChecked(boolean emailReoonChecked) { this.emailReoonChecked = emailReoonChecked; }
    public boolean isAccountBlocked() { return Boolean.TRUE.equals(accountBlocked); }
    public void setAccountBlocked(boolean accountBlocked) { this.accountBlocked = accountBlocked; }

    @PostLoad
    protected void normalizeNullFlags() {
        if (emailInvalid == null) emailInvalid = false;
        if (emailReoonChecked == null) emailReoonChecked = false;
        if (accountBlocked == null) accountBlocked = false;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @JsonIgnore
    @Override public String getPassword()               { return passwordHash; }
    @Override public String getUsername()               { return email; }
    @Override public boolean isAccountNonExpired()      { return true; }
    @Override public boolean isAccountNonLocked()       { return status != UserStatus.LOCKED; }
    @Override public boolean isCredentialsNonExpired()  { return true; }
    @Override public boolean isEnabled()                { return status == UserStatus.ACTIVE; }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}