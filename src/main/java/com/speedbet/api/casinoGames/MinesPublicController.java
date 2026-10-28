package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public Mines facts for the game page: the layout mode and, in MANUAL,
 * the fixed bomb COUNT. Fixed positions are never exposed here — players
 * learn the count so the picker can lock to it, never the tiles.
 * (GET /api/public/** is permitAll in SecurityConfig.)
 */
@RestController
@RequestMapping("/api/public/mines")
@RequiredArgsConstructor
public class MinesPublicController {

    private final MinesSettingsService settingsService;

    @GetMapping("/mode")
    public ResponseEntity<ApiResponse<MinesSettingsService.ModeView>> mode() {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.modeView()));
    }
}
