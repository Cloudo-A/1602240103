package com.adc.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patient-auth")
class PatientAuthController {
    private final PatientAuthService patients;

    PatientAuthController(PatientAuthService patients) { this.patients = patients; }

    record Sms(String phone, @NotNull(message="请选择验证码用途")
               @Pattern(regexp="REGISTER|RESET_PASSWORD",message="验证码用途不正确") String purpose) {}
    record Register(String phone, String code, String name, String password) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    record Login(String phone, String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    record Reset(String phone, String code, String newPassword) {
        @Override public String toString() { return "Reset[redacted]"; }
    }
    record SmsSent(boolean sent, String message) {}
    record Done(boolean ok) {}

    @PostMapping("/sms-code")
    SmsSent sms(@Valid @RequestBody Sms input) { return patients.sms(input); }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    PatientAuthService.Session register(@RequestBody Register input) { return patients.register(input); }

    @PostMapping("/login")
    PatientAuthService.Session login(@RequestBody Login input) { return patients.login(input); }

    @PostMapping("/reset-password")
    Done reset(@RequestBody Reset input) { return patients.reset(input); }

    @GetMapping("/me")
    PatientPrincipal me(@AuthenticationPrincipal PatientPrincipal patient) { return patient; }

    @PostMapping("/logout")
    void logout(@AuthenticationPrincipal PatientPrincipal patient, HttpServletRequest request) {
        patients.logout(patient.id(), BearerFilter.token(request));
    }
}
