package com.speedbet.api.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {
    List<OtpCode> findByPhoneAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(String phone, LocalDateTime now);
}
