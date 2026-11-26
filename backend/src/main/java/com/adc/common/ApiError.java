package com.adc.common;

import java.util.List;

public record ApiError(String code, String message, List<FieldIssue> fieldErrors, Object current) {
    public record FieldIssue(String field, String message) {}
    public static ApiError unauthorized(String message) {
        return new ApiError("UNAUTHENTICATED", message, null, null);
    }
}
