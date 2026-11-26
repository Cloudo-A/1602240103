package com.adc.common;

import com.adc.auth.CurrentUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OperationLogService {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final ObjectMapper json;

    public OperationLogService(JdbcClient jdbc, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String targetType, long id, Object detail) {
        var user = (CurrentUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        jdbc.sql("""
            INSERT INTO operation_log (user_id,username,occurred_at,action,target_type,target_id,detail)
            VALUES (?,?,?,?,?,?,?)
            """).params(user.id(), user.username(), LocalDateTime.now(clock).withNano(0), action,
                    targetType.toUpperCase(Locale.ROOT), id,
                    json.writeValueAsString(detail)).update();
    }
}
