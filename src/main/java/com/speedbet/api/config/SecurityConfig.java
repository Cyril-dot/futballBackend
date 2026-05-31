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

                        // ── Admin & super-admin ───────────────────────────────────────────
                        .requestMatchers((superAdminPath + "/**")).hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/super-admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

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
                // ── nxtbet ────────────────────────────────────────────────────────
                "https://nxtbet.site",
                "https://www.nxtbet.site",
                // ── bett75 ────────────────────────────────────────────────────────
                "https://bett75.com",
                "https://www.bett75.com",
                "https://bet75.vercel.app",
                "https://bet75-ui-1.vercel.app",
                "https://nxtbetadmin.vercel.app",
                "https://bet75admin.vercel.app",
                // ── zynobet ───────────────────────────────────────────────────────
                "https://zynobet.site",
                "https://www.zynobet.site",
                "https://oddsking-ui.vercel.app",
                "https://zynobetadmin.vercel.app",
                // ── bet (new) ─────────────────────────────────────────────────────
                "https://bet-sooty-omega.vercel.app",
                "https://bet-ej8t5pu7e-cyril-dots-projects.vercel.app"
        ));

        if (frontendUrl != null && !frontendUrl.isBlank() && !origins.contains(frontendUrl)) {
            origins.add(frontendUrl);
        }

        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
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