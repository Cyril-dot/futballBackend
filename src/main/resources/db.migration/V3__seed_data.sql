-- ============================================================
-- SpeedBet V3 — Seed Data
-- ============================================================

-- Super Admin (password: Change!Me.2026 — BCrypt encoded)
INSERT INTO users (id, email, password_hash, first_name, last_name, role, status)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'super@speedbet.app',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCwUBT2YZNXI5rYz5rN0zHe',
    'Super', 'Admin', 'SUPER_ADMIN', 'ACTIVE'
);

INSERT INTO wallets (user_id, currency, balance)
VALUES ('a0000000-0000-0000-0000-000000000001', 'GHS', 0);

-- Admin 1
INSERT INTO users (id, email, password_hash, first_name, last_name, role, status,
                   created_by_admin_id)
VALUES (
    'a0000000-0000-0000-0000-000000000002',
    'admin1@speedbet.app',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCwUBT2YZNXI5rYz5rN0zHe',
    'Admin', 'One', 'ADMIN', 'ACTIVE',
    'a0000000-0000-0000-0000-000000000001'
);
INSERT INTO wallets (user_id, currency, balance)
VALUES ('a0000000-0000-0000-0000-000000000002', 'GHS', 120.00);

-- Admin 2
INSERT INTO users (id, email, password_hash, first_name, last_name, role, status,
                   created_by_admin_id)
VALUES (
    'a0000000-0000-0000-0000-000000000003',
    'admin2@speedbet.app',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCwUBT2YZNXI5rYz5rN0zHe',
    'Admin', 'Two', 'ADMIN', 'ACTIVE',
    'a0000000-0000-0000-0000-000000000001'
);
INSERT INTO wallets (user_id, currency, balance)
VALUES ('a0000000-0000-0000-0000-000000000003', 'GHS', 85.50);

-- Demo User (password: demo123)
INSERT INTO users (id, email, password_hash, first_name, last_name, role, status)
VALUES (
    'a0000000-0000-0000-0000-000000000004',
    'user@speedbet.app',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCwUBT2YZNXI5rYz5rN0zHe',
    'Demo', 'User', 'USER', 'ACTIVE'
);
INSERT INTO wallets (user_id, currency, balance)
VALUES ('a0000000-0000-0000-0000-000000000004', 'GHS', 500.00);

-- VIP User
INSERT INTO users (id, email, password_hash, first_name, last_name, role, status)
VALUES (
    'a0000000-0000-0000-0000-000000000005',
    'vip@speedbet.app',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCwUBT2YZNXI5rYz5rN0zHe',
    'VIP', 'Member', 'USER', 'ACTIVE'
);
INSERT INTO wallets (user_id, currency, balance)
VALUES ('a0000000-0000-0000-0000-000000000005', 'GHS', 1200.00);

INSERT INTO vip_memberships (user_id, started_at, expires_at, auto_renew, price_paid, status, activated_via)
VALUES (
    'a0000000-0000-0000-0000-000000000005',
    now(), now() + interval '30 days',
    true, 250.00, 'ACTIVE', 'WALLET'
);

-- Referral links for admins
INSERT INTO referral_links (id, admin_id, code, label, commission_percent, is_active)
VALUES
    ('b0000000-0000-0000-0000-000000000001',
     'a0000000-0000-0000-0000-000000000002',
     'ADMIN1REF', 'Admin1 Main Link', 10, true),
    ('b0000000-0000-0000-0000-000000000002',
     'a0000000-0000-0000-0000-000000000003',
     'ADMIN2REF', 'Admin2 Main Link', 10, true);

-- Sample matches
INSERT INTO matches (id, source, external_id, sport, league, home_team, away_team,
                     kickoff_at, status, score_home, score_away, is_featured)
VALUES
    ('c0000000-0000-0000-0000-000000000001',
     'SPORTDB', 'EPL-001', 'Football', 'Premier League',
     'Arsenal', 'Chelsea', now() + interval '2 hours',
     'UPCOMING', NULL, NULL, true),
    ('c0000000-0000-0000-0000-000000000002',
     'SPORTDB', 'EPL-002', 'Football', 'Premier League',
     'Manchester City', 'Liverpool', now() + interval '4 hours',
     'UPCOMING', NULL, NULL, true),
    ('c0000000-0000-0000-0000-000000000003',
     'SPORTDB', 'LL-001', 'Football', 'La Liga',
     'Barcelona', 'Real Madrid', now() - interval '30 minutes',
     'LIVE', 1, 0, true),
    ('c0000000-0000-0000-0000-000000000004',
     'SPORTDB', 'UCL-001', 'Football', 'Champions League',
     'PSG', 'Bayern Munich', now() - interval '2 hours',
     'FINISHED', 2, 1, false);

-- Odds for matches
INSERT INTO odds (match_id, market, selection, value)
VALUES
    -- Arsenal vs Chelsea 1X2
    ('c0000000-0000-0000-0000-000000000001', '1X2', 'Home Win', 2.10),
    ('c0000000-0000-0000-0000-000000000001', '1X2', 'Draw', 3.40),
    ('c0000000-0000-0000-0000-000000000001', '1X2', 'Away Win', 3.20),
    -- Arsenal vs Chelsea BTTS
    ('c0000000-0000-0000-0000-000000000001', 'BTTS', 'Yes', 1.70),
    ('c0000000-0000-0000-0000-000000000001', 'BTTS', 'No', 2.10),
    -- Arsenal vs Chelsea Over/Under
    ('c0000000-0000-0000-0000-000000000001', 'OVER_UNDER', 'Over', 1.85),
    ('c0000000-0000-0000-0000-000000000001', 'OVER_UNDER', 'Under', 1.95),
    -- Man City vs Liverpool 1X2
    ('c0000000-0000-0000-0000-000000000002', '1X2', 'Home Win', 1.95),
    ('c0000000-0000-0000-0000-000000000002', '1X2', 'Draw', 3.50),
    ('c0000000-0000-0000-0000-000000000002', '1X2', 'Away Win', 3.80),
    -- Barcelona vs Real Madrid 1X2 (LIVE)
    ('c0000000-0000-0000-0000-000000000003', '1X2', 'Home Win', 1.75),
    ('c0000000-0000-0000-0000-000000000003', '1X2', 'Draw', 3.60),
    ('c0000000-0000-0000-0000-000000000003', '1X2', 'Away Win', 4.20);

-- Sample booking codes
INSERT INTO booking_codes (code, creator_admin_id, label, kind, version, currency,
                            stake, selections, total_odds, potential_payout, status)
VALUES
    ('ARSNLWIN',
     'a0000000-0000-0000-0000-000000000002',
     'Arsenal Home Win Banker',
     '1X2', 1, 'GHS', 10.00,
     '[{"fixture_id":"c0000000-0000-0000-0000-000000000001","match":"Arsenal vs Chelsea","market":"1X2","pick":"Home Win","odds":2.10,"result":null}]',
     2.10, 21.00, 'active'),
    ('ACCA4WIN',
     'a0000000-0000-0000-0000-000000000002',
     'Saturday Acca',
     'MIXED', 1, 'GHS', 10.00,
     '[{"fixture_id":"c0000000-0000-0000-0000-000000000001","match":"Arsenal vs Chelsea","market":"1X2","pick":"Home Win","odds":2.10,"result":null},{"fixture_id":"c0000000-0000-0000-0000-000000000002","match":"Man City vs Liverpool","market":"BTTS","pick":"Yes","odds":1.75,"result":null}]',
     3.675, 36.75, 'active');

-- Crash schedule seed (aviator — 10 rounds)
INSERT INTO game_crash_schedule (game_slug, round_number, crash_at, tier, is_high_crash,
                                  is_extreme_crash, generated_by)
VALUES
    ('aviator', 1, 1.24, 'LOW', false, false, 'PRNG_FALLBACK'),
    ('aviator', 2, 3.45, 'MEDIUM', false, false, 'PRNG_FALLBACK'),
    ('aviator', 3, 1.02, 'LOW', false, false, 'PRNG_FALLBACK'),
    ('aviator', 4, 12.40, 'HIGH', true, false, 'PRNG_FALLBACK'),
    ('aviator', 5, 2.18, 'MEDIUM', false, false, 'PRNG_FALLBACK'),
    ('aviator', 6, 1.55, 'LOW', false, false, 'PRNG_FALLBACK'),
    ('aviator', 7, 34.80, 'EXTREME', true, true, 'PRNG_FALLBACK'),
    ('aviator', 8, 1.87, 'LOW', false, false, 'PRNG_FALLBACK'),
    ('aviator', 9, 4.20, 'MEDIUM', false, false, 'PRNG_FALLBACK'),
    ('aviator', 10, 1.33, 'LOW', false, false, 'PRNG_FALLBACK'),
    ('crash',   1, 1.50, 'LOW', false, false, 'PRNG_FALLBACK'),
    ('crash',   2, 5.80, 'HIGH', false, false, 'PRNG_FALLBACK'),
    ('crash',   3, 1.10, 'LOW', false, false, 'PRNG_FALLBACK'),
    ('crash',   4, 2.90, 'MEDIUM', false, false, 'PRNG_FALLBACK'),
    ('crash',   5, 1.40, 'LOW', false, false, 'PRNG_FALLBACK');
