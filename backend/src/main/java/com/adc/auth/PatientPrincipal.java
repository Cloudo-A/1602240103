package com.adc.auth;

import java.time.LocalDateTime;

public record PatientPrincipal(long id, String phone, String name, LocalDateTime createdAt) {}
