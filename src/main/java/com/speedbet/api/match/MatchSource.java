package com.speedbet.api.match;

/**
 * Identifies which upstream provider supplied a Match's externalId.
 *
 * Used by MatchService to route detail/odds/standings calls back to the
 * correct client (since externalId formats differ between providers).
 *
 * NOTE: This enum is stored in Postgres as a NAMED_ENUM type. Adding values
 * here requires a corresponding Flyway migration:
 *   ALTER TYPE match_source ADD VALUE IF NOT EXISTS 'BSD';
 *   ALTER TYPE match_source ADD VALUE IF NOT EXISTS 'FOOTBALL_DATA';
 *   ALTER TYPE match_source ADD VALUE IF NOT EXISTS 'API_FOOTBALL';
 *
 * See: V7__add_match_source_values.sql
 */
public enum MatchSource {
    /** SportDB.dev (Flashscore) — string event ids; fixtures, match detail, standings. */
    SPORTDB,

    /** SportSRC v2.5 — string match ids; streaming sources only. */
    SPORTSRC,

    /** Bzzoiro Sports Data — numeric event ids; live scores, odds, predictions, player stats. */
    BSD,

    /** football-data.org v4 — numeric fixture ids; standings, today/upcoming fixtures, H2H. */
    FOOTBALL_DATA,

    /** API-Football v3 (api-sports.io) — numeric fixture ids. */
    API_FOOTBALL,

    /** Generated virtual matches (synthetic markets, e.g. for testing). */
    VIRTUAL,

    /** Manually created via the admin panel. */
    ADMIN_CREATED,
    LIVESCORE
}