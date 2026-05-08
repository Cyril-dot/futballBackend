package com.speedbet.api.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByCreatedByAdminId(UUID adminId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdByAdminId = :adminId")
    long countByAdminId(UUID adminId);
}
