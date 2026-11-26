package com.adc.auth;

import com.adc.common.BusinessException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class PasswordCodec {
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final String dummyHash = bcrypt.encode("unused");

    boolean matches(String supplied, String hash) {
        if (supplied == null || !StandardCharsets.UTF_8.newEncoder().canEncode(supplied)
                || supplied.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        return bcrypt.matches(supplied, hash == null ? dummyHash : hash) && hash != null;
    }

    String encode(String password, String field) {
        if (password == null) throw BusinessException.invalid(field, "请填写密码");
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(password))
            throw BusinessException.invalid(field, "密码包含无效字符");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw BusinessException.invalid(field, "密码太长（最多 72 个字节，约 24 个汉字）");
        int characters = password.codePointCount(0, password.length());
        if (characters < 6 || characters > 64) throw BusinessException.invalid(field, "密码应为 6—64 个字符");
        return bcrypt.encode(password);
    }
}
