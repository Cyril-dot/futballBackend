package com.speedbet.api.sportsdata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache mapping team names and league names to logo URLs.
 *
 * Populated by LiveScorePoller via ingest() from competition-specific
 * API endpoints that return nested home.logo / away.logo fields.
 * The general fixtures endpoint never returns logos, so this cache
 * is used to backfill logo fields before persisting those fixtures.
 *
 * Thread-safe: backed by ConcurrentHashMap.
 */
@Slf4j
@Component
public class TeamLogoCache {

    // teamName (normalised lowercase) -> logo URL
    private final ConcurrentHashMap<String, String> teamLogos   = new ConcurrentHashMap<>();

    // leagueName (normalised lowercase) -> logo URL
    private final ConcurrentHashMap<String, String> leagueLogos = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // INGEST
    // Accepts a raw list of match/fixture event maps as returned by
    // LiveScoreApiClient and extracts any logo URLs it finds.
    // -------------------------------------------------------------------------

    /**
     * Walk a list of API event maps and store any home/away/league logos found.
     * Safe to call with null or empty lists.
     */
    public void ingest(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) return;
        int teamHits = 0, leagueHits = 0;
        for (Map<String, Object> event : events) {
            if (event == null) continue;
            teamHits   += ingestTeamLogo(event, "home");
            teamHits   += ingestTeamLogo(event, "away");
            leagueHits += ingestLeagueLogo(event);
        }
        if (teamHits > 0 || leagueHits > 0) {
            log.debug("TeamLogoCache.ingest: +{} team logos, +{} league logos (total team={}, league={})",
                    teamHits, leagueHits, teamLogos.size(), leagueLogos.size());
        }
    }

    // -------------------------------------------------------------------------
    // LOOKUP
    // -------------------------------------------------------------------------

    /**
     * Returns the cached logo URL for the given team name, or "" if not found.
     * Never returns null.
     */
    public String getTeamLogo(String teamName) {
        if (teamName == null || teamName.isBlank()) return "";
        return teamLogos.getOrDefault(normalise(teamName), "");
    }

    /**
     * Returns the cached logo URL for the given league name, or "" if not found.
     * Never returns null.
     */
    public String getLeagueLogo(String leagueName) {
        if (leagueName == null || leagueName.isBlank()) return "";
        return leagueLogos.getOrDefault(normalise(leagueName), "");
    }

    /** Total number of team + league entries in the cache. */
    public int size() {
        return teamLogos.size() + leagueLogos.size();
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    /**
     * Tries every known path pattern for a team logo inside an event map.
     * Returns 1 if a new entry was stored, 0 otherwise.
     *
     * Supported response shapes:
     *   { "home": { "name": "Arsenal", "logo": "https://..." } }
     *   { "home": { "name": "Arsenal", "image": "https://..." } }
     *   { "home_name": "Arsenal", "home_logo": "https://..." }
     *   { "home_name": "Arsenal", "home_image": "https://..." }
     */
    @SuppressWarnings("unchecked")
    private int ingestTeamLogo(Map<String, Object> event, String side) {
        // Pattern 1: nested object  { "home": { "name": ..., "logo": ... } }
        Object nested = event.get(side);
        if (nested instanceof Map) {
            Map<String, Object> sideMap = (Map<String, Object>) nested;
            String name = str(sideMap.get("name"));
            String logo = firstNonBlank(str(sideMap.get("logo")), str(sideMap.get("image")));
            if (!name.isBlank() && !logo.isBlank()) {
                teamLogos.put(normalise(name), logo);
                return 1;
            }
        }

        // Pattern 2: flat keys  "home_name" / "home_logo" or "home_image"
        String name = str(event.get(side + "_name"));
        String logo = firstNonBlank(str(event.get(side + "_logo")), str(event.get(side + "_image")));
        if (!name.isBlank() && !logo.isBlank()) {
            teamLogos.put(normalise(name), logo);
            return 1;
        }

        return 0;
    }

    /**
     * Tries every known path pattern for a league/competition logo.
     * Returns 1 if a new entry was stored, 0 otherwise.
     *
     * Supported shapes:
     *   { "competition": { "name": "Premier League", "logo": "https://..." } }
     *   { "league":      { "name": "Premier League", "logo": "https://..." } }
     *   { "competition_name": "Premier League", "competition_logo": "https://..." }
     *   { "league_name": "...", "league_logo": "..." }
     */
    @SuppressWarnings("unchecked")
    private int ingestLeagueLogo(Map<String, Object> event) {
        for (String key : new String[]{"competition", "league"}) {
            Object nested = event.get(key);
            if (nested instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) nested;
                String name = str(m.get("name"));
                String logo = firstNonBlank(str(m.get("logo")), str(m.get("image")));
                if (!name.isBlank() && !logo.isBlank()) {
                    leagueLogos.put(normalise(name), logo);
                    return 1;
                }
            }

            String name = str(event.get(key + "_name"));
            String logo = firstNonBlank(str(event.get(key + "_logo")), str(event.get(key + "_image")));
            if (!name.isBlank() && !logo.isBlank()) {
                leagueLogos.put(normalise(name), logo);
                return 1;
            }
        }
        return 0;
    }

    /** Null-safe Object to String, trimmed. */
    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /** Returns the first non-blank candidate, or "" if all are blank. */
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return "";
    }

    /** Lowercase + trim for map keys so lookups are case-insensitive. */
    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }
}