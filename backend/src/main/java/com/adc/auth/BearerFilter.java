package com.adc.auth;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class BearerFilter extends OncePerRequestFilter {
    private final AuthService service;
    private final PatientAuthService patients;

    BearerFilter(AuthService service, PatientAuthService patients) {
        this.service = service;
        this.patients = patients;
    }

    static String token(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
            ? header.substring(7) : "";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = token(request);
        if (token.length() == 43) {
            service.authenticate(token).ifPresent(user -> authenticate(user, user.role()));
        } else if (token.length() == 46 && token.startsWith("pt_")) {
            patients.authenticate(token).ifPresent(patient -> authenticate(patient, "PATIENT"));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(Object principal, String role) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(context);
    }
}
