package com.speedbet.api.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record RegisterRequest(
            @Email(message = "Valid email required") @NotBlank String email,
            @NotBlank @Size(min = 6, message = "Password must be at least 6 characters") String password,
            @NotBlank String firstName,
            @NotBlank String lastName,
            String phone,
            String country,
            String ref
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record AuthResponse(
            String accessToken,
            String tokenType,
            UserDto user,
            boolean mustSetup2fa
    ) {
        public AuthResponse(String accessToken, UserDto user) {
            this(accessToken, "Bearer", user, false);
        }

        public AuthResponse withMustSetup2fa(boolean value) {
            return new AuthResponse(accessToken, tokenType, user, value);
        }
    }

    public record UserDto(
            String id,
            String email,
            String firstName,
            String lastName,
            String phone,
            String country,
            String role,
            String themePreference
    ) {
        public static UserDto from(User u) {
            return new UserDto(
                    u.getId().toString(),
                    u.getEmail(),
                    u.getFirstName(),
                    u.getLastName(),
                    u.getPhone(),
                    u.getCountry(),
                    u.getRole().name(),
                    u.getThemePreference()
            );
        }
    }

    public record UpdateProfileRequest(
            String firstName,
            String lastName,
            String phone,
            String country,
            String themePreference
    ) {}

    public record DemoLoginRequest(String role) {}
}