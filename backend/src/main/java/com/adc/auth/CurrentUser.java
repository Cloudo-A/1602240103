package com.adc.auth;

public record CurrentUser(long id, String username, String displayName, String role, Long dentistId) {}
