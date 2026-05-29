package com.speedbet.api.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, UUID> {

    Page<WithdrawalRequest> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    Page<WithdrawalRequest> findByStatusOrderByCreatedAtDesc(WithdrawalStatus status, Pageable pageable);
    Page<WithdrawalRequest> findByAdminIdOrderByCreatedAtDesc(UUID adminId, Pageable pageable);
    Page<WithdrawalRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<WithdrawalRequest> findByIdAndUserId(UUID id, UUID userId);

    @Query("SELECT CASE WHEN COUNT(w) > 0 THEN true ELSE false END FROM WithdrawalRequest w " +
            "WHERE w.user.id = :userId AND w.status IN :statuses")
    boolean existsByUserIdAndStatusIn(
            @Param("userId")   UUID userId,
            @Param("statuses") List<WithdrawalStatus> statuses);

    long countByStatus(WithdrawalStatus status);

    @Query("SELECT COUNT(w) FROM WithdrawalRequest w WHERE w.user.id = :userId AND w.status = :status")
    long countByUserIdAndStatus(
            @Param("userId") UUID userId,
            @Param("status") WithdrawalStatus status);

    @Query("SELECT SUM(w.amount) FROM WithdrawalRequest w WHERE w.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") WithdrawalStatus status);

    @Query("""
        SELECT SUM(w.amount)
        FROM WithdrawalRequest w
        WHERE w.user.id   = :userId
          AND w.status    = :status
          AND w.createdAt >= :since
        """)
    BigDecimal sumAmountByUserIdAndStatusAndCreatedAtAfter(
            @Param("userId") UUID userId,
            @Param("status") WithdrawalStatus status,
            @Param("since")  Instant since);
}