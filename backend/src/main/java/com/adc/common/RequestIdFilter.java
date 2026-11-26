package com.adc.common;

import com.adc.auth.CurrentUser;
import com.adc.auth.PatientPrincipal;
import com.adc.common.RequestRecordService.AccountType;
import com.adc.common.RequestRecordService.Owner;
import com.adc.demo.DemoProgress;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public class RequestIdFilter extends OncePerRequestFilter {
    private final RequestRecordService records;
    private final ObjectMapper json;
    private final ApiExceptionHandler errors;

    public RequestIdFilter(RequestRecordService records, ObjectMapper json, ApiExceptionHandler errors) {
        this.records = records;
        this.json = json;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        var path = path(req);
        return req.getHeader("X-Request-Id") == null || !Set.of("POST","PUT").contains(req.getMethod())
            || !path.startsWith("/api/") || path.equals("/api/auth/login") || path.equals("/api/auth/logout")
            || path.startsWith("/api/patient-auth/")
            || Set.of("check","price-lines","settlement-preview","preview").contains(path.substring(path.lastIndexOf('/')+1));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException {
        try {
            String key = req.getHeader("X-Request-Id");
            if (key.isBlank() || !key.equals(key.strip()) || key.length() > 64)
                throw BusinessException.invalid("X-Request-Id", "请求标识应为 1 至 64 个字符");
            if (key.startsWith(DemoProgress.KEY_PREFIX))
                throw BusinessException.invalid("X-Request-Id", "请使用有效的请求标识");
            byte[] bytes = req.getInputStream().readAllBytes();
            String action = req.getMethod();
            var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            var owner = principal instanceof PatientPrincipal patient
                ? new Owner(AccountType.PATIENT, patient.id())
                : new Owner(AccountType.EMPLOYEE, ((CurrentUser) principal).id());
            var hash = MessageDigest.getInstance("SHA-256");
            String target = action + " " + req.getRequestURI() + "?" + Objects.toString(req.getQueryString(),"");
            hash.update(target.getBytes(StandardCharsets.UTF_8));
            hash.update((byte)0);
            byte[] content = bytes;
            try {
                var tree = json.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(bytes);
                if (tree != null && !tree.isMissingNode()) content = json.writer()
                    .with(tools.jackson.databind.cfg.JsonNodeFeature.WRITE_PROPERTIES_SORTED)
                    .writeValueAsBytes(tree);
            } catch (JacksonException ignored) { /* Preserve invalid JSON verbatim for error replay. */ }
            String digest = HexFormat.of().formatHex(hash.digest(content));
            var result = records.execute(key, owner, action, digest, isCredentialWrite(req), () -> {
                var capture = new ContentCachingResponseWrapper(res);
                try {
                    chain.doFilter(new BodyRequest(req,bytes), capture);
                } catch (IOException | ServletException ex) {
                    throw new IllegalStateException(ex);
                }
                return new RequestRecordService.Result(capture.getStatus(), capture.getContentType(),
                    capture.getContentAsByteArray());
            });
            res.setStatus(result.status());
            res.setContentType(result.type());
            res.setContentLength(result.body().length);
            res.getOutputStream().write(result.body());
        } catch (Exception ex) {
            var error = ex instanceof BusinessException business ? errors.business(business) : errors.other(ex);
            res.resetBuffer();
            res.setStatus(error.getStatusCode().value());
            res.setContentType("application/json;charset=UTF-8");
            json.writeValue(res.getOutputStream(), error.getBody());
        }
    }

    private static boolean isCredentialWrite(HttpServletRequest request) {
        String path = path(request);
        return path.equals("/api/auth/change-password") || path.equals("/api/users")
            || path.matches("/api/users/[^/]+/reset-password");
    }

    private static String path(HttpServletRequest request) {
        return URI.create(request.getRequestURI().substring(request.getContextPath().length())).getPath();
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        BodyRequest(HttpServletRequest req, byte[] bytes) {
            super(req);
            this.bytes = bytes;
        }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                public int read() { return input.read(); }
                public int read(byte[] b, int off, int len) { return input.read(b,off,len); }
                public boolean isReady() { return true; }
                public boolean isFinished() { return input.available() == 0; }
                public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
