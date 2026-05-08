package com.speedbet.api;

import com.speedbet.api.security.JwtService;
import com.speedbet.api.user.*;
import com.speedbet.api.wallet.Wallet;
import com.speedbet.api.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepo;
    @Mock WalletRepository walletRepo;
    @InjectMocks UserService userService;

    private PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setup() {
        try {
            var field = UserService.class.getDeclaredField("passwordEncoder");
            field.setAccessible(true);
            field.set(userService, encoder);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void register_createsUserAndWallet() {
        when(userRepo.existsByEmail("test@speedbet.app")).thenReturn(false);
        var savedUser = User.builder().id(UUID.randomUUID()).email("test@speedbet.app")
                .passwordHash("hash").role(UserRole.USER).status(UserStatus.ACTIVE).build();
        when(userRepo.save(any())).thenReturn(savedUser);
        when(walletRepo.save(any())).thenReturn(Wallet.builder()
                .userId(savedUser.getId()).balance(BigDecimal.ZERO).build());

        var user = userService.register(
                "test@speedbet.app", "password123",
                "Test", "User", null, "Ghana", null
        );

        assertThat(user).isNotNull();
        assertThat(user.getEmail()).isEqualTo("test@speedbet.app");
        verify(walletRepo, times(1)).save(any());
    }

    @Test
    void register_throwsConflict_whenEmailExists() {
        when(userRepo.existsByEmail("taken@speedbet.app")).thenReturn(true);
        assertThatThrownBy(() -> userService.register(
                "taken@speedbet.app", "pass", "A", "B", null, "Ghana", null))
                .hasMessageContaining("already registered");
    }

    @Test
    void jwtService_generateAndValidate() {
        var jwtService = new JwtService();
        setField(jwtService, "secret",
                "SpeedBetSuperSecretKeyForJWTSigningMustBe512BitsLongAtMinimumPleaseChangeInProduction2026!!");
        setField(jwtService, "accessExpiryMinutes", 30L);
        setField(jwtService, "refreshExpiryDays", 14L);

        var user = User.builder().email("test@speedbet.app")
                .role(UserRole.USER).status(UserStatus.ACTIVE)
                .passwordHash("hash").build();

        var token = jwtService.generateAccessToken(user, java.util.Map.of());
        assertThat(token).isNotBlank();
        assertThat(jwtService.extractUsername(token)).isEqualTo("test@speedbet.app");
        assertThat(jwtService.isValid(token, user)).isTrue();
        assertThat(jwtService.isExpired(token)).isFalse();
    }

    private void setField(Object obj, String name, Object val) {
        try {
            var f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, val);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}