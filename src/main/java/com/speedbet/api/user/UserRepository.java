package com.speedbet.api.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>,
        JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    // Phone is not unique, so this returns a list: login by phone only
    // succeeds when exactly one account matches.
    List<User> findByPhone(String phone);

    boolean existsByEmail(String email);

    /**
     * MSport only: non-staff accounts whose 24h email-verification
     * deadline has passed while still unverified, and which the hourly
     * expiry job has not already blocked. Used by
     * EmailVerificationExpiryScheduler — staff never have a deadline,
     * so the role filter is belt-and-braces.
     */
    List<User> findByRoleAndEmailVerificationDueAtBeforeAndEmailVerifiedFalseAndAccountBlockedFalse(
            UserRole role, LocalDateTime now);

    /**
     * MSport only: unverified accounts, newest first — the super-admin
     * manual-verify tab (SuperAdminEmailVerificationController). The
     * list spans all skins (User carries no skin marker); the tab is
     * where the super admin finds an unverified account and verifies
     * it by hand.
     */
    Page<User> findByEmailVerifiedFalse(Pageable pageable);

    // keep these — still used elsewhere
    List<User> findAllByRole(UserRole role);
    long countByRole(UserRole role);

    // Used by WithdrawalService to email all admins on approve/reject
    List<User> findByRoleIn(List<UserRole> roles);

    // findAllFiltered is no longer needed — SuperAdminQueryService
    // now uses Specification<User> instead of calling this method.
    // You can safely delete it, or leave it — it just won't be called.
}