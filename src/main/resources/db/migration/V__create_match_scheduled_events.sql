-- ============================================================
-- Migration: match_scheduled_events
--
-- This table is the authoritative, durable store for every
-- lifecycle event in an automated match (kickoff, goals,
-- half-time, second half, finish).
--
-- AdminMatchScheduleService polls this table every 5 seconds
-- and executes any PENDING event whose fire_at ≤ NOW.
-- ============================================================

CREATE TABLE IF NOT EXISTS match_scheduled_events (
                                                      id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    match_id      UUID        NOT NULL,
    admin_id      UUID        NOT NULL,

    -- KICKOFF | GOAL | HALF_TIME | SECOND_HALF | FINISHED
    event_type    VARCHAR(20) NOT NULL,

    -- Wall-clock instant at which this event should fire
    fire_at       TIMESTAMPTZ NOT NULL,

    -- PENDING | CLAIMED | DONE | FAILED | CANCELLED
    --   PENDING   – waiting to be executed
    --   CLAIMED   – currently being executed by one poller node
    --   DONE      – executed successfully
    --   FAILED    – execution threw; will not be retried automatically
    --   CANCELLED – cancelled by admin before execution
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING',

    -- GOAL-specific (null for non-GOAL events)
    goal_minute   INT,
    goal_team     VARCHAR(4),       -- HOME | AWAY
    cum_home      INT,
    cum_away      INT,

    -- Target status (used for advanceStatusTo calls)
    -- For GOAL events: the status the match must be in for this minute
    -- For transition events: the status to advance TO
    target_status VARCHAR(15),

    -- FINISHED-event only: guaranteed final score
    final_home    INT,
    final_away    INT,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_match_scheduled_events PRIMARY KEY (id)
    );

-- Fast poller index: all PENDING events by fire_at
-- (partial index — only indexes rows the poller actually reads)
CREATE INDEX IF NOT EXISTS idx_mse_poll
    ON match_scheduled_events (fire_at ASC, status)
    WHERE status = 'PENDING';

-- Per-match inspection index
CREATE INDEX IF NOT EXISTS idx_mse_match
    ON match_scheduled_events (match_id, fire_at ASC);

COMMENT ON TABLE match_scheduled_events IS
    'Durable per-event schedule for automated match lifecycles. '
    'Polled every 5s by AdminMatchScheduleService.pollAndExecuteEvents().';