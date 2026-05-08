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
                        // Swagger / OpenAPI
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/api/geo/**"
                        ).permitAll()
                        // Auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        // Fully public match endpoints (lobby, live, today, upcoming, etc.)
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        // Unauthenticated match endpoints
                        .requestMatchers(HttpMethod.GET, "/api/matches/live").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/upcoming").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/future").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/featured").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/matches/search").permitAll()
                        // Tips & webhooks
                        .requestMatchers(HttpMethod.GET, "/api/tip/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                        // Booking
                        .requestMatchers(HttpMethod.POST, "/api/booking/redeem").permitAll()
                        // WebSocket & health
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Demo login
                        .requestMatchers(HttpMethod.POST, "/api/auth/demo-login").permitAll()
                        // Admin & super-admin
                        .requestMatchers((superAdminPath + "/**")).hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/super-admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
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

        // Collect all allowed origins: the env-configured URL + hardcoded dev/prod domains.
        // To add more origins, update APP_PLATFORM_FRONTEND_URL in your Railway env vars
        // or append to the list below.
        var origins = new java.util.ArrayList<>(List.of(
                "http://localhost:5173",
                "http://localhost:5500",
                "http://localhost:4173",
                "http://localhost:3000",
                "http://localhost:8081",
                "https://speedbet.site",
                "https://www.speedbet.site",
                "https://futball-gamma.vercel.app",
                "https://poikiloblastic-leeanne-gazeless.ngrok-free.dev"
        ));
        
        // Also include whatever is set in the env var (avoids duplicates gracefully)
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