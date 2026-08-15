package com.speedbet.api.config;

import com.speedbet.api.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.platform.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${app.super-admin.path:/x-control-9f3a2b}")
    private String superAdminPath;

    public SecurityConfig(@Lazy JwtAuthFilter jwtAuthFilter,
                          UserDetailsService userDetailsService,
                          PasswordEncoder passwordEncoder) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // ── Swagger / OpenAPI ──────────────────────────────────────────────
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/api/geo/**"
                        ).permitAll()

                        // ── Auth ───────────────────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/demo-login").permitAll()

                        // ── Fully public: all /api/public/** ──────────────────────────────
                        // Covers every sport controller's public routes in one rule:
                        //   MatchController       → /api/public/matches/**
                        //                           /api/public/football/**
                        //                           /api/public/leagues/**
                        //                           /api/public/cups/**
                        //                           /api/public/teams/**
                        //                           /api/public/standings/**
                        //                           /api/public/scorers/**
                        //                           /api/public/config
                        //   NbaMatchController    → /api/public/nba/**
                        //                           /api/public/basketball/**
                        //   NflMatchController    → /api/public/nfl/**
                        //   TennisMatchController → /api/public/tennis/**
                        //   MmaMatchController    → /api/public/mma/**
                        //   BaseballMatchController→/api/public/baseball/**
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()

                        // ── Public unauthenticated match endpoints ────────────────────────
                        // Generic football / cross-sport
                        .requestMatchers(HttpMethod.GET, "/api/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/future").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/featured").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/search").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/wallet/deposit/flutterwave/gh/init",
                                "/api/wallet/deposit/flutterwave/ng/init"
                        ).authenticated()
                        // ── NBA / Basketball public unauthenticated ────────────────────────
                        .requestMatchers(HttpMethod.GET, "/api/nba/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nba/matches/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nba/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nba/matches/future").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nba/matches/results").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nba/standings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/basketball/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/basketball/matches/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/basketball/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/basketball/matches/future").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/basketball/matches/results").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/basketball/standings").permitAll()

                        .requestMatchers(HttpMethod.POST,
                                "/api/wallet/deposit/expresspay/verify"
                        ).authenticated()

                        // ── NFL public unauthenticated ────────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/api/nfl/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nfl/matches/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nfl/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nfl/matches/results").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/nfl/standings").permitAll()

                        // ── Tennis public unauthenticated ─────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/api/tennis/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/matches/results").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/matches/featured").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/atp/rankings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/wta/rankings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/atp/tournaments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/wta/tournaments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/atp/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/wta/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/atp/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/wta/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/atp/matches").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/tennis/wta/matches").permitAll()

                        // ── MMA public unauthenticated ────────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/api/mma/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/mma/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/mma/matches/results").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/mma/matches/featured").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/mma/espn/events").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/mma/espn/events/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/mma/espn/events/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/mma/espn/events/finished").permitAll()

                        // ── Baseball public unauthenticated ───────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/api/baseball/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/baseball/matches/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/baseball/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/baseball/matches/results").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/baseball/standings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/baseball/espn/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/baseball/espn/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/baseball/espn/upcoming").permitAll()

                        // ── Tips & webhooks ───────────────────────────────────────────────
                        // NOTE: /api/webhooks/** covers /api/webhooks/moolre automatically.
                        //       Moolre webhook identity is verified internally via the secret
                        //       field in the payload (see MoolreController.verifyWebhookSecret).
                        //       It ALSO covers /api/webhooks/rushpay — RushPay webhook identity
                        //       is verified internally via HMAC signature over the raw body
                        //       (see RushPayController.verifySignature). Signature auth is why
                        //       this path is permitAll: RushPay's servers have no JWT.
                        .requestMatchers(HttpMethod.GET,  "/api/tip/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()

                        // ── Booking ───────────────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST, "/api/booking/redeem").permitAll()

                        // ── WebSocket & health ────────────────────────────────────────────
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()

                        // ── Moolre — deposit flow (authenticated) ─────────────────────────
                        // POST /api/wallet/deposit/moolre/init   — initiates USSD direct charge
                        // POST /api/wallet/deposit/moolre/verify — polls / verifies payment
                        .requestMatchers(HttpMethod.POST,
                                "/api/wallet/deposit/moolre/init",
                                "/api/wallet/deposit/moolre/verify"
                        ).authenticated()

                        // ── Moolre — admin upgrade flow (authenticated) ───────────────────
                        // POST /api/user/upgrade-to-admin/moolre/init — pays GHS 200 upgrade fee
                        .requestMatchers(HttpMethod.POST,
                                "/api/user/upgrade-to-admin/moolre/init"
                        ).authenticated()
                        // ── Moolre — USSD deposit flow (authenticated) ────────────────────
                        // POST /api/wallet/deposit/moolre/ussd/init   — initiates USSD direct charge
                        // POST /api/wallet/deposit/moolre/ussd/otp    — submits SMS/OTP code
                        // POST /api/wallet/deposit/moolre/ussd/verify — polls / verifies payment
                        .requestMatchers(HttpMethod.POST,
                                "/api/wallet/deposit/moolre/ussd/init",
                                "/api/wallet/deposit/moolre/ussd/otp",
                                "/api/wallet/deposit/moolre/ussd/verify"
                        ).authenticated()

                        // ── Moolre — Payment Link deposit flow (authenticated) ────────────
                        // POST /api/wallet/deposit/moolre/init   — generates hosted checkout link
                        // POST /api/wallet/deposit/moolre/verify — polls / verifies payment
                        .requestMatchers(HttpMethod.POST,
                                "/api/wallet/deposit/moolre/init",
                                "/api/wallet/deposit/moolre/verify"
                        ).authenticated()

                        // ── Moolre — admin upgrade flows (authenticated) ──────────────────
                        .requestMatchers(HttpMethod.POST,
                                "/api/user/upgrade-to-admin/moolre/init",
                                "/api/user/upgrade-to-admin/moolre/ussd/init"
                        ).authenticated()

                        // ── RushPay — deposit + admin upgrade init (authenticated) ────────
                        // These mint a checkout + short-lived widget session under the
                        // logged-in user; the browser then drives MoMo/card/gift-card funding
                        // with the widget token. The webhook (POST /api/webhooks/rushpay) is
                        // covered by the /api/webhooks/** permitAll rule above and is
                        // authenticated by HMAC signature, not JWT.
                        // POST /api/wallet/deposit/rushpay/init          — creates deposit checkout
                        // POST /api/user/upgrade-to-admin/rushpay/init   — pays GHS 200 upgrade fee
                        .requestMatchers(HttpMethod.POST,
                                "/api/wallet/deposit/rushpay/init",
                                "/api/user/upgrade-to-admin/rushpay/init"
                        ).authenticated()

                        // ── Admin & super-admin ───────────────────────────────────────────
                        .requestMatchers((superAdminPath + "/**")).hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/super-admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                                // ── NALOPAY — deposit + admin upgrade init (authenticated) ────────
// MoMo hits NALOPAY /clientapi/collection/ (approval prompt on handset);
// card/bank mint a hosted checkout session and return checkout_url.
// The callback (POST /api/webhooks/nalopay/{token}) is covered by the
// /api/webhooks/** permitAll rule above. NALOPAY does NOT sign callbacks —
// unlike RushPay's HMAC — so identity is proven by a shared secret in the
// URL path, compared in constant time (NaloPayController.callback).
                                .requestMatchers(HttpMethod.POST,
                                        "/api/wallet/deposit/nalopay-momo/init",
                                        "/api/wallet/deposit/nalopay-card/init",
                                        "/api/wallet/deposit/nalopay-bank/init",
                                        "/api/user/upgrade-to-admin/nalopay-momo/init",
                                        "/api/user/upgrade-to-admin/nalopay-card/init"
                                ).authenticated()

                                .requestMatchers(HttpMethod.GET,
                                        "/api/wallet/deposit/nalopay/verify/**"
                                ).authenticated()
                        // ── Everything else requires auth ─────────────────────────────────
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
        hierarchy.setHierarchy("ROLE_SUPER_ADMIN > ROLE_ADMIN\nROLE_ADMIN > ROLE_USER");
        return hierarchy;
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();

        var origins = new java.util.ArrayList<>(List.of(
                "http://localhost:5173",
                "http://localhost:5500",
                "http://localhost:4173",
                "http://localhost:3000",
                "http://localhost:8081",

                "https://speedbet.site",
                "https://www.speedbet.site",

                "https://futball-gamma.vercel.app",
                "https://futballadmin.vercel.app",

                "https://poikiloblastic-leeanne-gazeless.ngrok-free.dev",

                "https://www.futball.site",

                "https://superbet.vercel.app",

                // ── nxtbet ────────────────────────────────────────────
                "https://nxtbet.site",
                "https://www.nxtbet.site",
                "https://nxtbetadmin.vercel.app",

                // ── bett75 ────────────────────────────────────────────
                "https://bett75.com",
                "https://www.bett75.com",
                "https://bet75.vercel.app",
                "https://bet75-ui-1.vercel.app",
                "https://bet75admin.vercel.app",

                // ── zynobet ───────────────────────────────────────────
                "https://zynobet.site",
                "https://www.zynobet.site",
                "https://oddsking-ui.vercel.app",
                "https://zynobetadmin.vercel.app",

                // ── bet (new) ─────────────────────────────────────────
                "https://bet-sooty-omega.vercel.app",
                "https://bet-ej8t5pu7e-cyril-dots-projects.vercel.app",

                // ── bbet360 ───────────────────────────────────────────
                "https://bbet360.site",
                "https://www.bbet360.site",
                "https://winningbet-chi.vercel.app",

                // ── bet360admin ───────────────────────────────────────
                "https://bet360admin.vercel.app",
                "https://www.winningbbet.site",
                "https://winningbetadmin.vercel.app",
                "https://championbet-jade.vercel.app",

                // ── zynobet ───────────────────────────────────────────
                "https://zynobet.site",
                "https://www.zynobet.site",
                "https://oddsking-ui.vercel.app",
                "https://zynobetadmin.vercel.app",

                // ── zynobett (new) ────────────────────────────────────
                "https://zynobett.site",
                "https://www.zynobett.site",
                "https://championbett.site",
                "https://www.championbett.site",
                "https://championbet-jade.vercel.app",
                "https://championbetadmin.vercel.app",
                "https://pulsebetui.vercel.app",
                "https://winningbetadmin.vercel.app",
                "https://nexbetadmin.vercel.app",

                // ── pulsebett ─────────────────────────────────────────
                "https://pulsebett.site",
                "https://www.pulsebett.site",
                "https://pulsebetui.vercel.app",
                "https://pulsebetadmin.vercel.app",

                // ── omegabett ─────────────────────────────────────────
                "https://omega-bet-gh.vercel.app",
                "https://omegabett.site",
                "https://www.omegabett.site",
                "https://omega-bet-admin.vercel.app",

                // ── africabett (NEW) ──────────────────────────────────
                "https://africabett.site",
                "https://www.africabett.site",
                "https://africa-bet-ten.vercel.app",
                "https://africabett-admin.vercel.app",

                "https://crownbet-superadmin.vercel.app",

                // ── betchamp ──────────────────────────────────────────
                "https://bet-champ.vercel.app",
                "https://betchamp.site",
                "https://www.betchamp.site",

                // ── nexbett ───────────────────────────────────────────
                "https://nexbett.xyz",
                "https://www.nexbett.xyz",
                "https://nex-bet-pink.vercel.app",
                "https://nex-bet-admin.vercel.app",

                // ── crownbett ─────────────────────────────────────────
                "https://crown-bet-xi.vercel.app",
                "https://crownbett.xyz",
                "https://www.crownbett.xyz",
                "https://betnova-seven.vercel.app",
                // ── africabet / africabett ──────────────────────────────────
                "https://africabet.site",
                "https://www.africabet.site",
                "https://africabett.site",
                "https://www.africabett.site",
                "https://africa-bet-ten.vercel.app",
                "https://africabet-admin.vercel.app",
                "https://africabett-admin.vercel.app",
                "https://nex-bet-pink.vercel.app",
                "https://www.bettnova.xyz",
                "https://betnovasuper-admin.vercel.app",
                "https://bet-nova-admin.vercel.app",
                "https://omega-subadmin.vercel.app",
                "https://www.bbetnova.xyz",
                "https://nxt-bet-admin-ui.vercel.app",
                "https://eaglebet-ui.vercel.app",
                "https://africabett.xyz",
                "https://www.africabett.xyz",
                "https://www.eaglebett.xyz",
                "https://eaglebet-subadmin.vercel.app",
                "https://eaglebet-superadmin.vercel.app",
                "https://nexbetbg-qua5xs9p.manus.space"

        ));

        if (frontendUrl != null && !frontendUrl.isBlank() && !origins.contains(frontendUrl)) {
            origins.add(frontendUrl);
        }

        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}