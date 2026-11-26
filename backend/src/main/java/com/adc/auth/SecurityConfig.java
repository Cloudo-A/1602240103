package com.adc.auth;

import java.io.IOException;
import com.adc.common.*;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import java.time.Clock;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import java.time.ZoneId;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    ValidationConfigurationCustomizer validationClock(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService service, PatientAuthService patientAuth, ObjectMapper json,
                                RequestRecordService records, ApiExceptionHandler errors) throws Exception {
        return http
            .cors(cors -> cors.configurationSource(corsSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/clinics", "/api/public/dentists",
                    "/api/public/treatment-items").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/patient-auth/sms-code", "/api/patient-auth/register",
                    "/api/patient-auth/login", "/api/patient-auth/reset-password").permitAll()
                .requestMatchers("/api/patient/**", "/api/patient-auth/me", "/api/patient-auth/logout").hasRole("PATIENT")
                .requestMatchers("/api/appointment-requests/**").hasRole("FRONT_DESK")
                .requestMatchers("/api/clinics","/api/clinics/{id}","/api/dentists","/api/dentists/{id}",
                    "/api/qualifications","/api/treatment-items","/api/treatment-items/{id}",
                    "/api/treatment-items/{id}/prices").hasRole("HQ")
                .requestMatchers(HttpMethod.GET, "/api/patients", "/api/patients/{id}").hasAnyRole("DENTIST","FRONT_DESK")
                .requestMatchers("/api/patients", "/api/patients/{id}").hasRole("FRONT_DESK")
                .requestMatchers(HttpMethod.GET, "/api/patients/{id}/medical-record").hasRole("DENTIST")
                .requestMatchers(HttpMethod.POST, "/api/appointments", "/api/appointments/check",
                    "/api/appointments/{id}/cancel", "/api/appointments/{id}/arrive").hasRole("FRONT_DESK")
                .requestMatchers(HttpMethod.POST, "/api/visits/{id}/price-lines", "/api/visits/{id}/confirm",
                    "/api/visits/{id}/end-without-treatment").hasRole("DENTIST")
                .requestMatchers(HttpMethod.POST, "/api/visits/{id}/settlement-preview",
                    "/api/visits/{id}/settlement").hasRole("FRONT_DESK")
                .requestMatchers(HttpMethod.GET, "/api/settlements", "/api/settlements/{settlementNo}").hasRole("FRONT_DESK")
                .requestMatchers("/api/stats/**", "/api/quarters/**").hasRole("HQ")
                .requestMatchers("/api/users", "/api/users/{id}", "/api/users/{id}/reset-password",
                    "/api/operation-logs").hasRole("HQ")
                .requestMatchers("/api/**").hasAnyRole("FRONT_DESK", "DENTIST", "HQ")
                .anyRequest().permitAll())
            .exceptionHandling(handlers -> handlers
                .authenticationEntryPoint((req, res, ex) ->
                    write(json, res, 401, ApiError.unauthorized("请登录，或重新获取有效令牌")))
                .accessDeniedHandler((req, res, ex) ->
                    write(json, res, 403, new ApiError("FORBIDDEN", "岗位无权办理", null, null))))
            .addFilterBefore(new BearerFilter(service, patientAuth), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new RequestIdFilter(records, json, errors), AuthorizationFilter.class)
            .build();
    }

    private static CorsConfigurationSource corsSource() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("*"));
        cors.setAllowedMethods(List.of("GET","POST","PUT","DELETE","PATCH","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type","X-Request-Id"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static void write(ObjectMapper json, HttpServletResponse response, int status, ApiError error)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), error);
    }
}
