package com.speedbet.api.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>,
        JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // keep these — still used elsewhere
    List<User> findAllByRole(UserRole role);
    long countByRole(UserRole role);

    // Used by WithdrawalService to email all admins on approve/reject
    List<User> findByRoleIn(List<UserRole> roles);

    // findAllFiltered is no longer needed — SuperAdminQueryService
    // now uses Specification<User> instead of calling this method.
    // You can safely delete it, or leave it — it just won't be called.
}