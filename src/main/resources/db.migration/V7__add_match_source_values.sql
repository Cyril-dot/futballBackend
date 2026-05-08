-- V7__add_match_source_values.sql
-- Extends the existing Postgres `match_source` enum with the new providers
-- wired up in the Java MatchSource enum.
--
-- Postgres ALTER TYPE ... ADD VALUE is idempotent with IF NOT EXISTS (PG 9.6+).
-- These statements must run OUTSIDE a transaction block, so make sure this
-- migration file is set to `transactional = false` in your Flyway config,
-- OR rely on the default behaviour where Flyway runs each statement separately.

ALTER TYPE match_source ADD VALUE IF NOT EXISTS 'BSD';
ALTER TYPE match_source ADD VALUE IF NOT EXISTS 'FOOTBALL_DATA';
ALTER TYPE match_source ADD VALUE IF NOT EXISTS 'API_FOOTBALL';