package com.speedbet.api.security;

import com.speedbet.api.common.MsportOrigin;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRole;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        var authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }

        var token = authHeader.substring(7);
        String username;

        try {
            username = jwtService.extractUsername(token);
        } catch (ExpiredJwtException e) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\": \"Token expired, please log in again\"}");
            return;
        } catch (JwtException e) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\": \"Invalid token\"}");
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            var userDetails = userDetailsService.loadUserByUsername(username);
            if (jwtService.isValid(token, userDetails)) {
                // MSport only: a player account flagged with a fake email is
                // logged out — every authenticated call from MSport answers
                // 401 EMAIL_INVALID and the refresh cookie is cleared, so
                // the frontend shows the update-email popup. Skipped for
                // /api/auth/** (login / refresh / the recovery endpoints
                // must stay reachable) and for staff (an admin must never
                // be locked out of the panel by a flag). Other skins never
                // match the MSport-origin check, so they pass through
                // untouched.
                if (userDetails instanceof User user
                        && user.isEmailInvalid()
                        && user.getRole() == UserRole.USER
                        && !req.getRequestURI().startsWith("/api/auth/")
                        && MsportOrigin.isMsport(req)) {
                    var cookie = new Cookie("refreshToken", "");
                    cookie.setHttpOnly(true);
                    cookie.setPath("/api/auth/refresh");
                    cookie.setMaxAge(0);
                    res.addCookie(cookie);
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"success\":false,\"message\":\"Your email address is invalid. Please update it to a valid email address.\",\"code\":\"EMAIL_INVALID\"}");
                    return;
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(req, res);
    }
}