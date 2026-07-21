package com.speedbet.api.sportsdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EspnFootballDataService {

    private static final String BASE_URL = "https://site.api.espn.com/apis/site/v2/sports/soccer";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final DateTimeFormatter ESPN_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static final String STATE_PRE  = "pre";
    public static final String STATE_IN   = "in";
    public static final String STATE_POST = "post";

    public enum EspnLeague {
        PREMIER_LEAGUE      ("eng.1",  "Premier League",         true),
        LA_LIGA             ("esp.1",  "La Liga",                true),
        BUNDESLIGA          ("ger.1",  "Bundesliga",             true),
        SERIE_A             ("ita.1",  "Serie A",                true),
        LIGUE_1             ("fra.1",  "Ligue 1",                true),
        CHAMPIONSHIP        ("eng.2",  "Championship",           false),
        EREDIVISIE          ("ned.1",  "Eredivisie",             false),
        PRIMEIRA_LIGA       ("por.1",  "Primeira Liga",          false),
        SCOTTISH_PREM       ("sco.1",  "Scottish Premiership",   false),
        TURKISH_SUPER       ("tur.1",  "Turkish Süper Lig",      false),
        MLS                 ("usa.1",  "MLS",                    false),
        LIGA_MX             ("mex.1",  "Liga MX",                false),
        BRAZILIAN_SERIE_A   ("bra.1",  "Brazilian Série A",      false),
        ARGENTINE_PRIMERA   ("arg.1",  "Argentine Primera",      false),
        SAUDI_PRO           ("ksa.1",  "Saudi Pro League",       false),

        // ── AFRICA ────────────────────────────────────────────
        RSA_PREMIERSHIP     ("rsa.1",  "South African Premiership",        false),
        RSA_FIRST_DIVISION  ("rsa.2",  "South African First Division",     false),
        NIGERIAN_PL         ("nga.1",  "Nigerian Professional League",     false),
        GHANAIAN_PL         ("gha.1",  "Ghanaian Premier League",          false),
        KENYAN_PL           ("ken.1",  "Kenyan Premier League",            false),
        UGANDAN_PL          ("uga.1",  "Ugandan Premier League",           false),
        ZAMBIAN_SL          ("zam.1",  "Zambian Super League",             false),
        ZIMBABWE_PSL        ("zim.1",  "Zimbabwean Premier Soccer League", false),

        // ── ADDITIONAL EUROPE ──────────────────────────────────
        BELGIAN_PRO         ("bel.1",  "Belgian Pro League",            false),
        RUSSIAN_PL          ("rus.1",  "Russian Premier League",        false),
        GREEK_SL            ("gre.1",  "Greek Super League",            false),
        DANISH_SL           ("den.1",  "Danish Superliga",              false),
        SWEDISH_AL          ("swe.1",  "Swedish Allsvenskan",           false),
        NORWEGIAN_EL        ("nor.1",  "Norwegian Eliteserien",         false),

        // ── ADDITIONAL AMERICAS ────────────────────────────────
        COLOMBIAN_LP        ("col.1",  "Colombian Liga BetPlay",        false),
        CHILEAN_PD          ("chi.1",  "Chilean Primera División",      false),
        ECUADORIAN_PD       ("ecu.1",  "Ecuadorian LigaPro",            false),
        PARAGUAYAN_AP       ("par.1",  "Paraguayan Apertura",           false),
        PERUVIAN_PD         ("per.1",  "Peruvian Liga 1",               false),
        URUGUAYAN_PD        ("uru.1",  "Uruguayan Primera División",    false),

        // ── ASIA / MENA ────────────────────────────────────────
        CHINESE_SL          ("chn.1",  "Chinese Super League",          false),
        JAPANESE_J1         ("jpn.1",  "J1 League",                     false),
        KOREAN_KL           ("kor.1",  "Korean K League 1",             false);

        private final String  slug;
        private final String  displayName;
        private final boolean isTop6;

        EspnLeague(String slug, String displayName, boolean isTop6) {
            this.slug        = slug;
            this.displayName = displayName;
            this.isTop6      = isTop6;
        }

        public String slug()        { return slug; }
        public String displayName() { return displayName; }
        public boolean isTop6()     { return isTop6; }

        public static List<EspnLeague> top6() {
            return Arrays.stream(values()).filter(EspnLeague::isTop6).collect(Collectors.toList());
        }

        public static List<EspnLeague> african() {
            return List.of(
                    RSA_PREMIERSHIP, RSA_FIRST_DIVISION, NIGERIAN_PL, GHANAIAN_PL,
                    KENYAN_PL, UGANDAN_PL, ZAMBIAN_SL, ZIMBABWE_PSL
            );
        }
    }

    public enum EspnCup {
        FA_CUP           ("eng.fa_cup",            "FA Cup",                true),
        EFL_CUP          ("eng.league_cup",         "EFL Cup / Carabao Cup", true),
        COPA_DEL_REY     ("esp.copa_del_rey",       "Copa del Rey",          true),
        DFB_POKAL        ("ger.dfb_pokal",          "DFB Pokal",             true),
        COPPA_ITALIA     ("ita.coppa_italia",       "Coppa Italia",          true),
        COUPE_DE_FRANCE  ("fra.coupe_de_france",    "Coupe de France",       true),
        CHAMPIONS_LEAGUE ("uefa.champions_league",  "UEFA Champions League", true),
        EUROPA_LEAGUE    ("uefa.europa",             "UEFA Europa League",    true),
        CONFERENCE_LEAGUE("uefa.europa.conference", "UEFA Conference League",true),
        WORLD_CUP        ("fifa.world",              "FIFA World Cup",        false),

        // ── PRESEASON / CLUB FRIENDLIES ─────────────────────────
        // Soccer has no discrete "preseason" the way American sports do — preseason
        // fixtures (summer tours, warm-up games) are published by ESPN under these
        // friendly / exhibition competition slugs instead.
        CLUB_FRIENDLY            ("club.friendly",         "Club Friendly",                false),
        INTERNATIONAL_CHAMPS_CUP ("global.champs_cup",     "International Champions Cup",  false),
        EMIRATES_CUP             ("friendly.emirates_cup", "Emirates Cup",                 false),
        INTERNATIONAL_FRIENDLY   ("fifa.friendly",         "International Friendly",       false),
        NON_FIFA_FRIENDLY        ("nonfifa",               "Non-FIFA Friendly",            false);

        private final String  slug;
        private final String  displayName;
        private final boolean isTop6Related;

        EspnCup(String slug, String displayName, boolean isTop6Related) {
            this.slug          = slug;
            this.displayName   = displayName;
            this.isTop6Related = isTop6Related;
        }

        public String  slug()           { return slug; }
        public String  displayName()    { return displayName; }
        public boolean isTop6Related()  { return isTop6Related; }

        public static List<EspnCup> top6DomesticCups() {
            return List.of(FA_CUP, EFL_CUP, COPA_DEL_REY, DFB_POKAL, COPPA_ITALIA, COUPE_DE_FRANCE);
        }

        public static List<EspnCup> uefaClubComps() {
            return List.of(CHAMPIONS_LEAGUE, EUROPA_LEAGUE, CONFERENCE_LEAGUE);
        }

        public static List<EspnCup> top6Related() {
            return Arrays.stream(values()).filter(EspnCup::isTop6Related).collect(Collectors.toList());
        }

        /** Preseason / club friendly / exhibition competitions (ICC, Emirates Cup, etc). */
        public static List<EspnCup> preseasonAndFriendlies() {
            return List.of(
                    CLUB_FRIENDLY, INTERNATIONAL_CHAMPS_CUP, EMIRATES_CUP,
                    INTERNATIONAL_FRIENDLY, NON_FIFA_FRIENDLY
            );
        }

        /** All cups including World Cup and preseason/friendlies — used for upcoming fixture and live scanning. */
        public static List<EspnCup> allIncludingWorldCup() {
            return Arrays.asList(values());
        }
    }

    private final WebClient    client;
    private final ObjectMapper mapper = new ObjectMapper();

    // ── THREE SEPARATE CAFFEINE CACHES WITH DIFFERENT TTLs ────────────────
    //
    //  liveCache    — 30 seconds  : in-progress match data (scores, minute, state)
    //  stdCache     — 90 seconds  : scoreboard / upcoming / finished for today
    //  staticCache  — 10 minutes  : standings, team lists, team schedules
    //
    // Previously everything shared one 5-minute cache, which caused finished
    // matches to linger as "live" or "in-progress" for up to 5 minutes after
    // the final whistle.  Live matches now flip to STATE_POST within 30s.

    private final Cache<String, Object> liveCache =
            Caffeine.newBuilder()
                    .maximumSize(40)
                    .expireAfterWrite(30, TimeUnit.SECONDS)
                    .build();

    private final Cache<String, Object> stdCache =
            Caffeine.newBuilder()
                    .maximumSize(80)
                    .expireAfterWrite(90, TimeUnit.SECONDS)
                    .build();

    private final Cache<String, Object> staticCache =
            Caffeine.newBuilder()
                    .maximumSize(40)
                    .expireAfterWrite(10, TimeUnit.MINUTES)
                    .build();

    public EspnFootballDataService(WebClient.Builder builder) {
        this.client = builder
                .baseUrl(BASE_URL)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("EspnFootballDataService initialised — base URL: {}", BASE_URL);
        log.info("Cache TTLs — live: 30s | std: 90s | static: 10m");
    }

    // ── SECTION 1: SCOREBOARD — LEAGUE ────────────────────────────────────

    public List<Map<String, Object>> getScoreboard(EspnLeague league) {
        String cacheKey = "scoreboard:" + league.slug();
        return cachedStd(cacheKey, () -> {
            log.info("ESPN getScoreboard({}): fetching today's scoreboard", league.displayName());
            Map<String, Object> raw = fetch(league.slug() + "/scoreboard");
            List<Map<String, Object>> events = extractEvents(raw);
            log.info("ESPN getScoreboard({}): {} event(s) returned", league.displayName(), events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getScoreboardByDate(EspnLeague league, String yyyymmdd) {
        String cacheKey = "scoreboard:" + league.slug() + ":" + yyyymmdd;
        return cachedStd(cacheKey, () -> {
            log.info("ESPN getScoreboardByDate({}, {}): fetching", league.displayName(), yyyymmdd);
            Map<String, Object> raw = fetch(league.slug() + "/scoreboard?dates=" + yyyymmdd);
            List<Map<String, Object>> events = extractEvents(raw);
            log.info("ESPN getScoreboardByDate({}, {}): {} event(s)", league.displayName(), yyyymmdd, events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getLiveMatches(EspnLeague league) {
        String cacheKey = "live:league:" + league.slug();
        return cachedLive(cacheKey, () -> {
            // Fetch fresh from ESPN — never read from stdCache for live state
            Map<String, Object> raw = fetch(league.slug() + "/scoreboard");
            List<Map<String, Object>> all = extractEvents(raw);
            List<Map<String, Object>> live = all.stream()
                    .filter(EspnFootballDataService::isLive)
                    .collect(Collectors.toList());
            log.info("ESPN getLiveMatches({}): {}/{} live", league.displayName(), live.size(), all.size());
            return live;
        });
    }

    public List<Map<String, Object>> getUpcomingMatches(EspnLeague league) {
        String cacheKey = "upcoming:league:" + league.slug();
        return cachedStd(cacheKey, () -> {
            List<Map<String, Object>> events = getScoreboard(league).stream()
                    .filter(EspnFootballDataService::isUpcoming)
                    .collect(Collectors.toList());
            log.info("ESPN getUpcomingMatches({}): {} upcoming", league.displayName(), events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getFinishedMatches(EspnLeague league) {
        String cacheKey = "finished:league:" + league.slug();
        return cachedStd(cacheKey, () -> {
            List<Map<String, Object>> events = getScoreboard(league).stream()
                    .filter(EspnFootballDataService::isFinished)
                    .collect(Collectors.toList());
            log.info("ESPN getFinishedMatches({}): {} finished", league.displayName(), events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getTodayMatches(EspnLeague league) {
        log.debug("ESPN getTodayMatches({}): delegating to getScoreboard", league.displayName());
        return getScoreboard(league);
    }

    public List<Map<String, Object>> getTop6TodayMatches() {
        return cachedStd("today:top6:all", () -> {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (EspnLeague league : EspnLeague.top6()) {
                merged.addAll(getScoreboard(league));
            }
            merged = mergeByEventId(merged);
            log.info("ESPN getTop6TodayMatches: {} deduplicated event(s)", merged.size());
            return merged;
        });
    }

    public List<Map<String, Object>> getTop6LiveMatches() {
        return cachedLive("live:top6:all", () -> {
            List<Map<String, Object>> live = new ArrayList<>();
            for (EspnLeague league : EspnLeague.top6()) {
                live.addAll(getLiveMatches(league));
            }
            live = mergeByEventId(live);
            log.info("ESPN getTop6LiveMatches: {} live event(s)", live.size());
            return live;
        });
    }

    public List<Map<String, Object>> getTop6UpcomingMatches() {
        return cachedStd("upcoming:top6:all", () -> {
            List<Map<String, Object>> upcoming = new ArrayList<>();
            for (EspnLeague league : EspnLeague.top6()) {
                upcoming.addAll(getUpcomingMatches(league));
            }
            upcoming = mergeByEventId(upcoming);
            log.info("ESPN getTop6UpcomingMatches: {} upcoming event(s)", upcoming.size());
            return upcoming;
        });
    }

    public List<Map<String, Object>> getAllLeaguesTodayMatches() {
        return cachedStd("today:all-leagues", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (EspnLeague league : EspnLeague.values()) {
                all.addAll(getScoreboard(league));
            }
            all = mergeByEventId(all);
            log.info("ESPN getAllLeaguesTodayMatches: {} total deduplicated event(s)", all.size());
            return all;
        });
    }

    // ── SECTION 1B: ALL-LEAGUES TODAY — LIVE / UPCOMING / FINISHED ────────

    public List<Map<String, Object>> getAllLiveMatchesToday() {
        return cachedLive("today:all:live", () -> {
            log.info("ESPN getAllLiveMatchesToday: scanning all leagues + cups for live matches");
            List<Map<String, Object>> all = new ArrayList<>();

            for (EspnLeague league : EspnLeague.values()) {
                try {
                    // Always hit ESPN directly for live — never read stdCache
                    List<Map<String, Object>> events = extractEvents(fetch(league.slug() + "/scoreboard"));
                    for (Map<String, Object> e : events) {
                        if (isLive(e)) all.add(e);
                    }
                } catch (Exception e) {
                    log.warn("ESPN getAllLiveMatchesToday: error fetching {} — {}", league.displayName(), e.getMessage());
                }
            }

            // Also scan cups (World Cup, UCL, preseason/club friendlies, etc.) for live matches
            for (EspnCup cup : EspnCup.allIncludingWorldCup()) {
                try {
                    List<Map<String, Object>> events = extractEvents(fetch(cup.slug() + "/scoreboard"));
                    for (Map<String, Object> e : events) {
                        if (isLive(e)) all.add(e);
                    }
                } catch (Exception e) {
                    log.warn("ESPN getAllLiveMatchesToday: error fetching cup {} — {}", cup.displayName(), e.getMessage());
                }
            }

            List<Map<String, Object>> merged = mergeByEventId(all);
            log.info("ESPN getAllLiveMatchesToday: {} live event(s) across all leagues + cups", merged.size());
            return merged;
        });
    }

    public List<Map<String, Object>> getAllUpcomingMatchesToday() {
        return cachedStd("today:all:upcoming", () -> {
            log.info("ESPN getAllUpcomingMatchesToday: scanning all leagues + cups");
            List<Map<String, Object>> all = new ArrayList<>();

            for (EspnLeague league : EspnLeague.values()) {
                try {
                    List<Map<String, Object>> events = extractEvents(fetch(league.slug() + "/scoreboard"));
                    for (Map<String, Object> e : events) {
                        if (isUpcoming(e)) all.add(e);
                    }
                } catch (Exception e) {
                    log.warn("ESPN getAllUpcomingMatchesToday: error fetching {} — {}", league.displayName(), e.getMessage());
                }
            }

            // Include cups (World Cup, UCL, domestic cups, preseason/club friendlies) for upcoming today
            for (EspnCup cup : EspnCup.allIncludingWorldCup()) {
                try {
                    List<Map<String, Object>> events = extractEvents(fetch(cup.slug() + "/scoreboard"));
                    for (Map<String, Object> e : events) {
                        if (isUpcoming(e)) all.add(e);
                    }
                } catch (Exception e) {
                    log.warn("ESPN getAllUpcomingMatchesToday: error fetching cup {} — {}", cup.displayName(), e.getMessage());
                }
            }

            List<Map<String, Object>> merged = mergeByEventId(all);
            log.info("ESPN getAllUpcomingMatchesToday: {} upcoming event(s) across all leagues + cups", merged.size());
            return merged;
        });
    }

    public List<Map<String, Object>> getAllFinishedMatchesToday() {
        return cachedStd("today:all:finished", () -> {
            log.info("ESPN getAllFinishedMatchesToday: scanning all leagues + cups");
            List<Map<String, Object>> all = new ArrayList<>();

            for (EspnLeague league : EspnLeague.values()) {
                try {
                    List<Map<String, Object>> events = extractEvents(fetch(league.slug() + "/scoreboard"));
                    for (Map<String, Object> e : events) {
                        if (isFinished(e)) all.add(e);
                    }
                } catch (Exception e) {
                    log.warn("ESPN getAllFinishedMatchesToday: error fetching {} — {}", league.displayName(), e.getMessage());
                }
            }

            // Cups (World Cup, UCL, domestic cups, preseason/club friendlies) were previously
            // missing from this bucket even though the live/upcoming buckets included them.
            for (EspnCup cup : EspnCup.allIncludingWorldCup()) {
                try {
                    List<Map<String, Object>> events = extractEvents(fetch(cup.slug() + "/scoreboard"));
                    for (Map<String, Object> e : events) {
                        if (isFinished(e)) all.add(e);
                    }
                } catch (Exception e) {
                    log.warn("ESPN getAllFinishedMatchesToday: error fetching cup {} — {}", cup.displayName(), e.getMessage());
                }
            }

            List<Map<String, Object>> merged = mergeByEventId(all);
            log.info("ESPN getAllFinishedMatchesToday: {} finished event(s) across all leagues + cups", merged.size());
            return merged;
        });
    }

    public Map<String, List<Map<String, Object>>> getAllMatchesTodayByStatus() {
        log.info("ESPN getAllMatchesTodayByStatus: assembling live/upcoming/finished buckets");
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("live",     getAllLiveMatchesToday());
        result.put("upcoming", getAllUpcomingMatchesToday());
        result.put("finished", getAllFinishedMatchesToday());
        log.info("ESPN getAllMatchesTodayByStatus: live={}, upcoming={}, finished={}",
                result.get("live").size(),
                result.get("upcoming").size(),
                result.get("finished").size());
        return result;
    }

    // ── SECTION 1C: UPCOMING FIXTURES — NEXT N DAYS (ALL LEAGUES + CUPS) ──

    public List<Map<String, Object>> getAllUpcomingFixturesByDate(String yyyymmdd) {
        String cacheKey = "upcoming:all:" + yyyymmdd;
        return cachedStd(cacheKey, () -> {
            log.info("ESPN getAllUpcomingFixturesByDate({}): scanning all leagues + cups incl. World Cup and preseason/friendlies", yyyymmdd);
            List<Map<String, Object>> all = new ArrayList<>();

            for (EspnLeague league : EspnLeague.values()) {
                try {
                    all.addAll(extractEvents(fetch(league.slug() + "/scoreboard?dates=" + yyyymmdd)));
                } catch (Exception e) {
                    log.warn("ESPN getAllUpcomingFixturesByDate({}): error fetching {} — {}",
                            yyyymmdd, league.displayName(), e.getMessage());
                }
            }

            // Include ALL cups — World Cup, Champions League, domestic cups, preseason/club friendlies, etc.
            for (EspnCup cup : EspnCup.allIncludingWorldCup()) {
                try {
                    all.addAll(extractEvents(fetch(cup.slug() + "/scoreboard?dates=" + yyyymmdd)));
                } catch (Exception e) {
                    log.warn("ESPN getAllUpcomingFixturesByDate({}): error fetching cup {} — {}",
                            yyyymmdd, cup.displayName(), e.getMessage());
                }
            }

            List<Map<String, Object>> merged = mergeByEventId(all);
            log.info("ESPN getAllUpcomingFixturesByDate({}): {} event(s)", yyyymmdd, merged.size());
            return merged;
        });
    }

    /**
     * Returns fixtures for a single {@link LocalDate} across all leagues + cups (incl. World Cup).
     * Called by {@link com.speedbet.api.livescore.LiveScorePoller#pollUpcomingFixtures()}
     * one day at a time so only one day's worth of event maps is held in memory at once.
     */
    public List<Map<String, Object>> getFixturesForDate(LocalDate date) {
        String yyyymmdd = formatDate(date);
        log.info("ESPN getFixturesForDate({}): delegating to getAllUpcomingFixturesByDate", yyyymmdd);
        return getAllUpcomingFixturesByDate(yyyymmdd);
    }

    public Map<String, List<Map<String, Object>>> getUpcomingFixturesNextDays(int days) {
        String cacheKey = "upcoming:next-" + days + "days";
        return cachedStd(cacheKey, () -> {
            log.info("ESPN getUpcomingFixturesNextDays({}): building {}-day fixture list", days, days);
            Map<String, List<Map<String, Object>>> byDate = new LinkedHashMap<>();
            LocalDate startDate = LocalDate.now();
            for (int i = 1; i <= days; i++) {
                String dateStr = formatDate(startDate.plusDays(i));
                List<Map<String, Object>> fixtures = getAllUpcomingFixturesByDate(dateStr);
                if (!fixtures.isEmpty()) {
                    byDate.put(dateStr, fixtures);
                    log.debug("ESPN getUpcomingFixturesNextDays: date={} -> {} fixture(s)", dateStr, fixtures.size());
                } else {
                    log.debug("ESPN getUpcomingFixturesNextDays: date={} -> no fixtures", dateStr);
                }
            }
            int total = byDate.values().stream().mapToInt(List::size).sum();
            log.info("ESPN getUpcomingFixturesNextDays({}): {} date(s), {} total event(s)", days, byDate.size(), total);
            return byDate;
        });
    }

    /** Kept for backwards compatibility — delegates to the 3-day window. */
    public Map<String, List<Map<String, Object>>> getUpcomingFixturesNext7Days() {
        return getUpcomingFixturesNextDays(3);
    }

    public List<Map<String, Object>> getUpcomingFixturesNext7DaysFlatList() {
        return cachedStd("upcoming:next3days:flat", () -> {
            List<Map<String, Object>> flat = new ArrayList<>();
            for (List<Map<String, Object>> dayFixtures : getUpcomingFixturesNext7Days().values()) {
                flat.addAll(dayFixtures);
            }
            log.info("ESPN getUpcomingFixturesNext7DaysFlatList: {} total upcoming fixture(s)", flat.size());
            return flat;
        });
    }

    // ── SECTION 2: SCOREBOARD — CUP ───────────────────────────────────────

    public List<Map<String, Object>> getCupScoreboard(EspnCup cup) {
        String cacheKey = "scoreboard:cup:" + cup.slug();
        return cachedStd(cacheKey, () -> {
            log.info("ESPN getCupScoreboard({}): fetching today", cup.displayName());
            Map<String, Object> raw = fetch(cup.slug() + "/scoreboard");
            List<Map<String, Object>> events = extractEvents(raw);
            log.info("ESPN getCupScoreboard({}): {} event(s)", cup.displayName(), events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getCupScoreboardByDate(EspnCup cup, String yyyymmdd) {
        String cacheKey = "scoreboard:cup:" + cup.slug() + ":" + yyyymmdd;
        return cachedStd(cacheKey, () -> {
            log.info("ESPN getCupScoreboardByDate({}, {}): fetching", cup.displayName(), yyyymmdd);
            Map<String, Object> raw = fetch(cup.slug() + "/scoreboard?dates=" + yyyymmdd);
            List<Map<String, Object>> events = extractEvents(raw);
            log.info("ESPN getCupScoreboardByDate({}, {}): {} event(s)", cup.displayName(), yyyymmdd, events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getCupLiveMatches(EspnCup cup) {
        String cacheKey = "live:cup:" + cup.slug();
        return cachedLive(cacheKey, () -> {
            // Always hit ESPN directly for live state
            Map<String, Object> raw = fetch(cup.slug() + "/scoreboard");
            List<Map<String, Object>> live = extractEvents(raw).stream()
                    .filter(EspnFootballDataService::isLive)
                    .collect(Collectors.toList());
            log.info("ESPN getCupLiveMatches({}): {} live", cup.displayName(), live.size());
            return live;
        });
    }

    public List<Map<String, Object>> getCupUpcomingMatches(EspnCup cup) {
        String cacheKey = "upcoming:cup:" + cup.slug();
        return cachedStd(cacheKey, () -> {
            List<Map<String, Object>> events = getCupScoreboard(cup).stream()
                    .filter(EspnFootballDataService::isUpcoming)
                    .collect(Collectors.toList());
            log.info("ESPN getCupUpcomingMatches({}): {} upcoming", cup.displayName(), events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getCupFinishedMatches(EspnCup cup) {
        String cacheKey = "finished:cup:" + cup.slug();
        return cachedStd(cacheKey, () -> {
            List<Map<String, Object>> events = getCupScoreboard(cup).stream()
                    .filter(EspnFootballDataService::isFinished)
                    .collect(Collectors.toList());
            log.info("ESPN getCupFinishedMatches({}): {} finished", cup.displayName(), events.size());
            return events;
        });
    }

    public List<Map<String, Object>> getTop6CupsTodayMatches() {
        return cachedStd("today:top6cups:all", () -> {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (EspnCup cup : EspnCup.top6DomesticCups()) {
                merged.addAll(getCupScoreboard(cup));
            }
            merged = mergeByEventId(merged);
            log.info("ESPN getTop6CupsTodayMatches: {} deduplicated event(s)", merged.size());
            return merged;
        });
    }

    public List<Map<String, Object>> getTop6CupsLiveMatches() {
        return cachedLive("live:top6cups:all", () -> {
            List<Map<String, Object>> live = new ArrayList<>();
            for (EspnCup cup : EspnCup.top6DomesticCups()) {
                live.addAll(getCupLiveMatches(cup));
            }
            live = mergeByEventId(live);
            log.info("ESPN getTop6CupsLiveMatches: {} live event(s)", live.size());
            return live;
        });
    }

    public List<Map<String, Object>> getTop6CupsUpcomingMatches() {
        return cachedStd("upcoming:top6cups:all", () -> {
            List<Map<String, Object>> upcoming = new ArrayList<>();
            for (EspnCup cup : EspnCup.top6Related()) {
                upcoming.addAll(getCupUpcomingMatches(cup));
            }
            upcoming = mergeByEventId(upcoming);
            log.info("ESPN getTop6CupsUpcomingMatches: {} upcoming event(s)", upcoming.size());
            return upcoming;
        });
    }

    public List<Map<String, Object>> getUefaCompetitionsTodayMatches() {
        return cachedStd("today:uefa-clubs:all", () -> {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (EspnCup cup : EspnCup.uefaClubComps()) {
                merged.addAll(getCupScoreboard(cup));
            }
            merged = mergeByEventId(merged);
            log.info("ESPN getUefaCompetitionsTodayMatches: {} event(s)", merged.size());
            return merged;
        });
    }

    public List<Map<String, Object>> getUefaLiveMatches() {
        return cachedLive("live:uefa-clubs:all", () -> {
            List<Map<String, Object>> live = new ArrayList<>();
            for (EspnCup cup : EspnCup.uefaClubComps()) {
                live.addAll(getCupLiveMatches(cup));
            }
            live = mergeByEventId(live);
            log.info("ESPN getUefaLiveMatches: {} live event(s)", live.size());
            return live;
        });
    }

    public List<Map<String, Object>> getAllCupsTodayMatches() {
        return cachedStd("today:all-cups", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (EspnCup cup : EspnCup.values()) {
                all.addAll(getCupScoreboard(cup));
            }
            all = mergeByEventId(all);
            log.info("ESPN getAllCupsTodayMatches: {} deduplicated event(s)", all.size());
            return all;
        });
    }

    // ── SECTION 2B: PRESEASON / CLUB FRIENDLIES ───────────────────────────

    public List<Map<String, Object>> getPreseasonFriendliesTodayMatches() {
        return cachedStd("today:preseason:all", () -> {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (EspnCup cup : EspnCup.preseasonAndFriendlies()) {
                merged.addAll(getCupScoreboard(cup));
            }
            merged = mergeByEventId(merged);
            log.info("ESPN getPreseasonFriendliesTodayMatches: {} deduplicated event(s)", merged.size());
            return merged;
        });
    }

    public List<Map<String, Object>> getPreseasonFriendliesLiveMatches() {
        return cachedLive("live:preseason:all", () -> {
            List<Map<String, Object>> live = new ArrayList<>();
            for (EspnCup cup : EspnCup.preseasonAndFriendlies()) {
                live.addAll(getCupLiveMatches(cup));
            }
            live = mergeByEventId(live);
            log.info("ESPN getPreseasonFriendliesLiveMatches: {} live event(s)", live.size());
            return live;
        });
    }

    public List<Map<String, Object>> getPreseasonFriendliesUpcomingMatches() {
        return cachedStd("upcoming:preseason:all", () -> {
            List<Map<String, Object>> upcoming = new ArrayList<>();
            for (EspnCup cup : EspnCup.preseasonAndFriendlies()) {
                upcoming.addAll(getCupUpcomingMatches(cup));
            }
            upcoming = mergeByEventId(upcoming);
            log.info("ESPN getPreseasonFriendliesUpcomingMatches: {} upcoming event(s)", upcoming.size());
            return upcoming;
        });
    }

    public List<Map<String, Object>> getPreseasonFriendliesByDate(String yyyymmdd) {
        String cacheKey = "preseason:date:" + yyyymmdd;
        return cachedStd(cacheKey, () -> {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (EspnCup cup : EspnCup.preseasonAndFriendlies()) {
                merged.addAll(getCupScoreboardByDate(cup, yyyymmdd));
            }
            merged = mergeByEventId(merged);
            log.info("ESPN getPreseasonFriendliesByDate({}): {} event(s)", yyyymmdd, merged.size());
            return merged;
        });
    }

    // ── SECTION 3: DATE-RANGE / UPCOMING FIXTURES ─────────────────────────

    public List<Map<String, Object>> getUpcomingFixturesByDate(EspnLeague league, String yyyymmdd) {
        log.info("ESPN getUpcomingFixturesByDate({}, {}): fetching", league.displayName(), yyyymmdd);
        return getScoreboardByDate(league, yyyymmdd);
    }

    public List<Map<String, Object>> getCupUpcomingFixturesByDate(EspnCup cup, String yyyymmdd) {
        log.info("ESPN getCupUpcomingFixturesByDate({}, {}): fetching", cup.displayName(), yyyymmdd);
        return getCupScoreboardByDate(cup, yyyymmdd);
    }

    public List<Map<String, Object>> getTop6UpcomingFixturesByDate(String yyyymmdd) {
        String cacheKey = "upcoming:top6:" + yyyymmdd;
        return cachedStd(cacheKey, () -> {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (EspnLeague league : EspnLeague.top6()) {
                merged.addAll(getScoreboardByDate(league, yyyymmdd));
            }
            merged = mergeByEventId(merged);
            log.info("ESPN getTop6UpcomingFixturesByDate({}): {} event(s)", yyyymmdd, merged.size());
            return merged;
        });
    }

    public List<Map<String, Object>> getTop6CupsUpcomingFixturesByDate(String yyyymmdd) {
        String cacheKey = "upcoming:top6cups:" + yyyymmdd;
        return cachedStd(cacheKey, () -> {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (EspnCup cup : EspnCup.top6DomesticCups()) {
                merged.addAll(getCupScoreboardByDate(cup, yyyymmdd));
            }
            merged = mergeByEventId(merged);
            log.info("ESPN getTop6CupsUpcomingFixturesByDate({}): {} event(s)", yyyymmdd, merged.size());
            return merged;
        });
    }

    // ── SECTION 4: MATCH DETAIL / SUMMARY ─────────────────────────────────

    public Map<String, Object> getMatchSummary(EspnLeague league, String eventId) {
        log.info("ESPN getMatchSummary({}, event={}): fetching full summary", league.displayName(), eventId);
        Map<String, Object> summary = fetch(league.slug() + "/summary?event=" + eventId);
        if (summary == null) {
            log.warn("ESPN getMatchSummary({}, event={}): null response", league.displayName(), eventId);
            return Map.of();
        }
        return summary;
    }

    public Map<String, Object> getCupMatchSummary(EspnCup cup, String eventId) {
        log.info("ESPN getCupMatchSummary({}, event={}): fetching full summary", cup.displayName(), eventId);
        Map<String, Object> summary = fetch(cup.slug() + "/summary?event=" + eventId);
        if (summary == null) {
            log.warn("ESPN getCupMatchSummary({}, event={}): null response", cup.displayName(), eventId);
            return Map.of();
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> extractMatchOdds(Map<String, Object> summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object pcRaw = summary.get("pickcenter");
            if (pcRaw instanceof List<?> pcList && !pcList.isEmpty()) {
                Map<String, Object> dk = (Map<String, Object>) pcList.get(0);
                Map<String, Object> dkOdds = new LinkedHashMap<>();
                Object homeOdds = nestedGet(dk, "homeTeamOdds", "moneyLine");
                Object awayOdds = nestedGet(dk, "awayTeamOdds", "moneyLine");
                Object drawOdds = nestedGet(dk, "drawOdds", "moneyLine");
                if (homeOdds != null) dkOdds.put("home", homeOdds.toString());
                if (awayOdds != null) dkOdds.put("away", awayOdds.toString());
                if (drawOdds != null) dkOdds.put("draw", drawOdds.toString());
                if (!dkOdds.isEmpty()) result.put("draftkings", dkOdds);
            }

            Object oddsRaw = summary.get("odds");
            if (oddsRaw instanceof List<?> oddsList) {
                for (Object o : oddsList) {
                    Map<String, Object> oddsEntry = (Map<String, Object>) o;
                    Object provider = oddsEntry.get("provider");
                    if (provider instanceof Map<?, ?> pMap &&
                            "Bet365".equalsIgnoreCase(String.valueOf(pMap.get("name")))) {
                        Map<String, Object> b365 = new LinkedHashMap<>();
                        Object homeVal  = nestedGet(oddsEntry, "homeTeamOdds", "odds", "value");
                        Object homeFrac = nestedGet(oddsEntry, "homeTeamOdds", "odds", "summary");
                        Object awayVal  = nestedGet(oddsEntry, "awayTeamOdds", "odds", "value");
                        Object awayFrac = nestedGet(oddsEntry, "awayTeamOdds", "odds", "summary");
                        Object drawVal  = nestedGet(oddsEntry, "drawOdds", "value");
                        Object drawFrac = nestedGet(oddsEntry, "drawOdds", "summary");
                        if (homeVal  != null) b365.put("home",           homeVal.toString());
                        if (homeFrac != null) b365.put("homeFractional", homeFrac.toString());
                        if (awayVal  != null) b365.put("away",           awayVal.toString());
                        if (awayFrac != null) b365.put("awayFractional", awayFrac.toString());
                        if (drawVal  != null) b365.put("draw",           drawVal.toString());
                        if (drawFrac != null) b365.put("drawFractional", drawFrac.toString());
                        if (!b365.isEmpty()) result.put("bet365", b365);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ESPN extractMatchOdds: error during extraction — {}", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> extractPlayerGoalscorerOdds(Map<String, Object> summary) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        String[] keys  = {"preMatchFirstGoalScorer", "preMatchAnyTimeGoalScorer", "preMatchLastGoalScorer"};
        String[] names = {"firstGoalscorer", "anytimeGoalscorer", "lastGoalscorer"};
        for (int i = 0; i < keys.length; i++) {
            Object raw = summary.get(keys[i]);
            if (raw instanceof List<?> list && !list.isEmpty()) {
                result.put(names[i], (List<Map<String, Object>>) list);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractHeadToHead(Map<String, Object> summary) {
        Object raw = summary.get("headToHeadGames");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractRecentForm(Map<String, Object> summary) {
        try {
            Object boxscore = summary.get("boxscore");
            if (boxscore instanceof Map<?, ?> bsMap) {
                Object form = ((Map<String, Object>) bsMap).get("form");
                if (form instanceof List<?> list && !list.isEmpty()) {
                    return (List<Map<String, Object>>) list;
                }
            }
        } catch (Exception e) {
            log.warn("ESPN extractRecentForm: error — {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractMatchNews(Map<String, Object> summary) {
        try {
            Object news = summary.get("news");
            if (news instanceof Map<?, ?> newsMap) {
                Object articles = ((Map<String, Object>) newsMap).get("articles");
                if (articles instanceof List<?> list && !list.isEmpty()) {
                    return (List<Map<String, Object>>) list;
                }
            }
        } catch (Exception e) {
            log.warn("ESPN extractMatchNews: error — {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractMatchVideos(Map<String, Object> summary) {
        Object raw = summary.get("videos");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    // ── SECTION 5: STANDINGS ──────────────────────────────────────────────

    public Map<String, Object> getStandings(EspnLeague league) {
        return cachedStatic("standings:league:" + league.slug(), () -> {
            log.info("ESPN getStandings({}): fetching league table", league.displayName());
            Map<String, Object> raw = fetch(league.slug() + "/standings");
            return raw != null ? raw : Map.of();
        });
    }

    public Map<String, Object> getCupStandings(EspnCup cup) {
        return cachedStatic("standings:cup:" + cup.slug(), () -> {
            log.info("ESPN getCupStandings({}): fetching standings", cup.displayName());
            Map<String, Object> raw = fetch(cup.slug() + "/standings");
            return raw != null ? raw : Map.of();
        });
    }

    public Map<String, Map<String, Object>> getTop6Standings() {
        return cachedStatic("standings:top6:all", () -> {
            log.info("ESPN getTop6Standings: fetching standings for all Top 6 leagues");
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (EspnLeague league : EspnLeague.top6()) {
                Map<String, Object> standings = getStandings(league);
                if (!standings.isEmpty()) result.put(league.displayName(), standings);
            }
            log.info("ESPN getTop6Standings: {} league(s) with standings", result.size());
            return result;
        });
    }

    // ── SECTION 6: TEAMS ──────────────────────────────────────────────────

    public Map<String, Object> getTeams(EspnLeague league) {
        return cachedStatic("teams:league:" + league.slug(), () -> {
            log.info("ESPN getTeams({}): fetching team list", league.displayName());
            Map<String, Object> raw = fetch(league.slug() + "/teams");
            return raw != null ? raw : Map.of();
        });
    }

    public Map<String, Object> getTeamSchedule(EspnLeague league, String teamId) {
        String cacheKey = "schedule:league:" + league.slug() + ":team:" + teamId;
        return cachedStatic(cacheKey, () -> {
            log.info("ESPN getTeamSchedule({}, teamId={}): fetching schedule", league.displayName(), teamId);
            Map<String, Object> raw = fetch(league.slug() + "/teams/" + teamId + "/schedule");
            return raw != null ? raw : Map.of();
        });
    }

    // ── SECTION 7: STATUS DETECTION ───────────────────────────────────────

    public static boolean isLive(Map<String, Object> event) {
        return STATE_IN.equals(extractState(event));
    }

    public static boolean isFinished(Map<String, Object> event) {
        return STATE_POST.equals(extractState(event));
    }

    public static boolean isUpcoming(Map<String, Object> event) {
        return STATE_PRE.equals(extractState(event));
    }

    // ── SECTION 8: FIELD EXTRACTORS ───────────────────────────────────────

    public static String extractEventId(Map<String, Object> event) {
        Object id = event.get("id");
        return id != null ? id.toString() : "";
    }

    public static String extractHomeName(Map<String, Object> event) {
        return extractCompetitorField(event, "home", "displayName");
    }

    public static String extractAwayName(Map<String, Object> event) {
        return extractCompetitorField(event, "away", "displayName");
    }

    public static String extractHomeLogo(Map<String, Object> event) {
        return extractCompetitorField(event, "home", "logo");
    }

    public static String extractAwayLogo(Map<String, Object> event) {
        return extractCompetitorField(event, "away", "logo");
    }

    @SuppressWarnings("unchecked")
    public static String extractScore(Map<String, Object> event) {
        try {
            List<Map<String, Object>> competitors = getCompetitors(event);
            if (competitors == null || competitors.size() < 2) return "";
            String homeScore = "";
            String awayScore = "";
            for (Map<String, Object> c : competitors) {
                String side  = String.valueOf(c.getOrDefault("homeAway", ""));
                Object score = c.get("score");
                if ("home".equals(side) && score != null) homeScore = score.toString();
                if ("away".equals(side) && score != null) awayScore = score.toString();
            }
            if (homeScore.isBlank() && awayScore.isBlank()) return "";
            return homeScore + " - " + awayScore;
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    public static String extractStatus(Map<String, Object> event) {
        try {
            Object status = event.get("status");
            if (status instanceof Map<?, ?> sMap) {
                Object type = sMap.get("type");
                if (type instanceof Map<?, ?> tMap) {
                    Object detail = tMap.get("shortDetail");
                    if (detail != null && !detail.toString().isBlank()) return detail.toString();
                }
            }
        } catch (Exception e) {
            log.trace("extractStatus: error — {}", e.getMessage());
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    public static String extractKickoffTime(Map<String, Object> event) {
        Object dateObj = event.get("date");
        log.info("extractKickoffTime: root date={}", dateObj);

        if (dateObj == null) {
            try {
                List<?> competitions = (List<?>) event.get("competitions");
                if (competitions != null && !competitions.isEmpty()) {
                    Map<String, Object> comp = (Map<String, Object>) competitions.get(0);
                    dateObj = comp.get("date");
                }
            } catch (ClassCastException ignored) {}
        }

        return dateObj != null ? dateObj.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public static String extractCompetitionName(Map<String, Object> event) {
        try {
            Map<String, Object> comp = getFirstCompetition(event);
            if (comp == null) return "";
            Object leagues = comp.get("league");
            if (leagues instanceof Map<?, ?> lMap) {
                Object name = ((Map<String, Object>) lMap).get("name");
                if (name != null && !name.toString().isBlank()) return name.toString();
            }
            Object name = event.get("name");
            return name != null ? name.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    public static String extractVenue(Map<String, Object> event) {
        try {
            Map<String, Object> comp = getFirstCompetition(event);
            if (comp == null) return "";
            Object venueObj = comp.get("venue");
            if (venueObj instanceof Map<?, ?> venueRaw) {
                Map<String, Object> venueMap = (Map<String, Object>) venueRaw;
                String name = String.valueOf(venueMap.getOrDefault("fullName", ""));
                String city = "";
                Object addrObj = venueMap.get("address");
                if (addrObj instanceof Map<?, ?> addrRaw) {
                    city = String.valueOf(((Map<String, Object>) addrRaw).getOrDefault("city", ""));
                }
                if (!name.isBlank() && !city.isBlank()) return name + ", " + city;
                if (!name.isBlank()) return name;
                return city;
            }
        } catch (Exception e) {
            log.trace("extractVenue: error — {}", e.getMessage());
        }
        return "";
    }

    // ── SECTION 9: UTILITY ────────────────────────────────────────────────

    public static String formatDate(LocalDate date) {
        return date.format(ESPN_DATE_FMT);
    }

    public static String today() {
        return formatDate(LocalDate.now());
    }

    public void clearCache() {
        liveCache.invalidateAll();
        stdCache.invalidateAll();
        staticCache.invalidateAll();
        log.info("ESPN clearCache: all three cache tiers cleared (live/std/static)");
    }

    public void invalidateCache(String key) {
        liveCache.invalidate(key);
        stdCache.invalidate(key);
        staticCache.invalidate(key);
        log.debug("ESPN invalidateCache('{}'): invalidated across all tiers", key);
    }

    // ── PRIVATE: HTTP FETCH ───────────────────────────────────────────────

    private Map<String, Object> fetch(String path) {
        log.debug("ESPN fetch: GET /{}", path);
        try {
            String raw = client.get()
                    .uri("/" + path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(e -> {
                        log.warn("ESPN fetch /{}: network error — {}", path, e.getMessage());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();

            if (raw == null || raw.isBlank()) {
                log.warn("ESPN fetch /{}: blank or null response", path);
                return null;
            }

            Map<String, Object> parsed = mapper.readValue(raw, MAP_TYPE);
            log.debug("ESPN fetch /{}: OK ({} bytes)", path, raw.length());
            return parsed;

        } catch (Exception e) {
            log.error("ESPN fetch /{}: exception — {}", path, e.getMessage());
            return null;
        }
    }

    // ── PRIVATE: CACHE HELPERS ────────────────────────────────────────────

    /** 30-second cache — for live/in-progress match data only. */
    private <T> T cachedLive(String key, java.util.function.Supplier<T> loader) {
        return cached(liveCache, key, loader);
    }

    /** 90-second cache — for today's scoreboard, upcoming, finished buckets. */
    private <T> T cachedStd(String key, java.util.function.Supplier<T> loader) {
        return cached(stdCache, key, loader);
    }

    /** 10-minute cache — for standings, team lists, schedules. */
    private <T> T cachedStatic(String key, java.util.function.Supplier<T> loader) {
        return cached(staticCache, key, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(Cache<String, Object> c, String key, java.util.function.Supplier<T> loader) {
        T result = (T) c.getIfPresent(key);
        if (result != null) {
            log.debug("ESPN cache HIT: '{}'", key);
            return result;
        }
        log.debug("ESPN cache MISS: '{}'", key);
        result = loader.get();
        if (result != null) {
            c.put(key, result);
        }
        return result;
    }

    // ── PRIVATE: RESPONSE EXTRACTION HELPERS ─────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractEvents(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        Object events = response.get("events");
        if (events instanceof List<?> list && !list.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getFirstCompetition(Map<String, Object> event) {
        Object comps = event.get("competitions");
        if (comps instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) return (Map<String, Object>) map;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getCompetitors(Map<String, Object> event) {
        Map<String, Object> comp = getFirstCompetition(event);
        if (comp == null) return null;
        Object competitors = comp.get("competitors");
        if (competitors instanceof List<?> list) return (List<Map<String, Object>>) list;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractCompetitorField(Map<String, Object> event, String side, String field) {
        try {
            List<Map<String, Object>> competitors = getCompetitors(event);
            if (competitors == null) return "";
            for (Map<String, Object> c : competitors) {
                String ha = String.valueOf(c.getOrDefault("homeAway", ""));
                if (side.equals(ha)) {
                    Object team = c.get("team");
                    if (team instanceof Map<?, ?> teamRaw) {
                        Object val = ((Map<String, Object>) teamRaw).get(field);
                        if (val != null && !val.toString().isBlank()) return val.toString();
                    }
                    Object direct = c.get(field);
                    if (direct != null && !direct.toString().isBlank()) return direct.toString();
                }
            }
        } catch (Exception e) {
            log.trace("extractCompetitorField({}, {}): error — {}", side, field, e.getMessage());
        }
        return "";
    }

    private static String extractState(Map<String, Object> event) {
        try {
            Object status = event.get("status");
            if (status instanceof Map<?, ?> sMap) {
                Object type = sMap.get("type");
                if (type instanceof Map<?, ?> tMap) {
                    Object state = tMap.get("state");
                    if (state != null) return state.toString();
                }
            }
        } catch (Exception ignore) {}
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Object nestedGet(Map<String, Object> map, String... keys) {
        Object current = map;
        for (int i = 0; i < keys.length - 1; i++) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) m).get(keys[i]);
        }
        if (!(current instanceof Map<?, ?> m)) return null;
        return ((Map<String, Object>) m).get(keys[keys.length - 1]);
    }

    private static List<Map<String, Object>> mergeByEventId(List<Map<String, Object>> events) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> e : events) {
            String id = extractEventId(e);
            if (!id.isBlank() && seen.add(id)) result.add(e);
        }
        return result;
    }
}