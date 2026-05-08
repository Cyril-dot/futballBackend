package com.speedbet.api.sportsdata;

import com.speedbet.api.match.Match;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authoritative team whitelists for each Top-6 domestic league.
 *
 * Purpose:
 *   Prevents matches from appearing under the wrong league by validating that
 *   BOTH the home and away teams actually belong to that league's known squad.
 *
 * Usage in MatchService:
 *
 *   // Filter a list — only keep matches where both teams belong to their league
 *   List<Match> clean = Top6LeagueTeams.filterValid(matches);
 *
 *   // Check a single match
 *   boolean ok = Top6LeagueTeams.PREMIER_LEAGUE.isValidMatch(match);
 *
 *   // Resolve from league name (matches CompetitionIds.Top6League.displayName())
 *   Top6LeagueTeams.fromLeagueName("Premier League")
 *                  .ifPresent(t -> ...);
 *
 * Teams are kept in sync with the 2024/25 season squads.
 * Update each summer after promotion/relegation is confirmed.
 */
public enum Top6LeagueTeams {

    // ══════════════════════════════════════════════════════════════════════
    //  PREMIER LEAGUE — England  (20 clubs, 2024/25)
    // ══════════════════════════════════════════════════════════════════════
    PREMIER_LEAGUE(
        CompetitionIds.Top6League.PREMIER_LEAGUE,
        "Arsenal", "Aston Villa", "Bournemouth", "Brentford", "Brighton",
        "Brighton & Hove Albion", "Chelsea", "Crystal Palace", "Everton",
        "Fulham", "Ipswich Town", "Ipswich", "Leicester City", "Leicester",
        "Liverpool", "Manchester City", "Man City", "Manchester United", "Man United",
        "Man Utd", "Newcastle United", "Newcastle", "Nottingham Forest",
        "Southampton", "Tottenham Hotspur", "Tottenham", "Spurs",
        "West Ham United", "West Ham", "Wolverhampton Wanderers", "Wolves"
    ),

    // ══════════════════════════════════════════════════════════════════════
    //  LA LIGA — Spain  (20 clubs, 2024/25)
    // ══════════════════════════════════════════════════════════════════════
    LA_LIGA(
        CompetitionIds.Top6League.LA_LIGA,
        "Alaves", "Deportivo Alavés", "Athletic Bilbao", "Athletic Club",
        "Atletico Madrid", "Atlético de Madrid", "Atlético Madrid",
        "Barcelona", "FC Barcelona", "Betis", "Real Betis",
        "Celta Vigo", "RC Celta", "Espanyol", "RCD Espanyol",
        "Getafe", "Getafe CF", "Girona", "Girona FC",
        "Las Palmas", "UD Las Palmas", "Leganes", "CD Leganés",
        "Mallorca", "RCD Mallorca", "Osasuna", "CA Osasuna",
        "Rayo Vallecano", "Real Madrid", "Real Sociedad",
        "Sevilla", "Sevilla FC", "Valencia", "Valencia CF",
        "Valladolid", "Real Valladolid", "Villarreal", "Villarreal CF"
    ),

    // ══════════════════════════════════════════════════════════════════════
    //  BUNDESLIGA — Germany  (18 clubs, 2024/25)
    // ══════════════════════════════════════════════════════════════════════
    BUNDESLIGA(
        CompetitionIds.Top6League.BUNDESLIGA,
        "Augsburg", "FC Augsburg", "Bayer Leverkusen", "Leverkusen",
        "Bayern Munich", "FC Bayern München", "FC Bayern Munich",
        "Bochum", "VfL Bochum", "Borussia Dortmund", "Dortmund", "BVB",
        "Borussia Mönchengladbach", "Borussia M'gladbach", "Gladbach",
        "Eintracht Frankfurt", "Frankfurt",
        "Freiburg", "SC Freiburg", "Hamburg", "Hamburger SV", "HSV",
        "Heidenheim", "1. FC Heidenheim",
        "Hoffenheim", "TSG Hoffenheim", "Holstein Kiel", "Kiel",
        "Mainz", "1. FSV Mainz 05", "Mainz 05",
        "RB Leipzig", "Leipzig",
        "St. Pauli", "FC St. Pauli",
        "Stuttgart", "VfB Stuttgart",
        "Union Berlin", "1. FC Union Berlin",
        "Werder Bremen", "Werder", "Wolfsburg", "VfL Wolfsburg"
    ),

    // ══════════════════════════════════════════════════════════════════════
    //  SERIE A — Italy  (20 clubs, 2024/25)
    // ══════════════════════════════════════════════════════════════════════
    SERIE_A(
        CompetitionIds.Top6League.SERIE_A,
        "AC Milan", "Milan",
        "Atalanta", "Atalanta BC",
        "Bologna", "Bologna FC",
        "Cagliari", "Cagliari Calcio",
        "Como", "Como 1907",
        "Empoli", "FC Empoli",
        "Fiorentina", "ACF Fiorentina",
        "Genoa", "Genoa CFC",
        "Hellas Verona", "Verona",
        "Inter Milan", "Inter", "FC Internazionale", "Internazionale",
        "Juventus", "Juventus FC",
        "Lazio", "SS Lazio",
        "Lecce", "US Lecce",
        "Monza", "AC Monza",
        "Napoli", "SSC Napoli",
        "Parma", "Parma Calcio",
        "Roma", "AS Roma",
        "Torino", "Torino FC",
        "Udinese", "Udinese Calcio",
        "Venezia", "Venezia FC"
    ),

    // ══════════════════════════════════════════════════════════════════════
    //  LIGUE 1 — France  (18 clubs, 2024/25)
    // ══════════════════════════════════════════════════════════════════════
    LIGUE_1(
        CompetitionIds.Top6League.LIGUE_1,
        "Angers", "SCO Angers",
        "Auxerre", "AJ Auxerre",
        "Brest", "Stade Brestois", "Stade Brestois 29",
        "Lens", "RC Lens",
        "Lille", "LOSC Lille",
        "Lyon", "Olympique Lyonnais", "OL",
        "Marseille", "Olympique de Marseille", "OM",
        "Monaco", "AS Monaco",
        "Montpellier", "Montpellier HSC",
        "Nantes", "FC Nantes",
        "Nice", "OGC Nice",
        "Paris Saint-Germain", "PSG", "Paris SG",
        "Reims", "Stade de Reims",
        "Rennes", "Stade Rennais", "Stade Rennais FC",
        "Saint-Etienne", "AS Saint-Étienne", "AS Saint-Etienne",
        "Strasbourg", "RC Strasbourg", "RC Strasbourg Alsace",
        "Toulouse", "Toulouse FC"
    );

    // ─────────────────────────────────────────────────────────────────────

    private final CompetitionIds.Top6League league;

    /** All accepted team name variants, lower-cased for fast lookup. */
    private final Set<String> teams;

    Top6LeagueTeams(CompetitionIds.Top6League league, String... teamNames) {
        this.league = league;
        this.teams  = Arrays.stream(teamNames)
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** The Top6League this whitelist belongs to. */
    public CompetitionIds.Top6League league() { return league; }

    /** The league's display name (e.g. "Premier League"). */
    public String leagueName() { return league.displayName(); }

    /** Returns true if the given team name is a known member of this league. */
    public boolean containsTeam(String teamName) {
        if (teamName == null || teamName.isBlank()) return false;
        return teams.contains(teamName.strip().toLowerCase());
    }

    /**
     * Returns true if BOTH home and away teams belong to this league.
     * This is the main guard — a match is only valid if both sides are known members.
     */
    public boolean isValidMatch(Match match) {
        if (match == null) return false;
        return containsTeam(match.getHomeTeam()) && containsTeam(match.getAwayTeam());
    }

    /**
     * Returns true if this match's league name matches AND both teams are valid members.
     * Use this when the match already has a league field set.
     */
    public boolean isValidMatchForLeague(Match match) {
        if (match == null || match.getLeague() == null) return false;
        return league.displayName().equalsIgnoreCase(match.getLeague()) && isValidMatch(match);
    }

    // ── Static helpers ────────────────────────────────────────────────────

    /**
     * Resolves from a league display name (case-insensitive).
     * Matches CompetitionIds.Top6League.displayName() values.
     *
     * Example: Top6LeagueTeams.fromLeagueName("Premier League") → Optional[PREMIER_LEAGUE]
     */
    public static java.util.Optional<Top6LeagueTeams> fromLeagueName(String leagueName) {
        if (leagueName == null || leagueName.isBlank()) return java.util.Optional.empty();
        return Arrays.stream(values())
                .filter(t -> t.leagueName().equalsIgnoreCase(leagueName.strip()))
                .findFirst();
    }

    /**
     * Resolves from a Top6League enum directly.
     */
    public static java.util.Optional<Top6LeagueTeams> fromLeague(CompetitionIds.Top6League league) {
        return Arrays.stream(values())
                .filter(t -> t.league == league)
                .findFirst();
    }

    /**
     * Filters a list of matches, keeping only those where:
     *   1. The match's league is one of the Top-6 leagues, AND
     *   2. Both home and away teams are known members of that league.
     *
     * Matches with an unknown/null league are dropped.
     *
     * This is the primary method to call in MatchService.
     */
    public static java.util.List<Match> filterValid(java.util.List<Match> matches) {
        if (matches == null || matches.isEmpty()) return java.util.List.of();
        return matches.stream()
                .filter(Top6LeagueTeams::isKnownTop6Match)
                .toList();
    }

    /**
     * Returns true if the match belongs to a top-6 league AND
     * both teams are verified members of that league.
     */
    public static boolean isKnownTop6Match(Match match) {
        if (match == null || match.getLeague() == null) return false;
        return fromLeagueName(match.getLeague())
                .map(t -> t.isValidMatch(match))
                .orElse(false);
    }

    /**
     * Returns the set of raw team name variants registered for this league.
     * Useful for debugging / logging which names are accepted.
     */
    public Set<String> registeredTeams() { return teams; }
}