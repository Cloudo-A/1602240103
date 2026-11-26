package com.adc.operationlog;

import com.adc.common.BusinessException;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Service
@Transactional(readOnly = true)
class OperationLogQueryService {
    record Entry(long id, @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime occurredAt,
                 long userId, String username, String displayName, String action, String targetType,
                 Long targetId, Object detail) {}
    record Page(List<Entry> items, long total) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ObjectReader detailReader;

    OperationLogQueryService(JdbcClient jdbc, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.detailReader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    Page list(LocalDate from, LocalDate to, Long userId, String action, String targetType,
              String keyword, int page, int size) {
        if (page < 0) throw BusinessException.invalid("page", "页码不能小于0");
        if (size < 1 || size > 100) throw BusinessException.invalid("size", "每页条数应为1至100");
        if (from == null && to == null) {
            to = LocalDate.now(clock);
            from = to.minusDays(6);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.invalid("from", "开始日期不能晚于结束日期");
        }

        var filter = new StringBuilder(" FROM operation_log l LEFT JOIN app_user u ON u.id = l.user_id WHERE 1=1");
        var params = new HashMap<String, Object>();
        if (from != null) {
            filter.append(" AND l.occurred_at >= :start");
            params.put("start", from.atStartOfDay());
        }
        if (to != null) {
            filter.append(" AND l.occurred_at <= :end");
            params.put("end", to.atTime(23, 59, 59));
        }
        if (userId != null) {
            filter.append(" AND l.user_id = :userId");
            params.put("userId", userId);
        }
        if (!action.isBlank()) {
            filter.append(" AND l.action = CAST(:action AS BINARY)");
            params.put("action", action);
        }
        if (!targetType.isBlank()) {
            filter.append(" AND UPPER(l.target_type) = :targetType");
            params.put("targetType", targetType.toUpperCase(Locale.ROOT));
        }
        if (!keyword.isBlank()) {
            filter.append("""
                 AND (LOCATE(:keyword, l.username) > 0 OR LOCATE(:keyword, u.display_name) > 0
                      OR LOCATE(:keyword, CAST(l.target_id AS CHAR)) > 0)
                """);
            params.put("keyword", keyword.strip());
        }
        long total = jdbc.sql("SELECT COUNT(*)" + filter).params(params).query(Long.class).single();
        params.put("size", size);
        params.put("offset", (long) page * size);
        String columns = """
            SELECT l.id, l.occurred_at, l.user_id, l.username, u.display_name, l.action,
                   UPPER(l.target_type) AS target_type, l.target_id, l.detail
            """;
        var items = jdbc.sql(columns + filter + " ORDER BY l.occurred_at DESC, l.id DESC LIMIT :size OFFSET :offset")
            .params(params).query((rs, row) -> new Entry(rs.getLong("id"), rs.getObject("occurred_at", LocalDateTime.class),
                rs.getLong("user_id"), rs.getString("username"), rs.getString("display_name"), rs.getString("action"),
                rs.getString("target_type"), rs.getObject("target_id", Long.class), detail(rs.getString("detail")))).list();
        return new Page(items, total);
    }

    private Object detail(String text) {
        if (text == null || text.isBlank()) return text;
        try {
            return detailReader.readTree(text);
        } catch (JacksonException e) {
            return text;
        }
    }
}
