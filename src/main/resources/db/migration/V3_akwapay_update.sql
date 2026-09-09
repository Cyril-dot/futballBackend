-- V3__akwapay_update.sql
-- AkwaPay / NaloPay integration schema update
-- Runs automatically on deploy via Flyway

-- 1. Create akwapay_pending_intents table if it doesn't exist
CREATE TABLE IF NOT EXISTS akwapay_pending_intents (
                                                       reference        VARCHAR(100) PRIMARY KEY,
    intent_id        VARCHAR(100),
    user_id          UUID         NOT NULL,
    amount_ghs       NUMERIC(12,2) NOT NULL,
    admin_upgrade    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    attempts         INTEGER      NOT NULL DEFAULT 0,
    last_checked_at  TIMESTAMPTZ
    );

-- 2. Make intent_id nullable — recordPending() is now called AFTER
--    akwapayCreateIntent() succeeds so intent_id is always set,
--    but we drop NOT NULL as a safety net for edge cases.
ALTER TABLE akwapay_pending_intents
    ALTER COLUMN intent_id DROP NOT NULL;

-- 3. Indexes for the reconciliation sweep
CREATE INDEX IF NOT EXISTS idx_akwapay_pending_created_at
    ON akwapay_pending_intents (created_at ASC);

CREATE INDEX IF NOT EXISTS idx_akwapay_pending_user_id
    ON akwapay_pending_intents (user_id);

CREATE INDEX IF NOT EXISTS idx_akwapay_pending_intent_id
    ON akwapay_pending_intents (intent_id)
    WHERE intent_id IS NOT NULL;