package com.speedbet.api.booking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookingCodeRepository extends JpaRepository<BookingCode, UUID> {
    Optional<BookingCode> findByCode(String code);
    boolean existsByCode(String code);
    Page<BookingCode> findByCreatorAdminIdOrderByCreatedAtDesc(UUID adminId, Pageable pageable);
}
