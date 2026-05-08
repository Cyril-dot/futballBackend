package com.speedbet.api.sportsdata;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Authoritative registry of all competition IDs used by the LiveScore API.
 *
 * ── Structure ────────────────────────────────────────────────────────────
 *
 *   All IDs are sourced directly from:
 *     GET /api-client/competitions/list.json
 *
 *   Three enum types are provided:
 *
 *   1. {@link Top6League}        — The "Big 6" elite domestic leagues + UEFA Champions League.
 *                                  These IDs are stable and intentionally kept separate so
 *                                  they can never accidentally be confused with other leagues
 *                                  of the same name (e.g. there are many "Premier League"
 *                                  competitions in the API — only England's gets id=2 here).
 *
 *   2. {@link LeagueCompetition} — Every other notable domestic league tracked by the platform.
 *
 *   3. {@link CupCompetition}    — All cup / knockout competitions: UEFA cups, domestic cups
 *                                  for top-6 nations (England, Spain, Germany, Italy, France),
 *                                  super cups, community shields, and beyond.
 *
 * ── Usage ────────────────────────────────────────────────────────────────
 *
 *   Replace every raw integer or free-text league name in the codebase with:
 *
 *     Top6League.PREMIER_LEAGUE.id()              // → 2
 *     CupCompetition.FA_CUP.id()                  // → 152
 *     LeagueCompetition.EREDIVISIE.displayName()  // → "Eredivisie"
 *
 *   Lookup by ID at runtime:
 *
 *     Top6League.fromId(2)                        // → Optional[PREMIER_LEAGUE]
 *     CupCompetition.fromId(244)                  // → Optional[CHAMPIONS_LEAGUE]
 *
 * ── Naming convention ────────────────────────────────────────────────────
 *
 *   • Enum constant  : SCREAMING_SNAKE_CASE, descriptive and unique
 *   • displayName    : human-readable label used in UI / logs / DB league column
 *   • id             : integer competition ID from livescore-api.com
 *
 * ── Important note on duplicate names ───────────────────────────────────
 *
 *   The LiveScore API reuses competition names across countries (e.g. "Premier League",
 *   "Bundesliga", "Ligue 1", "Champions League"). Only the IDs below are unambiguous.
 *   Always reference competitions by their enum constant or integer ID — never by name
 *   string alone when calling the API.
 */
public final class CompetitionIds {

    private CompetitionIds() {}

    // ══════════════════════════════════════════════════════════════════════
    //  TOP-6 LEAGUES  (kept separate — stable, highest-priority set)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The six elite European domestic leagues plus the UEFA Champions League.
     * These are the competitions that define the core product offering.
     *
     * IDs verified against API response (competitions/list.json).
     */
    public enum Top6League {

        // ── England ───────────────────────────────────────────────────────
        PREMIER_LEAGUE      ("Premier League",    2),

        // ── Spain ─────────────────────────────────────────────────────────
        LA_LIGA             ("La Liga",           3),   // "LaLiga Santander" in API

        // ── Germany ───────────────────────────────────────────────────────
        BUNDESLIGA          ("Bundesliga",        1),   // Germany's Bundesliga (id=1), not Austria (id=43)

        // ── Italy ─────────────────────────────────────────────────────────
        SERIE_A             ("Serie A",           4),

        // ── France ────────────────────────────────────────────────────────
        LIGUE_1             ("Ligue 1",           5),   // France's Ligue 1, not Algeria (35) or others

        // ── UEFA ──────────────────────────────────────────────────────────
        CHAMPIONS_LEAGUE    ("Champions League",  244); // UEFA Champions League

        private final String displayName;
        private final int    id;

        Top6League(String displayName, int id) {
            this.displayName = displayName;
            this.id          = id;
        }

        public String displayName() { return displayName; }
        public int    id()          { return id; }

        private static final Map<Integer, Top6League> BY_ID =
                Arrays.stream(values()).collect(Collectors.toMap(Top6League::id, e -> e));

        public static Optional<Top6League> fromId(int id)    { return Optional.ofNullable(BY_ID.get(id)); }
        public static Map<String, Integer> asNameToIdMap()   {
            return Arrays.stream(values()).collect(Collectors.toMap(Top6League::displayName, Top6League::id));
        }
        public static int[] allIds() {
            return Arrays.stream(values()).mapToInt(Top6League::id).toArray();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CUP COMPETITIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cup and knockout competitions.
     *
     * Grouped into:
     *   A) UEFA club competitions  (Champions League is also in Top6League but included
     *      here for cup-context queries such as "all live cup matches")
     *   B) English cups
     *   C) Spanish cups
     *   D) German cups
     *   E) Italian cups
     *   F) French cups
     *   G) Scottish cups  (Scottish clubs compete in European cups alongside top-6 nations)
     *   H) Dutch cups     (Netherlands cups — Eredivisie teams in Europa / Conference League)
     *   I) Portuguese cups
     *   J) Other notable international / UEFA cups
     */
    public enum CupCompetition {

        // ── A) UEFA club competitions ─────────────────────────────────────
        CHAMPIONS_LEAGUE        ("Champions League",        244),
        EUROPA_LEAGUE           ("Europa League",           245),
        CONFERENCE_LEAGUE       ("UEFA Conference League",  446),
        UEFA_SUPER_CUP          ("UEFA Super Cup",          349),

        // ── B) English cups ───────────────────────────────────────────────
        FA_CUP                  ("FA Cup",                  152),  // England & Wales
        EFL_CUP                 ("EFL Cup",                 150),  // Carabao Cup
        EFL_TROPHY              ("EFL Trophy",              151),  // Papa John's Trophy
        COMMUNITY_SHIELD        ("Community Shield",        149),
        FA_TROPHY               ("FA Trophy",               153),

        // ── C) Spanish cups ───────────────────────────────────────────────
        COPA_DEL_REY            ("Copa del Rey",            334),
        SPANISH_SUPER_CUP       ("Spanish Super Cup",       333),

        // ── D) German cups ────────────────────────────────────────────────
        DFB_CUP                 ("DFB Cup",                 167),
        GERMAN_SUPER_CUP        ("German Super Cup",        169),  // DFL Supercup

        // ── E) Italian cups ───────────────────────────────────────────────
        COPPA_ITALIA            ("Coppa Italia",            179),
        ITALIAN_SUPER_CUP       ("Italian Super Cup",       178),
        COPPA_ITALIA_SERIE_C    ("Coppa Italia Serie C",    180),

        // ── F) French cups ────────────────────────────────────────────────
        COUPE_DE_FRANCE         ("Coupe de France",         162),
        FRENCH_LEAGUE_CUP       ("French League Cup",       163),
        FRENCH_SUPER_CUP        ("French Super Cup",        160),

        // ── G) Scottish cups ──────────────────────────────────────────────
        SCOTTISH_CUP            ("Scottish Cup",            105),
        SCOTTISH_LEAGUE_CUP     ("Scottish League Cup",     320),
        SCOTTISH_CHALLENGE_CUP  ("Scottish Challenge Cup",  316),

        // ── H) Dutch cups ─────────────────────────────────────────────────
        KNVB_BEKER              ("KNVB Beker",              198),
        DUTCH_SUPER_CUP         ("Dutch Super Cup",         197),

        // ── I) Portuguese cups ────────────────────────────────────────────
        TACA_DE_PORTUGAL        ("Taça de Portugal",        212),
        PORTUGUESE_LEAGUE_CUP   ("Portuguese League Cup",   213),
        PORTUGUESE_SUPER_CUP    ("Portuguese Super Cup",    211),

        // ── J) Belgian cups ───────────────────────────────────────────────
        BEKER_VAN_BELGIE        ("Beker Van Belgie",         99),
        BELGIAN_SUPER_CUP       ("Belgian Super Cup",       137),

        // ── K) Welsh cup ──────────────────────────────────────────────────
        WELSH_CUP               ("Welsh Cup",               104),

        // ── L) Northern Irish cup ─────────────────────────────────────────
        IRISH_CUP               ("Irish Cup",               103),  // Northern Ireland

        // ── M) Republic of Ireland cups ───────────────────────────────────
        FAI_CUP                 ("FAI Cup",                 217),
        FAI_PRESIDENTS_CUP      ("FAI Presidents Cup",      215),

        // ── N) Turkish cup ────────────────────────────────────────────────
        TURKISH_CUP             ("Turkish Cup",             347),
        TURKISH_SUPER_CUP       ("Turkish Super Cup",       348),

        // ── O) Greek cup ──────────────────────────────────────────────────
        GREEK_CUP               ("Greek Cup",               171),
        GREEK_SUPER_CUP         ("Greek Super Cup",         518),

        // ── P) Danish cup ─────────────────────────────────────────────────
        DBU_POKALEN             ("DBU Pokalen",             102),

        // ── Q) Norwegian cup ─────────────────────────────────────────────
        NM_CUPEN                ("NM Cupen",                206),

        // ── R) Swedish cup ────────────────────────────────────────────────
        SVENSKA_CUPEN           ("Svenska Cupen",           335),

        // ── S) Austrian cup ───────────────────────────────────────────────
        OFB_CUP                 ("ÖFB Cup",                 100),

        // ── T) Swiss cup ─────────────────────────────────────────────────
        SWISS_CUP               ("Swiss Cup",               340),

        // ── U) Russian cup ────────────────────────────────────────────────
        RUSSIAN_CUP             ("Russian Cup",             311),

        // ── V) Cypriot cup ───────────────────────────────────────────────
        CYPRIOT_CUP             ("Cypriot Cup",             101),

        // ── W) Israeli cups ──────────────────────────────────────────────
        TOTO_CUP_LIGAT_AL       ("Toto Cup Ligat Al",       495);

        private final String displayName;
        private final int    id;

        CupCompetition(String displayName, int id) {
            this.displayName = displayName;
            this.id          = id;
        }

        public String displayName() { return displayName; }
        public int    id()          { return id; }

        private static final Map<Integer, CupCompetition> BY_ID =
                Arrays.stream(values()).collect(Collectors.toMap(CupCompetition::id, e -> e));

        public static Optional<CupCompetition> fromId(int id) { return Optional.ofNullable(BY_ID.get(id)); }
        public static Map<String, Integer> asNameToIdMap() {
            return Arrays.stream(values()).collect(Collectors.toMap(CupCompetition::displayName, CupCompetition::id));
        }
        public static int[] allIds() {
            return Arrays.stream(values()).mapToInt(CupCompetition::id).toArray();
        }

        // ── Convenience sub-sets ──────────────────────────────────────────

        /** UEFA + domestic cups directly linked to top-6 league nations (Eng, Spa, Ger, Ita, Fra) + UEFA cups. */
        public static CupCompetition[] top6Related() {
            return new CupCompetition[]{
                    CHAMPIONS_LEAGUE, EUROPA_LEAGUE, CONFERENCE_LEAGUE, UEFA_SUPER_CUP,
                    FA_CUP, EFL_CUP, EFL_TROPHY, COMMUNITY_SHIELD, FA_TROPHY,
                    COPA_DEL_REY, SPANISH_SUPER_CUP,
                    DFB_CUP, GERMAN_SUPER_CUP,
                    COPPA_ITALIA, ITALIAN_SUPER_CUP, COPPA_ITALIA_SERIE_C,
                    COUPE_DE_FRANCE, FRENCH_LEAGUE_CUP, FRENCH_SUPER_CUP
            };
        }

        /** All English cup competitions. */
        public static CupCompetition[] english() {
            return new CupCompetition[]{ FA_CUP, EFL_CUP, EFL_TROPHY, COMMUNITY_SHIELD, FA_TROPHY };
        }

        /** All UEFA club competitions. */
        public static CupCompetition[] uefa() {
            return new CupCompetition[]{ CHAMPIONS_LEAGUE, EUROPA_LEAGUE, CONFERENCE_LEAGUE, UEFA_SUPER_CUP };
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DOMESTIC LEAGUES (beyond Top-6)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Domestic league competitions beyond the top-6 set.
     * Useful for broader fixture polling, standings, and bet-slip coverage.
     */
    public enum LeagueCompetition {

        // ── English pyramid ───────────────────────────────────────────────
        CHAMPIONSHIP            ("Championship",            77),
        LEAGUE_ONE_ENG          ("League One",              82),
        LEAGUE_TWO_ENG          ("League Two",              83),
        NATIONAL_LEAGUE_ENG     ("National League",         154),

        // ── Spanish ───────────────────────────────────────────────────────
        SEGUNDA_DIVISION        ("Segunda División",        79),
        SEGUNDA_B               ("Segunda B",               332),

        // ── German ────────────────────────────────────────────────────────
        ZWEITE_BUNDESLIGA       ("2nd Bundesliga",          93),
        DRITTE_LIGA             ("3. Liga",                 166),

        // ── Italian ───────────────────────────────────────────────────────
        SERIE_B                 ("Serie B",                 87),
        SERIE_C                 ("Serie C",                 181),

        // ── French ───────────────────────────────────────────────────────
        LIGUE_2                 ("Ligue 2",                 97),
        NATIONAL_1              ("National 1",              161),

        // ── Scottish ──────────────────────────────────────────────────────
        SCOTTISH_PREMIERSHIP    ("Scottish Premiership",    75),
        SCOTTISH_CHAMPIONSHIP   ("Scottish Championship",   317),
        SCOTTISH_LEAGUE_ONE     ("Scottish League One",     318),
        SCOTTISH_LEAGUE_TWO     ("Scottish League Two",     319),

        // ── Dutch ─────────────────────────────────────────────────────────
        EREDIVISIE              ("Eredivisie",              196),
        EERSTE_DIVISIE          ("Eerste Divisie",          199),
        TWEEDE_DIVISIE          ("Tweede Divisie",          447),

        // ── Portuguese ───────────────────────────────────────────────────
        PRIMEIRA_LIGA           ("Primeira Liga",           8),
        SEGUNDA_LIGA_POR        ("Segunda Liga",            92),

        // ── Belgian ───────────────────────────────────────────────────────
        JUPILER_PRO_LEAGUE      ("Jupiler Pro League",      68),  // First Division A
        FIRST_DIVISION_B        ("First Division B",        136),

        // ── Turkish ──────────────────────────────────────────────────────
        SUPER_LIG               ("Süper Lig",               6),
        FIRST_LIG               ("1. Lig",                  344),

        // ── Greek ─────────────────────────────────────────────────────────
        SUPER_LEAGUE_GREECE     ("Super League Greece",     9),
        SUPER_LEAGUE_2          ("Super League 2",          173),

        // ── Russian ───────────────────────────────────────────────────────
        RUSSIAN_PREMIER_LEAGUE  ("Russian Premier League",  7),
        RUSSIAN_FNL             ("Football National League",309),

        // ── Ukrainian ────────────────────────────────────────────────────
        UKRAINIAN_PREMIER       ("Ukrainian Premier League",64),

        // ── Swiss ─────────────────────────────────────────────────────────
        SWISS_SUPER_LEAGUE      ("Swiss Super League",      15),

        // ── Austrian ─────────────────────────────────────────────────────
        AUSTRIAN_BUNDESLIGA     ("Austrian Bundesliga",     43),

        // ── Danish ───────────────────────────────────────────────────────
        SUPERLIGA_DEN           ("Superliga",               40),

        // ── Norwegian ────────────────────────────────────────────────────
        ELITESERIEN             ("Eliteserien",             13),

        // ── Swedish ───────────────────────────────────────────────────────
        ALLSVENSKAN             ("Allsvenskan",             14),

        // ── Finnish ──────────────────────────────────────────────────────
        VEIKKAUSLIIGA           ("Veikkausliiga",           57),

        // ── Polish ────────────────────────────────────────────────────────
        EKSTRAKLASA             ("Ekstraklasa",             60),

        // ── Czech ─────────────────────────────────────────────────────────
        CZECH_FIRST_LEAGUE      ("Czech First League",      72),

        // ── Slovak ───────────────────────────────────────────────────────
        SLOVAK_SUPER_LEAGUE     ("Slovak Super League",     63),

        // ── Hungarian ────────────────────────────────────────────────────
        NB_I                    ("NB I",                    19),

        // ── Romanian ─────────────────────────────────────────────────────
        LIGA_I                  ("Liga I",                  61),

        // ── Serbian ───────────────────────────────────────────────────────
        SUPER_LIGA_SRB          ("Serbian SuperLiga",       62),

        // ── Croatian ─────────────────────────────────────────────────────
        HNL                     ("1. HNL",                  17),

        // ── Slovenian ────────────────────────────────────────────────────
        PRVA_LIGA_SLO           ("Prva Liga Slovenia",      22),

        // ── Bulgarian ────────────────────────────────────────────────────
        FIRST_PROFESSIONAL      ("First Professional League",71),

        // ── Israeli ───────────────────────────────────────────────────────
        LIGAT_HAAL              ("Ligat HaAl",              73),

        // ── Welsh ─────────────────────────────────────────────────────────
        WELSH_PREMIER_LEAGUE    ("Welsh Premier League",    445),

        // ── Northern Irish ────────────────────────────────────────────────
        NIFL_PREMIERSHIP        ("NIFL Premiership",        69),

        // ── Republic of Ireland ───────────────────────────────────────────
        LOI_PREMIER_DIVISION    ("League of Ireland Premier Division", 11),

        // ── MLS / Americas ───────────────────────────────────────────────
        MLS                     ("Major League Soccer",     76),
        LIGA_MX                 ("Liga MX",                 45),
        LIGA_PROFESSIONAL_ARG   ("Liga Professional",       23),  // Argentine Primera
        SERIE_A_BRA             ("Serie A (Brazil)",        24),
        BRASILEIRAO_B           ("Série B (Brazil)",        95),

        // ── Asian / Middle East ───────────────────────────────────────────
        J_LEAGUE                ("J. League",               28),
        K_LEAGUE_1              ("K-League 1",              66),
        SUPER_LIG_TUR           ("Super Lig Turkey",        6),   // alias — same as SUPER_LIG above for lookup compat
        A_LEAGUE_AUS            ("A-League",                67),
        INDIAN_SUPER_LEAGUE     ("Indian Super League",     65),
        CHINESE_SUPER_LEAGUE    ("Chinese Super League",    26),
        SAUDI_PREMIER_LEAGUE    ("Saudi Premier League",    313),

        // ── African ───────────────────────────────────────────────────────
        BOTOLA_PRO              ("Botola Pro",              38),
        GHANA_PREMIER_LEAGUE    ("Ghana Premier League",    86),
        NPFL_NIGERIA            ("NPFL Nigeria",            78);

        private final String displayName;
        private final int    id;

        LeagueCompetition(String displayName, int id) {
            this.displayName = displayName;
            this.id          = id;
        }

        public String displayName() { return displayName; }
        public int    id()          { return id; }

        private static final Map<Integer, LeagueCompetition> BY_ID =
                Arrays.stream(values()).collect(Collectors.toMap(
                        LeagueCompetition::id, e -> e,
                        (a, b) -> a  // keep first on duplicate id (SUPER_LIG_TUR alias)
                ));

        public static Optional<LeagueCompetition> fromId(int id) { return Optional.ofNullable(BY_ID.get(id)); }
        public static Map<String, Integer> asNameToIdMap() {
            return Arrays.stream(values()).collect(Collectors.toMap(
                    LeagueCompetition::displayName, LeagueCompetition::id,
                    (a, b) -> a
            ));
        }
        public static int[] allIds() {
            return Arrays.stream(values()).mapToInt(LeagueCompetition::id).distinct().toArray();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GLOBAL LOOKUP HELPERS  (searches all three enums)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolves any known competition ID to its display name, searching all three enums.
     * Returns {@code Optional.empty()} for unknown IDs.
     */
    public static Optional<String> displayNameForId(int id) {
        Optional<Top6League> t6 = Top6League.fromId(id);
        if (t6.isPresent()) return t6.map(Top6League::displayName);

        Optional<CupCompetition> cup = CupCompetition.fromId(id);
        if (cup.isPresent()) return cup.map(CupCompetition::displayName);

        return LeagueCompetition.fromId(id).map(LeagueCompetition::displayName);
    }

    /**
     * Case-insensitive name → ID lookup across all three enums.
     * Exact match is tried first; partial (contains) match is used as fallback.
     *
     * @param name the league or cup name to resolve
     * @return the integer competition ID, or {@code -1} if not found
     */
    public static int resolveId(String name) {
        if (name == null || name.isBlank()) return -1;
        String lower = name.strip().toLowerCase();

        // 1. Exact match
        for (Top6League e : Top6League.values())
            if (e.displayName().equalsIgnoreCase(lower)) return e.id();
        for (CupCompetition e : CupCompetition.values())
            if (e.displayName().equalsIgnoreCase(lower)) return e.id();
        for (LeagueCompetition e : LeagueCompetition.values())
            if (e.displayName().equalsIgnoreCase(lower)) return e.id();

        // 2. Partial match
        for (Top6League e : Top6League.values())
            if (e.displayName().toLowerCase().contains(lower)) return e.id();
        for (CupCompetition e : CupCompetition.values())
            if (e.displayName().toLowerCase().contains(lower)) return e.id();
        for (LeagueCompetition e : LeagueCompetition.values())
            if (e.displayName().toLowerCase().contains(lower)) return e.id();

        return -1;
    }

    /**
     * Returns true if the given ID belongs to one of the top-6 leagues.
     */
    public static boolean isTop6League(int id) {
        return Top6League.fromId(id).isPresent();
    }

    /**
     * Returns true if the given ID belongs to any registered cup competition.
     */
    public static boolean isCup(int id) {
        return CupCompetition.fromId(id).isPresent();
    }
}