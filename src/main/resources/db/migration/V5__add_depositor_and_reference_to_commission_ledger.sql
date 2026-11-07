-- V5__add_depositor_and_reference_to_commission_ledger.sql
-- Adds the depositor and the deposit reference to commission ledger entries.
--
-- user_id: the referred user whose deposit generated the commission, so the
--   super-admin country report can attribute commission by the depositor's
--   country instead of bucketing every GHS-credited row as Ghana.
-- deposit_reference: the deposit's idempotency reference (the same ref the
--   wallet credit used), so re-attribution of the same deposit is
--   idempotent per (admin_id, deposit_reference).
--
-- Additive only: two new nullable columns + indexes. Nothing renamed,
-- nothing dropped, existing rows keep NULL in both columns and reports
-- fall back to currency / admin country for them.
--
-- NOTE: Flyway is currently disabled (spring.flyway.enabled=false) and the
-- schema is managed by Hibernate ddl-auto=update, so in practice the
-- matching entity fields create these columns on boot. This migration
-- keeps the schema reproducible if Flyway is ever re-enabled.

ALTER TABLE commission_ledger_entries
    ADD COLUMN IF NOT EXISTS user_id UUID,
    ADD COLUMN IF NOT EXISTS deposit_reference VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_cle_user_id
    ON commission_ledger_entries (user_id);

CREATE INDEX IF NOT EXISTS idx_cle_deposit_reference
    ON commission_ledger_entries (deposit_reference);
