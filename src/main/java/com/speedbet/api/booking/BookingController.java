package com.speedbet.api.booking;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.PageResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
public class BookingController {

    private final BookingService bookingService;

    /**
     * FIX: replaced raw Map<String, String> with a typed, validated DTO.
     * Previously req.get("code") could silently pass null to bookingService.redeem(),
     * causing an unhelpful NPE deep in the service. Now @NotBlank catches it at
     * the controller boundary and returns a clean 400.
     */
    @PostMapping("/api/booking/redeem")
    public ResponseEntity<ApiResponse<BookingService.RedeemResponse>> redeem(
            @Valid @RequestBody RedeemRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.redeem(req.code())));
    }

    @PostMapping("/api/admin/booking-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BookingCode>> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BookingService.CreateBookingRequest req) {
        return ResponseEntity.ok(
                ApiResponse.ok(bookingService.create(req, user.getId()), "Booking code created"));
    }

    @GetMapping("/api/admin/booking-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<BookingCode>>> list(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResponse<>(bookingService.getForAdmin(user.getId(), PageRequest.of(page, size)))));
    }

    @GetMapping("/api/admin/booking-codes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BookingCode>> detail(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getDetail(id, user.getId())));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Typed redeem request DTO — replaces the previous raw Map<String, String>.
     * @NotBlank ensures the code field is present and non-empty before the
     * request ever reaches the service layer.
     */
    public record RedeemRequest(@NotBlank(message = "code must not be blank") String code) {}
}