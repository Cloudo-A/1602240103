
package com.adc.demo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("demo")
public class DemoProgress {
    public static final String KEY_PREFIX = "demo-rolling:";
    private static final String OWNER = "account_type='EMPLOYEE' AND user_id=0 AND action='DEMO_ROLLING'";
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    DemoProgress(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Entry(String key, LocalDateTime at, JsonNode data) {}

    Optional<Entry> get(String key) {
        return jdbc.sql("SELECT request_id,created_at,response_body FROM request_record WHERE " + OWNER + " AND request_id=?")
            .param(KEY_PREFIX + key).query((r,n) -> new Entry(key, r.getObject(2, LocalDateTime.class),
                json.readTree(r.getBytes(3)))).optional();
    }

    List<Entry> due(String prefix, LocalDateTime before) {
        return jdbc.sql("""
            SELECT request_id,created_at,response_body FROM request_record
            WHERE
            """ + OWNER + " AND request_id LIKE ? AND created_at<? ORDER BY created_at,request_id")
            .params(KEY_PREFIX + prefix + "%", before).query((r,n) -> new Entry(
                r.getString(1).substring(KEY_PREFIX.length()), r.getObject(2, LocalDateTime.class),
                json.readTree(r.getBytes(3)))).list();
    }

    void put(String key, LocalDateTime at, Object data) {
        jdbc.sql("""
            INSERT INTO request_record(account_type,request_id,user_id,action,content_hash,created_at,response_body)
            VALUES ('EMPLOYEE',?,0,'DEMO_ROLLING','',?,?)
            """).params(KEY_PREFIX + key, at, json.writeValueAsBytes(data)).update();
    }

    void remove(String key) {
        jdbc.sql("DELETE FROM request_record WHERE " + OWNER + " AND request_id=?")
            .param(KEY_PREFIX + key).update();
    }

    static List<Long> ids(JsonNode node) {
        return node.values().stream().map(JsonNode::asLong).toList();
    }
}
