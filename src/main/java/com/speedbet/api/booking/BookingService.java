package com.speedbet.api.booking;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.match.Match;
import com.speedbet.api.match.MatchRepository;
import com.speedbet.api.match.MatchSource;
import com.speedbet.api.odds.OddsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingCodeRepository bookingRepo;
    private final MatchRepository matchRepo;
    private final OddsRepository oddsRepo;

    // ══════════════════════════════════════════════════════════════════════
    // ORIGINAL CREATE  (unchanged — external/public booking codes)
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public BookingCode create(CreateBookingRequest req, UUID adminId) {
        log.info("createBookingCode: adminId={} label='{}' kind={} stake={} selections={}",
                adminId, req.label(), req.kind(), req.stake(), req.selections().size());

        validateSelections(req.selections());

        BigDecimal totalOdds = computeTotalOdds(req.selections());
        BigDecimal potentialPayout = computePayout(req.stake(), totalOdds);
        String currency = resolveCurrency(req.currency());
        String kind = resolveKind(req.kind(), req.selections());

        BookingCode saved = bookingRepo.save(BookingCode.builder()
                .code(generateUniqueCode())
                .creatorAdminId(adminId)
                .label(req.label())
                .kind(kind)
                .currency(currency)
                .stake(req.stake())
                .selections(req.selections())
                .totalOdds(totalOdds)
                .potentialPayout(potentialPayout)
                .maxRedemptions(req.maxRedemptions())
                .expiresAt(req.expiresAt())
                // bookingType defaults to "STANDARD" via @Builder.Default
                .build());

        log.info("createBookingCode: success — code={} id={} kind={} totalOdds={} payout={}",
                saved.getCode(), saved.getId(), saved.getKind(), totalOdds, potentialPayout);

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW: ADMIN-ONLY BOOKING CODE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates a booking code whose selections are restricted to matches that
     * this admin personally created (source = ADMIN_CREATED, createdByAdminId
     * = adminId).
     *
     * Every fixture_id in the request is looked up and ownership is enforced
     * before the code is persisted. Any fixture that is not found, belongs to
     * a different admin, or comes from an external feed causes a 400 error so
     * the admin gets clear feedback on exactly which selection failed.
     *
     * Wire up to: POST /admin/booking-codes/admin-only
     */
    @Transactional
    public BookingCode createAdminOnly(CreateBookingRequest req, UUID adminId) {
        log.info("createAdminOnlyBookingCode: adminId={} label='{}' kind={} stake={} selections={}",
                adminId, req.label(), req.kind(), req.stake(), req.selections().size());

        validateSelections(req.selections());
        validateAdminOwnedSelections(req.selections(), adminId);

        BigDecimal totalOdds = computeTotalOdds(req.selections());
        BigDecimal potentialPayout = computePayout(req.stake(), totalOdds);
        String currency = resolveCurrency(req.currency());
        String kind = resolveKind(req.kind(), req.selections());

        BookingCode saved = bookingRepo.save(BookingCode.builder()
                .code(generateUniqueCode())
                .creatorAdminId(adminId)
                .label(req.label())
                .kind(kind)
                .currency(currency)
                .stake(req.stake())
                .selections(req.selections())
                .totalOdds(totalOdds)
                .potentialPayout(potentialPayout)
                .maxRedemptions(req.maxRedemptions())
                .expiresAt(req.expiresAt())
                .bookingType("ADMIN_ONLY")
                .build());

        log.info("createAdminOnlyBookingCode: success — code={} id={} kind={} totalOdds={} payout={}",
                saved.getCode(), saved.getId(), saved.getKind(), totalOdds, potentialPayout);

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW: MIXED BOOKING CODE  (admin games + external-feed games)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates a booking code that can combine the calling admin's own matches
     * with external-feed matches (MatchSource.EXTERNAL) in a single slip.
     *
     * Ownership rules enforced per selection:
     *   • ADMIN_CREATED fixture owned by this admin → allowed.
     *   • ADMIN_CREATED fixture owned by another admin → rejected (404-style message).
     *   • EXTERNAL fixture → always allowed.
     *   • Unknown fixture_id → rejected with 400.
     *
     * Wire up to: POST /admin/booking-codes/mixed
     */
    @Transactional
    public BookingCode createMixed(CreateBookingRequest req, UUID adminId) {
        log.info("createMixedBookingCode: adminId={} label='{}' kind={} stake={} selections={}",
                adminId, req.label(), req.kind(), req.stake(), req.selections().size());

        validateSelections(req.selections());
        validateMixedSelections(req.selections(), adminId);

        BigDecimal totalOdds = computeTotalOdds(req.selections());
        BigDecimal potentialPayout = computePayout(req.stake(), totalOdds);
        String currency = resolveCurrency(req.currency());
        String kind = resolveKind(req.kind(), req.selections());

        BookingCode saved = bookingRepo.save(BookingCode.builder()
                .code(generateUniqueCode())
                .creatorAdminId(adminId)
                .label(req.label())
                .kind(kind)
                .currency(currency)
                .stake(req.stake())
                .selections(req.selections())
                .totalOdds(totalOdds)
                .potentialPayout(potentialPayout)
                .maxRedemptions(req.maxRedemptions())
                .expiresAt(req.expiresAt())
                .bookingType("MIXED")
                .build());

        log.info("createMixedBookingCode: success — code={} id={} kind={} totalOdds={} payout={}",
                saved.getCode(), saved.getId(), saved.getKind(), totalOdds, potentialPayout);

        return saved;
    }

    // ══════════════════════════════════════════════════════════════════════
    // REDEEM  (unchanged except redundant cast removed)
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public RedeemResponse redeem(String code) {
        log.info("redeemBookingCode: code={}", code);

        var booking = bookingRepo.findByCode(code.toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Booking code not found: " + code));

        if (!"active".equals(booking.getStatus()))
            throw ApiException.badRequest("Booking code is " + booking.getStatus());
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(Instant.now()))
            throw ApiException.badRequest("Booking code has expired");
        if (booking.getMaxRedemptions() != null &&
                booking.getRedemptionCount() >= booking.getMaxRedemptions())
            throw ApiException.badRequest("Booking code has reached max redemptions");

        booking.setRedemptionCount(booking.getRedemptionCount() + 1);
        bookingRepo.save(booking);

        log.info("redeemBookingCode: code={} redemptionCount={}", code, booking.getRedemptionCount());

        List<Map<String, Object>> enrichedSelections = booking.getSelections().stream().map(sel -> {
            Map<String, Object> enriched = new HashMap<>(sel);
            Object fixtureIdRaw = sel.get("fixture_id");
            Object marketRaw    = sel.get("market");
            Object pickRaw      = sel.get("pick");

            if (fixtureIdRaw == null || marketRaw == null || pickRaw == null) {
                log.warn("redeemBookingCode: selection missing required field(s) — skipping enrichment for sel={}", sel);
                return enriched;
            }

            try {
                UUID matchId = UUID.fromString(fixtureIdRaw.toString());
                matchRepo.findById(matchId).ifPresent(m -> {
                    enriched.put("homeTeam", m.getHomeTeam());
                    enriched.put("awayTeam", m.getAwayTeam());
                    enriched.put("league", m.getLeague());
                    oddsRepo.findByMatchIdAndMarketAndSelection(
                            matchId,
                            marketRaw.toString(),
                            pickRaw.toString()
                    ).ifPresent(o -> enriched.put("odds", o.getValue()));
                });
            } catch (Exception e) {
                log.warn("redeemBookingCode: failed to enrich selection fixture_id={} — {}",
                        fixtureIdRaw, e.getMessage());
            }
            return enriched;
        }).toList();

        BigDecimal currentTotalOdds = computeTotalOdds(enrichedSelections);

        return new RedeemResponse(booking, enrichedSelections, currentTotalOdds);
    }

    // ══════════════════════════════════════════════════════════════════════
    // READ  (unchanged)
    // ══════════════════════════════════════════════════════════════════════

    public Page<BookingCode> getForAdmin(UUID adminId, Pageable pageable) {
        log.info("getBookingCodesForAdmin: adminId={}", adminId);
        return bookingRepo.findByCreatorAdminIdOrderByCreatedAtDesc(adminId, pageable);
    }

    public BookingCode getDetail(UUID id, UUID adminId) {
        log.info("getBookingCodeDetail: id={} adminId={}", id, adminId);
        var code = bookingRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Booking code not found"));
        if (!code.getCreatorAdminId().equals(adminId))
            throw ApiException.forbidden("Not your booking code");
        return code;
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW VALIDATION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * All fixtures must be ADMIN_CREATED and owned by the calling admin.
     * Called exclusively by {@link #createAdminOnly}.
     */
    private void validateAdminOwnedSelections(List<Map<String, Object>> selections, UUID adminId) {
        for (var sel : selections) {
            UUID fixtureId = UUID.fromString(sel.get("fixture_id").toString());
            Match match = matchRepo.findById(fixtureId)
                    .orElseThrow(() -> ApiException.badRequest("Fixture not found: " + fixtureId));

            if (match.getSource() != MatchSource.ADMIN_CREATED)
                throw ApiException.badRequest(
                        "Fixture " + fixtureId + " is not an admin-created match. " +
                                "Admin-only booking codes must use your own fixtures.");

            if (!adminId.equals(match.getCreatedByAdminId()))
                throw ApiException.badRequest(
                        "Fixture " + fixtureId + " does not belong to your account.");
        }
    }

    /**
     * ADMIN_CREATED fixtures must be owned by this admin.
     * EXTERNAL fixtures pass freely.
     * Called exclusively by {@link #createMixed}.
     */
    private void validateMixedSelections(List<Map<String, Object>> selections, UUID adminId) {
        for (var sel : selections) {
            UUID fixtureId = UUID.fromString(sel.get("fixture_id").toString());
            Match match = matchRepo.findById(fixtureId)
                    .orElseThrow(() -> ApiException.badRequest("Fixture not found: " + fixtureId));

            if (match.getSource() == MatchSource.ADMIN_CREATED
                    && !adminId.equals(match.getCreatedByAdminId()))
                throw ApiException.badRequest(
                        "Fixture " + fixtureId + " does not belong to your account.");

            // MatchSource.EXTERNAL — no ownership check needed
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EXISTING HELPERS  (validateSelections unchanged; generateCode inlined)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * FIX: per-selection field validation runs BEFORE the duplicate fixture_id
     * check so we never call .toString() on a null fixture_id.
     */
    private void validateSelections(List<Map<String, Object>> selections) {
        if (selections == null || selections.isEmpty())
            throw ApiException.badRequest("At least one selection required");
        if (selections.size() > 20)
            throw ApiException.badRequest("Max 20 selections");

        for (var sel : selections) {
            if (sel.get("fixture_id") == null)
                throw ApiException.badRequest("Selection missing fixture_id");
            if (sel.get("odds") == null)
                throw ApiException.badRequest("Selection missing odds");
            if (sel.get("market") == null)
                throw ApiException.badRequest("Selection missing market");
            if (sel.get("pick") == null)
                throw ApiException.badRequest("Selection missing pick");

            var odds = new BigDecimal(sel.get("odds").toString());
            if (odds.compareTo(new BigDecimal("1.10")) < 0)
                throw ApiException.badRequest("Minimum odds per selection is 1.10");
        }

        var fixtureIds = selections.stream()
                .map(s -> s.get("fixture_id").toString())
                .toList();
        if (fixtureIds.size() != fixtureIds.stream().distinct().count())
            throw ApiException.badRequest("Duplicate fixtures in booking code");
    }

    private String generateUniqueCode() {
        String code;
        int attempts = 0;
        do {
            // Build an 8-char code inline — length is always CODE_LENGTH so no
            // need for a separate parameterised method (removes "value always 8" warning)
            var sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++)
                sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
            code = sb.toString();
            attempts++;
        } while (bookingRepo.existsByCode(code));
        log.debug("generateUniqueCode: code={} attempts={}", code, attempts);
        return code;
    }

    // ── Small computation helpers shared across all three create methods ──

    private BigDecimal computeTotalOdds(List<Map<String, Object>> selections) {
        return selections.stream()
                .map(s -> new BigDecimal(s.get("odds").toString()))
                .reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, MathContext.DECIMAL64));
    }

    private BigDecimal computePayout(BigDecimal stake, BigDecimal totalOdds) {
        return stake != null ? stake.multiply(totalOdds, MathContext.DECIMAL64) : null;
    }

    private String resolveCurrency(String currency) {
        return (currency != null && !currency.isBlank()) ? currency : "GHS";
    }

    private String resolveKind(String kind, List<Map<String, Object>> selections) {
        if (kind != null && !kind.isBlank()) return kind.toUpperCase();
        if (selections == null || selections.isEmpty()) return "MIXED";
        var markets = selections.stream()
                .map(s -> s.get("market") == null ? "" : s.get("market").toString().toUpperCase())
                .distinct()
                .toList();
        return markets.size() == 1 ? markets.get(0) : "MIXED";
    }

    // ══════════════════════════════════════════════════════════════════════
    // DTOs  (unchanged)
    // ══════════════════════════════════════════════════════════════════════

    public record CreateBookingRequest(
            String kind,
            String label,
            String currency,
            BigDecimal stake,
            List<Map<String, Object>> selections,
            Integer maxRedemptions,
            Instant expiresAt
    ) {}

    public record RedeemResponse(
            BookingCode booking,
            List<Map<String, Object>> enrichedSelections,
            BigDecimal currentTotalOdds
    ) {}
}