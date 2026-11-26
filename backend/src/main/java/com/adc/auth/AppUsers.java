package com.adc.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface AppUsers extends Repository<AppUser, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppUser> findByUsername(String username);

    @Query(value = """
        SELECT u.* FROM app_user u JOIN auth_token t ON t.user_id = u.id
        WHERE t.token_hash = :hash AND t.expires_at > :now AND u.active
        """, nativeQuery = true)
    Optional<AppUser> findAuthenticated(String hash, LocalDateTime now);
}
