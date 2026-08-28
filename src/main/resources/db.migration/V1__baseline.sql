-- ============================================================
-- V1__baseline.sql
-- Flyway baseline — marks the existing DB as already applied.
-- This file documents what was already created by ddl-auto=update.
-- Flyway will NOT re-run this because baseline-on-migrate=true
-- sets the baseline at version 0, so V1 runs once and is recorded.
-- ============================================================

-- This migration is intentionally a no-op.
-- Your existing tables (bets, bet_selections, wallets, transactions, etc.)
-- were already created by Hibernate's ddl-auto=update.
-- From V2 onwards, Flyway owns all schema changes.

SELECT 1;