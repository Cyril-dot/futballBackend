package com.speedbet.api.ai;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "ai_predictions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiPrediction {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    @Builder.Default
    @Column(nullable = false)
    private String model = "mistral-large-latest";

    @Builder.Default
    @Column(name = "generated_at", updatable = false, nullable = false)
    private Instant generatedAt = Instant.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> prediction;

    @Column(name = "shared_at")
    private Instant sharedAt;

    @Column(name = "shared_by_admin_id")
    private UUID sharedByAdminId;

    @Builder.Default
    @Column(name = "is_published_to_users", nullable = false)
    private boolean publishedToUsers = false;

    @Column(name = "admin_note")
    private String adminNote;
}