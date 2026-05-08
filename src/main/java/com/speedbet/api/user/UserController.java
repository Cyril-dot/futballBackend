package com.speedbet.api.user;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final WalletService walletService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@AuthenticationPrincipal User user) {
        var wallet = walletService.getWallet(user.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "user", AuthDtos.UserDto.from(user),
                "wallet", Map.of(
                        "balance", wallet.getBalance(),
                        "currency", wallet.getCurrency()
                )
        )));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<AuthDtos.UserDto>> update(
            @AuthenticationPrincipal User user,
            @RequestBody AuthDtos.UpdateProfileRequest req) {
        var updated = userService.updateProfile(
                user.getId(), req.firstName(), req.lastName(),
                req.phone(), req.country(), req.themePreference()
        );
        return ResponseEntity.ok(ApiResponse.ok(AuthDtos.UserDto.from(updated)));
    }
}