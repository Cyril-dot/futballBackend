package com.speedbet.api.superadmin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/super-admin/sms")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminSmsController {

    private final SuperAdminSmsService smsService;

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record SendSmsToPhoneRequest(
            @NotBlank(message = "phoneNumber is required")
            String phoneNumber,

            @NotBlank(message = "message is required")
            @Size(max = 1000, message = "message must not exceed 1000 characters")
            String message
    ) {}

    public record SendSmsToUserRequest(
            @NotBlank(message = "message is required")
            @Size(max = 1000, message = "message must not exceed 1000 characters")
            String message
    ) {}

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/super-admin/sms/send
     *
     * Send an SMS directly to any phone number.
     * Body: { "phoneNumber": "+233XXXXXXXXX", "message": "Hello!" }
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendSmsToPhone(
            @Valid @RequestBody SendSmsToPhoneRequest request,
            @AuthenticationPrincipal(expression = "id") UUID adminId) {

        log.info("POST /super-admin/sms/send — adminId='{}' phone='{}'",
                adminId, request.phoneNumber());

        smsService.sendSmsToPhone(request.phoneNumber(), request.message(), adminId);

        return ResponseEntity.ok(Map.of(
                "status", "sent",
                "phone", request.phoneNumber(),
                "message", "SMS dispatched successfully"
        ));
    }

    /**
     * POST /api/v1/super-admin/sms/send/{userId}
     *
     * Send an SMS to a user identified by their userId.
     * Their phone number is looked up from the database automatically.
     * Body: { "message": "Hello!" }
     */
    @PostMapping("/send/{userId}")
    public ResponseEntity<Map<String, String>> sendSmsToUser(
            @PathVariable UUID userId,
            @Valid @RequestBody SendSmsToUserRequest request,
            @AuthenticationPrincipal(expression = "id") UUID adminId) {

        log.info("POST /super-admin/sms/send/{} — adminId='{}'", userId, adminId);

        smsService.sendSmsToUser(userId, request.message(), adminId);

        return ResponseEntity.ok(Map.of(
                "status", "sent",
                "userId", userId.toString(),
                "message", "SMS dispatched successfully"
        ));
    }
}