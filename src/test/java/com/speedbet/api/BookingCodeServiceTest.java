package com.speedbet.api;

import com.speedbet.api.booking.*;
import com.speedbet.api.match.MatchRepository;
import com.speedbet.api.odds.OddsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingCodeServiceTest {

    @Mock BookingCodeRepository bookingRepo;
    @Mock MatchRepository matchRepo;
    @Mock OddsRepository oddsRepo;
    @InjectMocks BookingService bookingService;

    @Test
    void create_generatesUniqueCode() {
        when(bookingRepo.existsByCode(any())).thenReturn(false);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var selections = List.of(
            Map.<String, Object>of(
                "fixture_id", UUID.randomUUID().toString(),
                "match", "Arsenal vs Chelsea",
                "market", "1X2",
                "pick", "Home Win",
                "odds", 2.10
            )
        );
        var req = new BookingService.CreateBookingRequest(
            "1X2", "Test Code", "GHS", new BigDecimal("10"), selections, null, null);

        var code = bookingService.create(req, UUID.randomUUID());

        assertThat(code).isNotNull();
        assertThat(code.getCode()).hasSize(8);
        assertThat(code.getTotalOdds()).isEqualByComparingTo("2.10");
    }

    @Test
    void create_rejectsDuplicateFixtures() {
        var fixtureId = UUID.randomUUID().toString();
        var selections = List.of(
            Map.<String, Object>of("fixture_id", fixtureId, "market", "1X2", "pick", "Home Win", "odds", 2.10),
            Map.<String, Object>of("fixture_id", fixtureId, "market", "BTTS", "pick", "Yes", "odds", 1.70)
        );
        var req = new BookingService.CreateBookingRequest(
            "MIXED", "Dup", "GHS", null, selections, null, null);

        assertThatThrownBy(() -> bookingService.create(req, UUID.randomUUID()))
            .hasMessageContaining("Duplicate fixtures");
    }

    @Test
    void create_rejectsBelowMinOdds() {
        var selections = List.of(
            Map.<String, Object>of(
                "fixture_id", UUID.randomUUID().toString(),
                "market", "1X2", "pick", "Home Win", "odds", 1.05
            )
        );
        var req = new BookingService.CreateBookingRequest(
            "1X2", "Low Odds", "GHS", null, selections, null, null);

        assertThatThrownBy(() -> bookingService.create(req, UUID.randomUUID()))
            .hasMessageContaining("Minimum odds");
    }

    @Test
    void redeem_throwsWhenCodeNotFound() {
        when(bookingRepo.findByCode("NOTEXIST")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> bookingService.redeem("NOTEXIST"))
            .hasMessageContaining("not found");
    }

    @Test
    void redeem_throwsWhenExpired() {
        var expired = BookingCode.builder()
            .code("EXPIRXYZ")
            .status("active")
            .expiresAt(java.time.Instant.now().minusSeconds(3600))
            .selections(List.of())
            .build();
        when(bookingRepo.findByCode("EXPIRXYZ")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> bookingService.redeem("EXPIRXYZ"))
            .hasMessageContaining("expired");
    }

    @Test
    void redeem_incrementsRedemptionCount() {
        var code = BookingCode.builder()
            .code("TESTCODE")
            .status("active")
            .redemptionCount(0)
            .selections(List.of(
                Map.of("fixture_id", UUID.randomUUID().toString(),
                    "match", "A vs B", "market", "1X2", "pick", "Home Win", "odds", 2.0)
            ))
            .totalOdds(new BigDecimal("2.0"))
            .build();
        when(bookingRepo.findByCode("TESTCODE")).thenReturn(Optional.of(code));
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepo.findById(any())).thenReturn(Optional.empty());

        bookingService.redeem("TESTCODE");

        assertThat(code.getRedemptionCount()).isEqualTo(1);
    }
}
