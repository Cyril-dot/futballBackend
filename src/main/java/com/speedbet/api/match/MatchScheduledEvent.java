package com.speedbet.api.match;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a single lifecycle event in an automated match schedule.
 *
 * Every scheduled match writes a row per event (KICKOFF, GOAL×N, HALF_TIME,
 * SECOND_HALF, FINISHED) into this table when the match is created. The
 * AdminMatchScheduleService poller reads these rows every 5 seconds and
 * executes any that are PENDING and overdue. This makes the entire match
 * lifecycle durable across restarts.
 *
 * Required migration:
 * ─────────────────────────────────────────────────────────────────────────
 *   CREATE TABLE match_scheduled_events (
 *       id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
 *       match_id      UUID        NOT NULL,
 *       admin_id      UUID        NOT NULL,
 *       event_type    VARCHAR(20) NOT NULL,
 *       fire_at       TIMESTAMPTZ NOT NULL,
 *       status        VARCHAR(10) NOT NULL DEFAULT 'PENDING',
 *       goal_minute   INT,
 *       goal_team     VARCHAR(4),
 *       cum_home      INT,
 *       cum_away      INT,
 *       target_status VARCHAR(15),
 *       final_home    INT,
 *       final_away    INT,
 *       created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
 *   );
 *   CREATE INDEX idx_mse_poll  ON match_scheduled_events (fire_at, status)
 *       WHERE status = 'PENDING';
 *   CREATE INDEX idx_mse_match ON match_scheduled_events (match_id, fire_at);
 * ─────────────────────────────────────────────────────────────────────────
 */
@Entity
@Table(name = "match_scheduled_events")
@Getter
@Setter
public class MatchScheduledEvent {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The match this event belongs to. */
    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    /** Admin who owns the match (passed through to AdminMatchService calls). */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    /**
     * What kind of event this is.
     * One of: KICKOFF | GOAL | HALF_TIME | SECOND_HALF | FINISHED
     */
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    /** Wall-clock instant at which this event should fire. */
    @Column(name = "fire_at", nullable = false)
    private Instant fireAt;

    /**
     * Execution state.
     * PENDING — not yet executed (poller will pick it up).
     * DONE    — executed successfully.
     * FAILED  — execution threw; will NOT be retried automatically.
     * CANCELLED — cancelled by admin before execution.
     */
    @Column(name = "status", nullable = false, length = 10)
    private String status = "PENDING";

    // ── GOAL-specific fields (null for non-GOAL events) ───────────────────

    /** Match minute the goal is scored (1-44 or 46-89). */
    @Column(name = "goal_minute")
    private Integer goalMinute;

    /** Which team scored: "HOME" or "AWAY". */
    @Column(name = "goal_team", length = 4)
    private String goalTeam;

    /** Cumulative home score AFTER this goal. */
    @Column(name = "cum_home")
    private Integer cumHome;

    /** Cumulative away score AFTER this goal. */
    @Column(name = "cum_away")
    private Integer cumAway;

    // ── Status-transition fields ──────────────────────────────────────────

    /**
     * The match status this event should advance TO.
     * For GOAL events this is the status the match must already be in
     * (LIVE or SECOND_HALF) — used for self-healing via advanceStatusTo().
     */
    @Column(name = "target_status", length = 15)
    private String targetStatus;

    // ── FINISHED-event fields ─────────────────────────────────────────────

    /** Guaranteed final home score (only set on the FINISHED event). */
    @Column(name = "final_home")
    private Integer finalHome;

    /** Guaranteed final away score (only set on the FINISHED event). */
    @Column(name = "final_away")
    private Integer finalAway;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt = Instant.now();
}