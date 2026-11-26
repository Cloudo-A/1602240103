package com.adc.common;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;
import tools.jackson.databind.exc.MismatchedInputException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException ex) {
        return ResponseEntity.status(ex.status).body(ex.error);
    }
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiError> credentials(BadCredentialsException ex) {
        return ResponseEntity.status(401).body(ApiError.unauthorized(ex.getMessage()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied() {
        return ResponseEntity.status(403).body(new ApiError("FORBIDDEN", "岗位无权办理", null, null));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> fields(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var issues = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiError.FieldIssue(f.getField(), f.getDefaultMessage())).toList();
        var message = request.getRequestURI().endsWith("/auth/login") ? "请检查登录信息" : "请检查输入内容";
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", message, issues, null));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> parameter(MethodArgumentTypeMismatchException ex) {
        return business(BusinessException.invalid(ex.getName(), "参数格式错误"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> body(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            var path = new StringBuilder();
            for (var part : mismatch.getPath()) {
                if (part.getPropertyName() == null) path.append("[").append(part.getIndex()).append("]");
                else {
                    if (!path.isEmpty()) path.append(".");
                    path.append(part.getPropertyName());
                }
            }
            return business(BusinessException.invalid(path.toString(), "字段格式错误"));
        }
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "请求体必须是有效的 JSON 对象", null, null));
    }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiError> missing() {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError("NOT_FOUND", "请求路径不存在", null, null));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return business(BusinessException.conflict("记录已存在，请检查重复内容"));
            }
        }
        if (ex instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(error.getStatusCode())
                .body(new ApiError("INVALID_INPUT", "请求方法或格式不正确", null, null));
        }
        log.error("处理 API 请求失败", ex);
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL", "系统错误，请稍后重试", null, null));
    }
}
