package com.adc.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
class AuthService {
    private final AppUsers users;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final PasswordCodec passwords;

    AuthService(AppUsers users, JdbcClient jdbc, Clock clock, PasswordCodec passwords) {
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
        this.passwords = passwords;
    }

    record LoginResult(String token, LocalDateTime expiresAt, CurrentUser user) {
        @Override public String toString() { return "LoginResult[redacted]"; }
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public LoginResult login(String username, String password) {
        var account = users.findByUsername(username);
        if (!passwords.matches(password, account.map(user -> user.passwordHash).orElse(null))) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        var user = account.get();
        if (!user.active) throw new BadCredentialsException("账号已停用，请联系总部");

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = LocalDateTime.now(clock).withNano(0);
        var expiresAt = now.plusHours(12);
        jdbc.sql("DELETE FROM auth_token WHERE user_id=? AND expires_at<=?").params(user.id, now).update();
        jdbc.sql("INSERT INTO auth_token (token_hash,user_id,expires_at) VALUES (?,?,?)")
            .params(digest(token), user.id, expiresAt).update();
        return new LoginResult(token, expiresAt, user.currentUser());
    }

    Optional<CurrentUser> authenticate(String token) {
        return users.findAuthenticated(digest(token), LocalDateTime.now(clock)).map(AppUser::currentUser);
    }

    @Transactional
    boolean revoke(long userId, String token) {
        jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(userId).query(Long.class).single();
        return jdbc.sql("DELETE FROM auth_token WHERE user_id=? AND token_hash=?").params(userId, digest(token)).update() == 1;
    }

    void requireCurrentToken(long userId, String token) {
        if (jdbc.sql("SELECT user_id FROM auth_token WHERE user_id=? AND token_hash=? AND expires_at>? FOR UPDATE")
            .params(userId, digest(token), LocalDateTime.now(clock)).query(Long.class).optional().isEmpty()) {
            throw new BadCredentialsException("令牌无效或已过期");
        }
    }

    void revokeAll(long userId) {
        jdbc.sql("DELETE FROM auth_token WHERE user_id=?").param(userId).update();
    }

    void revokeOthers(long userId, String currentToken) {
        jdbc.sql("DELETE FROM auth_token WHERE user_id=? AND token_hash<>?").params(userId, digest(currentToken)).update();
    }

    static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
