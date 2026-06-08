package com.speedbet.api.user;

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

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled")
    @Builder.Default
    private boolean totpEnabled = false;

    @Column(name = "totp_backup_codes", columnDefinition = "TEXT")
    private String totpBackupCodes;

    @Column(name = "email_verified")
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "verification_token")
    private String verificationToken;

    @Column(name = "reset_token")
    private String resetToken;

    @Column(name = "reset_token_expires_at")
    private LocalDateTime resetTokenExpiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
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