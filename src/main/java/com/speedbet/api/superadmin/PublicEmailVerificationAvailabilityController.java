package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.ReoonEmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public read of whether email verification is on (covered by the
 * /api/public/** permitAll rule). The account page reads this to decide
 * whether to show the mail as "verifying" — nothing sensitive lives
 * here, just one boolean. Available is true only when the Reoon switch
 * is ON and a key resolves (stored or env) — the same single gate
 * ReoonEmailService.configured() that the whole verification system
 * consults, so the frontend and the backend can never disagree about
 * whether verification is on. When it is false, verification is off
 * for all: no checks, no deadlines, no blocks, no deletions.
 */
@RestController
@RequestMapping("/api/public/email-verification/available")
@RequiredArgsConstructor
public class PublicEmailVerificationAvailabilityController {

    private final ReoonEmailService reoonEmailService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> get() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("available", reoonEmailService.configured())));
    }
}
