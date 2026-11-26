package com.adc.common;

import java.util.List;

public class BusinessException extends RuntimeException {
    final int status;
    final ApiError error;

    private BusinessException(int status, String code, String message, List<ApiError.FieldIssue> fields, Object current) {
        super(message);
        this.status = status;
        this.error = new ApiError(code, message, fields, current);
    }

    public static BusinessException invalid(String field, String message) {
        return new BusinessException(400, "INVALID_INPUT", message, List.of(new ApiError.FieldIssue(field, message)), null);
    }
    public static BusinessException invalid(List<ApiError.FieldIssue> fields) {
        return new BusinessException(400, "INVALID_INPUT", "请检查输入内容", List.copyOf(fields), null);
    }
    public static BusinessException notFound() {
        return notFound("记录不存在");
    }
    public static BusinessException notFound(String message) {
        return new BusinessException(404, "NOT_FOUND", message, null, null);
    }
    public static BusinessException conflict(String message) {
        return conflict(message, null);
    }
    public static BusinessException conflict(String message, Object candidates) {
        return new BusinessException(409, "CONFLICT", message, null, candidates);
    }
    public static BusinessException changed(Object current) {
        return new BusinessException(409, "CHANGED", "记录已被修改，请重新核对", null, current);
    }
    public static BusinessException stateNotAllowed(String message) {
        return new BusinessException(409, "STATE_NOT_ALLOWED", message, null, null);
    }
    static BusinessException duplicate() {
        return new BusinessException(409, "DUPLICATE_REQUEST", "同一请求标识已用于其他内容", null, null);
    }
}
