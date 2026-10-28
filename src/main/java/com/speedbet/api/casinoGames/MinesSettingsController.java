package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Super-admin control for the Mines layout mode. The toggle and the fixed
 * bomb tiles live here and nowhere else — admins cannot reach these paths.
 */
@RestController
@RequestMapping("/api/super-admin/mines")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class MinesSettingsController {

    private final MinesSettingsService settingsService;

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<MinesSettingsService.SettingsView>> settings() {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.get()));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<MinesSettingsService.SettingsView>> saveSettings(
            @AuthenticationPrincipal User user,
            @RequestBody SaveMinesSettingsRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.save(
                request.mode(), request.bombPositions(), user.getEmail())));
    }

    public record SaveMinesSettingsRequest(MinesMode mode, List<Integer> bombPositions) {}
}
