package com.speedbet.api.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // ── Existing ──────────────────────────────────────────────────────────────

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email); // ← ADDED — fixes the build error

    // ── Super Admin additions ─────────────────────────────────────────────────

    /**
     * Paginated user search across email, firstName, lastName.
     * Pass null role to search all roles.
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (
                :search IS NULL
                OR LOWER(u.email)     LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.lastName)  LIKE LOWER(CONCAT('%', :search, '%'))
              )
            ORDER BY u.createdAt DESC
            """)
    Page<User> findAllFiltered(
            @Param("role")   UserRole role,
            @Param("search") String search,
            Pageable pageable
    );

    List<User> findAllByRole(UserRole role);

    long countByRole(UserRole role);
}