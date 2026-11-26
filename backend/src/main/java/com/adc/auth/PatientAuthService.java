package com.adc.auth;

import com.adc.common.BusinessException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.adc.auth.PatientAuthController.*;

@Service
class PatientAuthService {
    private final JdbcClient jdbc;
    private final PasswordCodec passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    PatientAuthService(JdbcClient jdbc, PasswordCodec passwords, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
    }

    record Session(String token, LocalDateTime expiresAt, PatientPrincipal patient) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    private record Credentials(PatientPrincipal patient, String hash) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    SmsSent sms(Sms input) {
        String phone = validPhone(input.phone());
        boolean registered = jdbc.sql("SELECT id FROM patient_account WHERE phone=?")
            .param(phone).query(Long.class).optional().isPresent();
        if ("REGISTER".equals(input.purpose()) && registered) throw registered();
        if ("RESET_PASSWORD".equals(input.purpose()) && !registered) throw unregistered();
        return new SmsSent(true, "课程演示：验证码统一为 123456");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Session register(Register input) {
        String phone = validPhone(input.phone());
        checkCode(input.code());
        String name = input.name() == null ? "" : input.name().strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 20) throw BusinessException.invalid("name", "姓名应为 1—20 个字符");
        String hash = passwords.encode(input.password(), "password");
        var createdAt = now();
        var key = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO patient_account (phone,name,password_hash,created_at) VALUES (?,?,?,?)")
                .params(phone,name,hash,createdAt).update(key,"id");
        } catch (DuplicateKeyException duplicate) {
            throw registered();
        }
        var patient = new PatientPrincipal(Objects.requireNonNull(key.getKey()).longValue(),phone,name,createdAt);
        return issue(patient);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Session login(Login input) {
        var credentials = lookupForUpdate(validPhone(input.phone()));
        String hash = credentials.map(Credentials::hash).orElse(null);
        if (!passwords.matches(input.password(),hash)) throw new BadCredentialsException("手机号或密码不正确");
        return issue(credentials.orElseThrow().patient());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Done reset(Reset input) {
        String phone = validPhone(input.phone());
        checkCode(input.code());
        var account = lookupForUpdate(phone).orElseThrow(PatientAuthService::unregistered);
        String hash = passwords.encode(input.newPassword(),"newPassword");
        long id = account.patient().id();
        jdbc.sql("UPDATE patient_account SET password_hash=? WHERE id=?").params(hash,id).update();
        jdbc.sql("DELETE FROM patient_auth_token WHERE patient_account_id=?").param(id).update();
        return new Done(true);
    }

    Optional<PatientPrincipal> authenticate(String token) {
        return jdbc.sql("""
            SELECT p.id,p.phone,p.name,p.created_at FROM patient_auth_token t
            JOIN patient_account p ON p.id=t.patient_account_id WHERE t.token_hash=? AND t.expires_at>?
            """).params(AuthService.digest(token),LocalDateTime.now(clock)).query(PatientPrincipal.class).optional();
    }

    @Transactional
    void logout(long patientId, String token) {
        jdbc.sql("SELECT id FROM patient_account WHERE id=? FOR UPDATE").param(patientId).query(Long.class).single();
        int removed = jdbc.sql("DELETE FROM patient_auth_token WHERE patient_account_id=? AND token_hash=?")
            .params(patientId,AuthService.digest(token)).update();
        if (removed == 0) throw new BadCredentialsException("令牌无效或已过期");
    }

    private Optional<Credentials> lookupForUpdate(String phone) {
        return jdbc.sql("SELECT id,phone,name,created_at,password_hash FROM patient_account WHERE phone=? FOR UPDATE")
            .param(phone).query((r,n) -> new Credentials(new PatientPrincipal(r.getLong("id"),r.getString("phone"),
                r.getString("name"),r.getObject("created_at",LocalDateTime.class)),r.getString("password_hash"))).optional();
    }

    private Session issue(PatientPrincipal patient) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "pt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = now();
        var expires = now.plusDays(7);
        jdbc.sql("DELETE FROM patient_auth_token WHERE patient_account_id=? AND expires_at<=?").params(patient.id(),now).update();
        jdbc.sql("INSERT INTO patient_auth_token(token_hash,patient_account_id,expires_at) VALUES(?,?,?)")
            .params(AuthService.digest(token),patient.id(),expires).update();
        return new Session(token,expires,patient);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }

    private static String validPhone(String phone) {
        if (phone == null || !phone.matches("1[0-9]{10}"))
            throw BusinessException.invalid("phone","请输入以 1 开头的 11 位手机号");
        return phone;
    }

    private static void checkCode(String code) {
        if (!"123456".equals(code)) throw BusinessException.invalid("code","验证码不正确");
    }

    private static BusinessException registered() { return BusinessException.conflict("该手机号已注册，请直接登录"); }
    private static BusinessException unregistered() { return BusinessException.notFound("该手机号尚未注册"); }
}
