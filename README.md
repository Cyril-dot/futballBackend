# SpeedBet API — Backend

> HIT DIFFERENT. CASH OUT SMART.

Spring Boot 3.2 + Java 21 + PostgreSQL backend for the SpeedBet sports betting platform.

---

## Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2, Java 21 |
| Security | Spring Security 6, JJWT 0.12 |
| Database | PostgreSQL 16, Spring Data JPA, Flyway |
| Cache | Spring Cache + Caffeine |
| Real-time | Spring WebSocket (STOMP over SockJS) |
| HTTP Client | Spring WebFlux WebClient |
| Rate Limiting | Bucket4j |
| Build | Maven 3.8 |

---

## Quick Start (Docker)

```bash
# 1. Clone and configure
cp .env.example .env
# Edit .env with your API keys

# 2. Start everything
docker-compose up -d

# 3. API is ready at
curl http://localhost:8080/actuator/health
```

---

## Local Development

### Prerequisites
- Java 21
- Maven 3.8+
- PostgreSQL 16 running locally

### Setup

```bash
# 1. Create the database
psql -U postgres -c "CREATE DATABASE speedbet;"
psql -U postgres -c "CREATE USER speedbet WITH PASSWORD 'speedbet123';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE speedbet TO speedbet;"

# 2. Configure environment
cp .env.example .env
# Edit .env — at minimum set SPRING_DATASOURCE_PASSWORD

# 3. Run
mvn spring-boot:run

# The API starts at http://localhost:8080
# Flyway runs migrations automatically on startup
# Demo seed data is loaded (V3__seed_data.sql)
```

---

## Demo Mode

Set `DEMO_MODE=true` in `.env` to run without real API keys:
- Sports data returns seeded demo matches/scores
- AI predictions return pre-built mock responses
- Paystack/Stripe calls are skipped

Demo login endpoint (only active when `DEMO_MODE=true`):
```
POST /api/auth/demo-login
{ "role": "USER" }       → demo user account
{ "role": "ADMIN" }      → demo admin account
{ "role": "SUPER_ADMIN" } → demo super admin account
```

---

## Seed Accounts

All seed accounts use password: **`Change!Me.2026`**

| Email | Role | Notes |
|---|---|---|
| `super@speedbet.app` | SUPER_ADMIN | Platform owner |
| `admin1@speedbet.app` | ADMIN | Demo admin with referral link |
| `admin2@speedbet.app` | ADMIN | Demo admin |
| `user@speedbet.app` | USER | GHS 500 demo balance |
| `vip@speedbet.app` | USER | Active VIP membership |

⚠️ **Change all passwords before going to production.**

---

## Key Endpoints

### Auth
```
POST /api/auth/register          Register new user
POST /api/auth/login             Login, returns JWT + sets refresh cookie
POST /api/auth/refresh           Refresh access token (uses httpOnly cookie)
POST /api/auth/logout            Clear refresh cookie
POST /api/auth/demo-login        Demo only — bypass auth
```

### Public (no auth required)
```
GET  /api/public/matches         Live + upcoming + recent results
GET  /api/public/matches/featured Featured matches for carousel
GET  /api/public/matches/{id}    Match detail
GET  /api/public/matches/{id}/commentary
GET  /api/public/config          Platform config
GET  /api/tip/{id}               Public prediction tip card
```

### User (Bearer token required)
```
GET  /api/users/me               Profile + wallet + VIP status
PATCH /api/users/me              Update profile/theme

GET  /api/wallet                 Balance
GET  /api/wallet/transactions    Ledger
POST /api/wallet/withdraw        Request withdrawal
POST /api/wallet/deposit/paystack/init  Init Paystack payment
POST /api/wallet/deposit/stripe/intent  Init Stripe payment

POST /api/bets                   Place bet
GET  /api/bets                   My bets (paginated)
GET  /api/bets/{id}              Single bet
GET  /api/bets/unseen-wins       Unseen win notifications
POST /api/bets/{id}/dismiss-win  Mark win as seen

POST /api/booking/redeem         Redeem booking code

GET  /api/predictions/public     AI predictions feed (published by admins)

GET  /api/vip/status             VIP status
POST /api/vip/subscribe          Subscribe to VIP (wallet deduct)
GET  /api/vip/gifts              My VIP gifts
POST /api/vip/gifts/{id}/consume Redeem a gift

POST /api/games/{game}/play      Play arcade game (aviator/crash/flip/dice/spin/magicball)
POST /api/games/{game}/cashout   Cash out crash game
GET  /api/games/{game}/current-round  Current crash round info
GET  /api/games/history          My game history

GET  /api/matches/{id}/odds      All market odds
GET  /api/matches/{id}/stats     Live stats
GET  /api/matches/{id}/lineups   Team lineups
GET  /api/matches/{id}/h2h       Head to head
GET  /api/matches/{id}/commentary Commentary feed
GET  /api/matches/{id}/events    Goals, cards, subs
```

### Admin (ADMIN role required)
```
GET  /api/admin/analytics        KPIs (signups, stake, commission)
GET  /api/admin/referred-users   Users this admin brought in
POST /api/admin/referral-links   Create referral link
GET  /api/admin/referral-links   List referral links

POST /api/admin/booking-codes    Create booking code (all market types)
GET  /api/admin/booking-codes    List booking codes
GET  /api/admin/booking-codes/{id} Full code with selections

POST /api/admin/predictions/run  Run AI prediction for a match
GET  /api/admin/predictions      My predictions
POST /api/admin/predictions/{id}/share   Publish to users
POST /api/admin/predictions/{id}/unpublish

POST /api/admin/payout-request   Request affiliate payout (Fridays only)
GET  /api/admin/payout-requests  Payout history

GET  /api/admin/crash/schedule/{game}    Upcoming crash schedule
GET  /api/admin/crash/history/{game}     Crash history
POST /api/admin/crash/schedule/{game}/generate  Generate new batch
PATCH /api/admin/crash/schedule/{id}/override   Override round
```

### Super Admin (SUPER_ADMIN role)
```
# Also accessible at path configured by SUPER_ADMIN_DIRECT_PATH env var
GET  /api/super-admin/admins              All admins
POST /api/super-admin/admins              Create new admin
GET  /api/super-admin/metrics             Platform KPIs
GET  /api/super-admin/payout-requests     Pending payouts
POST /api/super-admin/payout-requests/{id}/approve
POST /api/super-admin/payout-requests/{id}/reject
POST /api/super-admin/payout-requests/{id}/mark-paid
GET  /api/super-admin/predictions         All AI predictions
GET  /api/super-admin/audit-log           Full audit log
POST /api/super-admin/vip/grant           Grant VIP to user
```

### WebSocket (STOMP over SockJS)
```
Connect: ws://localhost:8080/ws

Subscribe to:
  /topic/livescore/{matchId}      Live score + minute updates
  /topic/commentary/{matchId}     Match commentary events
  /topic/admin/crash-alerts       HIGH/EXTREME crash alerts (admin)
```

### Webhooks
```
POST /api/webhooks/paystack       Paystack payment events (HMAC-SHA512 verified)
POST /api/webhooks/stripe         Stripe payment events (Stripe-Signature verified)
```

---

## Scheduled Jobs

| Job | Schedule | Purpose |
|---|---|---|
| LiveScorePoller | Every 10s | Poll sportdb.dev live scores |
| CommentaryPoller | Every 30s | Push commentary via WebSocket |
| FixtureIngest | Every 15 min | Pull upcoming fixtures 72h ahead |
| SettlementEngine | Every 1 min | Settle FINISHED matches |
| PredictionRefresher | Every 1 hour | Auto-predict matches kicking off in 24h |
| CrashScheduleReplenisher | Every 1 hour | Keep 100+ crash rounds buffered per game |
| CrashHighDetector | Every 5s | Alert admins of HIGH/EXTREME rounds |
| VipExpirer | Every 15 min | Expire/auto-renew VIP memberships |
| WeeklyGiftDrop | Mon 08:00 UTC | Issue gifts to all VIPs |
| WeeklyGiveawayDraw | Sun 20:00 UTC | Pick random VIP winner |
| FridayPayoutWindow | Fri 00:00 UTC | Open affiliate payout window |
| AuditCompaction | Daily 02:00 UTC | Archive audit rows >90 days old |

---

## Running Tests

```bash
mvn test
```

Tests use Mockito (no DB required). Test classes:
- `AuthServiceTest` — register, JWT generation/validation
- `BetServiceTest` — placement, odds drift rejection, max selections
- `SettlementEngineTest` — all market outcomes (1X2, BTTS, CS, DC, OU)
- `VipCashbackTest` — cashback eligibility rules
- `ReferralServiceTest` — commission attribution
- `BookingCodeServiceTest` — creation, validation, redemption

---

## Environment Variables

See `.env.example` for the full list.

Critical variables:
- `JWT_SECRET` — min 64 chars, generate with `openssl rand -base64 64`
- `SUPER_ADMIN_DIRECT_PATH` — never expose this in any UI
- `DEMO_MODE=false` — must be false in production

---

## Going Live Checklist

- [ ] Set real `PAYSTACK_SECRET_KEY` and `PAYSTACK_WEBHOOK_SECRET`
- [ ] Set real `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET`
- [ ] Set real `SPORTDB_API_KEY` (sportdb.dev)
- [ ] Set real `SPORTSRC_API_KEY` (SportSRC v2 via RapidAPI)
- [ ] Set real `MISTRAL_API_KEY`
- [ ] Change all seed account passwords
- [ ] Set `DEMO_MODE=false`
- [ ] Set strong `JWT_SECRET` (openssl rand -base64 64)
- [ ] Change `SUPER_ADMIN_DIRECT_PATH` to a secret value
- [ ] Set `FRONTEND_URL` to your production domain
- [ ] Configure HTTPS (Nginx/Cloudflare in front)
- [ ] Verify Paystack webhook signature is working
- [ ] Verify Stripe webhook signature is working
