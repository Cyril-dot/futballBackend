-- ============================================================
-- SpeedBet V1 — Initial Schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS citext;

-- ENUMs
CREATE TYPE user_role     AS ENUM ('USER','ADMIN','SUPER_ADMIN');
CREATE TYPE user_status   AS ENUM ('ACTIVE','DISABLED','LOCKED');
CREATE TYPE tx_kind       AS ENUM ('DEPOSIT','WITHDRAW','BET_STAKE','BET_WIN',
                                    'REFERRAL_COMMISSION','PAYOUT','ADJUSTMENT',
                                    'VIP_CASHBACK','VIP_MEMBERSHIP');
CREATE TYPE payout_status AS ENUM ('REQUESTED','APPROVED','PAID','REJECTED');
CREATE TYPE match_source  AS ENUM ('SPORTDB','SPORTSRC','VIRTUAL','ADMIN_CREATED');
CREATE TYPE bet_status    AS ENUM ('PENDING','WON','LOST','VOID','CASHED_OUT');
CREATE TYPE odds_market   AS ENUM ('1X2','HOME_WIN','AWAY_WIN','OVER_UNDER','HANDICAP',
                                    'CORRECT_SCORE','HT_FT','DOUBLE_CHANCE','BTTS','FTS','LIVE');
CREATE TYPE booking_kind  AS ENUM ('1X2','HOME_WIN','AWAY_WIN','CORRECT_SCORE','HANDICAP',
                                    'HT_FT','BTTS','OVER_UNDER','MIXED');
CREATE TYPE gift_kind     AS ENUM ('FREE_BET','BOOSTED_ODDS','DEPOSIT_BONUS',
                                    'CASHBACK_CREDIT','ENTRY_TICKET');

-- USERS
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email               CITEXT UNIQUE NOT NULL,
    phone               VARCHAR(20),
    password_hash       TEXT NOT NULL,
    first_name          TEXT,
    last_name           TEXT,
    role                user_role NOT NULL DEFAULT 'USER',
    status              user_status NOT NULL DEFAULT 'ACTIVE',
    created_by_admin_id UUID REFERENCES users(id),
    referred_via_link_id UUID,
    theme_preference    VARCHAR(10) DEFAULT 'light',
    win_seen            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- WALLETS
CREATE TABLE wallets (
    id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id  UUID UNIQUE NOT NULL REFERENCES users(id),
    currency VARCHAR(3) NOT NULL DEFAULT 'GHS',
    balance  NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (balance >= 0)
);

-- TRANSACTIONS
CREATE TABLE transactions (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_id    UUID NOT NULL REFERENCES wallets(id),
    kind         tx_kind NOT NULL,
    amount       NUMERIC(19,4) NOT NULL,
    balance_after NUMERIC(19,4) NOT NULL,
    provider_ref TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    metadata     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- REFERRAL LINKS
CREATE TABLE referral_links (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    admin_id           UUID NOT NULL REFERENCES users(id),
    code               VARCHAR(12) UNIQUE NOT NULL,
    label              TEXT,
    commission_percent NUMERIC(5,2) NOT NULL DEFAULT 10,
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ
);

-- REFERRALS
CREATE TABLE referrals (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    link_id              UUID NOT NULL REFERENCES referral_links(id),
    user_id              UUID UNIQUE NOT NULL REFERENCES users(id),
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    lifetime_stake       NUMERIC(19,4) NOT NULL DEFAULT 0,
    lifetime_commission  NUMERIC(19,4) NOT NULL DEFAULT 0
);

-- PAYOUT REQUESTS
CREATE TABLE payout_requests (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    admin_id     UUID NOT NULL REFERENCES users(id),
    period_start TIMESTAMPTZ,
    period_end   TIMESTAMPTZ,
    amount       NUMERIC(19,4) NOT NULL,
    status       payout_status NOT NULL DEFAULT 'REQUESTED',
    reject_reason TEXT,
    paid_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- MATCHES
CREATE TABLE matches (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source      match_source NOT NULL,
    external_id TEXT,
    sport       VARCHAR(30),
    league      TEXT,
    home_team   TEXT,
    away_team   TEXT,
    kickoff_at  TIMESTAMPTZ,
    status      VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    score_home  INT,
    score_away  INT,
    is_featured BOOLEAN NOT NULL DEFAULT FALSE,
    metadata    JSONB,
    settled_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ODDS
CREATE TABLE odds (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    match_id    UUID NOT NULL REFERENCES matches(id),
    market      odds_market NOT NULL,
    selection   TEXT NOT NULL,
    value       NUMERIC(9,3) NOT NULL,
    line        NUMERIC(5,2),
    handicap    NUMERIC(5,2),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- BETS
CREATE TABLE bets (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id             UUID NOT NULL REFERENCES users(id),
    stake               NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'GHS',
    total_odds          NUMERIC(12,4),
    potential_return    NUMERIC(19,4),
    status              bet_status NOT NULL DEFAULT 'PENDING',
    win_seen            BOOLEAN NOT NULL DEFAULT FALSE,
    placed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ,
    booking_code_used_id UUID
);

-- BET SELECTIONS
CREATE TABLE bet_selections (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    bet_id      UUID NOT NULL REFERENCES bets(id),
    match_id    UUID NOT NULL REFERENCES matches(id),
    market      odds_market NOT NULL,
    selection   TEXT NOT NULL,
    odds_locked NUMERIC(9,3) NOT NULL,
    result      VARCHAR(10) NOT NULL DEFAULT 'PENDING'
);

-- BOOKING CODES
CREATE TABLE booking_codes (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code              VARCHAR(8) UNIQUE NOT NULL,
    creator_admin_id  UUID NOT NULL REFERENCES users(id),
    label             TEXT,
    kind              booking_kind NOT NULL DEFAULT '1X2',
    version           INT NOT NULL DEFAULT 1,
    currency          VARCHAR(3) NOT NULL DEFAULT 'GHS',
    stake             NUMERIC(19,4),
    selections        JSONB NOT NULL,
    total_odds        NUMERIC(12,4),
    potential_payout  NUMERIC(19,4),
    status            VARCHAR(20) NOT NULL DEFAULT 'active',
    redemption_count  INT NOT NULL DEFAULT 0,
    max_redemptions   INT,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- AI PREDICTIONS
CREATE TABLE ai_predictions (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    match_id              UUID NOT NULL REFERENCES matches(id),
    model                 TEXT NOT NULL DEFAULT 'mistral-large-latest',
    generated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    prediction            JSONB NOT NULL,
    shared_at             TIMESTAMPTZ,
    shared_by_admin_id    UUID REFERENCES users(id),
    is_published_to_users BOOLEAN NOT NULL DEFAULT FALSE,
    admin_note            TEXT
);

-- CUSTOM GAMES
CREATE TABLE custom_games (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    creator_admin_id      UUID NOT NULL REFERENCES users(id),
    title                 TEXT NOT NULL,
    sport                 VARCHAR(30),
    home_team             TEXT NOT NULL,
    away_team             TEXT NOT NULL,
    description           TEXT,
    kickoff_at            TIMESTAMPTZ,
    status                VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    available_in_arcade   BOOLEAN NOT NULL DEFAULT FALSE,
    available_in_virtuals BOOLEAN NOT NULL DEFAULT FALSE,
    predicted_score_home  INT,
    predicted_score_away  INT,
    actual_score_home     INT,
    actual_score_away     INT,
    is_settled            BOOLEAN NOT NULL DEFAULT FALSE,
    max_stake             NUMERIC(19,4),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CUSTOM GAME ODDS
CREATE TABLE custom_game_odds (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    custom_game_id UUID NOT NULL REFERENCES custom_games(id),
    market         odds_market NOT NULL,
    selection      TEXT NOT NULL,
    value          NUMERIC(9,3) NOT NULL
);

-- GAME ROUNDS (Arcade)
CREATE TABLE game_rounds (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id   UUID NOT NULL REFERENCES users(id),
    game      VARCHAR(30) NOT NULL,
    stake     NUMERIC(19,4) NOT NULL,
    result    JSONB,
    payout    NUMERIC(19,4),
    played_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- VIRTUAL GAMES
CREATE TABLE virtual_games (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    category       VARCHAR(30) NOT NULL,
    source         VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    custom_game_id UUID REFERENCES custom_games(id),
    schedule_slot  TIMESTAMPTZ,
    kickoff_at     TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    result         JSONB,
    home_team      TEXT,
    away_team      TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- VIP MEMBERSHIPS
CREATE TABLE vip_memberships (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id        UUID NOT NULL REFERENCES users(id),
    started_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    auto_renew     BOOLEAN NOT NULL DEFAULT FALSE,
    price_paid     NUMERIC(19,4),
    currency       VARCHAR(3) DEFAULT 'GHS',
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    activated_via  VARCHAR(20)
);

-- VIP CASHBACKS
CREATE TABLE vip_cashbacks (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    bet_id     UUID UNIQUE NOT NULL REFERENCES bets(id),
    user_id    UUID NOT NULL REFERENCES users(id),
    amount     NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- VIP GIVEAWAYS
CREATE TABLE vip_giveaways (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    week_start     TIMESTAMPTZ NOT NULL,
    prize_label    TEXT NOT NULL,
    prize_amount   NUMERIC(19,4),
    kind           TEXT,
    winner_user_id UUID REFERENCES users(id),
    drawn_at       TIMESTAMPTZ,
    fulfilled_at   TIMESTAMPTZ
);

-- VIP GIFTS
CREATE TABLE vip_gifts (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id),
    kind        gift_kind NOT NULL,
    payload     JSONB,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ
);

-- AUDIT LOG
CREATE TABLE audit_log (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    actor_user_id  UUID REFERENCES users(id),
    action         TEXT NOT NULL,
    target_entity  TEXT,
    target_id      UUID,
    before_state   JSONB,
    after_state    JSONB,
    ip_address     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- INDEXES
-- ============================================================
CREATE INDEX idx_matches_status     ON matches(status);
CREATE INDEX idx_matches_kickoff    ON matches(kickoff_at);
CREATE INDEX idx_matches_featured   ON matches(is_featured) WHERE is_featured = TRUE;
CREATE INDEX idx_bets_user          ON bets(user_id);
CREATE INDEX idx_bets_status        ON bets(status);
CREATE INDEX idx_bets_unseen_wins   ON bets(user_id, win_seen) WHERE status = 'WON' AND win_seen = FALSE;
CREATE INDEX idx_transactions_wallet ON transactions(wallet_id);
CREATE INDEX idx_referrals_link     ON referrals(link_id);
CREATE INDEX idx_ai_predictions_match     ON ai_predictions(match_id);
CREATE INDEX idx_ai_predictions_published ON ai_predictions(is_published_to_users);
CREATE INDEX idx_vip_memberships_user     ON vip_memberships(user_id);
CREATE INDEX idx_vip_memberships_status   ON vip_memberships(status, expires_at);
CREATE INDEX idx_bet_selections_bet       ON bet_selections(bet_id);
CREATE INDEX idx_bet_selections_match     ON bet_selections(match_id);
CREATE INDEX idx_odds_match               ON odds(match_id, market);
CREATE INDEX idx_custom_games_admin       ON custom_games(creator_admin_id);
CREATE INDEX idx_booking_codes_code       ON booking_codes(code);
CREATE INDEX idx_payout_requests_admin    ON payout_requests(admin_id, status);
