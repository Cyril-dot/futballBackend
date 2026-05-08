package com.speedbet.api.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Modifying
    @Query("DELETE FROM AuditLog a WHERE a.createdAt < :cutoff")
    void deleteOlderThan(Instant cutoff);
}

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository auditRepo;

    public void log(UUID actorId, String action, String entity, UUID targetId,
                    Map<String, Object> before, Map<String, Object> after, String ip) {
        auditRepo.save(AuditLog.builder()
                .actorUserId(actorId).action(action).targetEntity(entity)
                .targetId(targetId).beforeState(before).afterState(after)
                .ipAddress(ip)
                .createdAt(Instant.now())  // ← add this
                .build());
    }

    public Page<AuditLog> getAll(Pageable pageable) {
        return auditRepo.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Scheduled(cron = "0 0 2 * * *") // Daily 02:00 UTC
    @Transactional
    public void compact() {
        var cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
        auditRepo.deleteOlderThan(cutoff);
    }
}
