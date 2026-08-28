-- ============================================================
-- V2__add_cashout_fields.sql
-- Adds cashout tracking columns to the bets table.
-- Runs automatically on next startup via Flyway.
-- ============================================================

ALTER TABLE bets
    ADD COLUMN IF NOT EXISTS cashed_out_amount NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS cashout_type      VARCHAR(10);

-- Extend the status check constraint to allow CASHED_OUT.
-- Drop and recreate only if your DB has an existing check constraint on status.
-- If you don't have one, these two lines are safe no-ops.
ALTER TABLE bets DROP CONSTRAINT IF EXISTS bets_status_check;
ALTER TABLE bets ADD CONSTRAINT bets_status_check
    CHECK (status IN ('PENDING', 'WON', 'LOST', 'VOID', 'CASHED_OUT'));