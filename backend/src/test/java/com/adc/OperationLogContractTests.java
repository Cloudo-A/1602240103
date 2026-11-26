package com.adc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

class OperationLogContractTests extends ContractHttp {
    private static final String LOGS = "/api/operation-logs";
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @Test void pagesLegacyRowsByBeijingDayAndReadsCurrentDisplayName() throws Exception {
        String hq = token("hq1");
        // The numeric keyword below should match the target ID only.
        String name = "audit" + UUID.randomUUID().toString().replace("-", "").replaceAll("[0-9]", "x");
        jdbc.sql("""
            INSERT INTO app_user(username,password_hash,display_name,role,active)
            SELECT ?,password_hash,'原显示名','FRONT_DESK',false FROM app_user WHERE username='hq1'
            """).param(name).update();
        long actor = jdbc.sql("SELECT id FROM app_user WHERE username=?").param(name).query(Long.class).single();
        String byUser = LOGS + "?userId=" + actor;
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.minusDays(6);
        try {
            insert(actor, name, start.minusDays(1).atTime(23,59,59), "UPDATE", "appointment", 20, "old");
            insert(actor, name, start.atStartOfDay(), "UPDATE", "appointment", 21, "{\"ok\":true}");
            insert(actor, name, today.atTime(23,59,59), "CREATE", "PATIENT", 54321, "[1,false]");
            insert(actor, name, today.atTime(23,59,59), "UPDATE", "AppOintment", 22, "{broken");
            insert(actor, name, today.plusDays(1).atStartOfDay(), "UPDATE", "appointment", 23, "42");
            jdbc.sql("UPDATE app_user SET display_name='日志%_员' WHERE id=?").param(actor).update();

            var first = api("GET", byUser + "&size=2", hq, null, 200);
            assertThat(first.propertyNames()).containsExactlyInAnyOrder("items", "total");
            assertThat(first.get("total").asInt()).isEqualTo(3);
            var latest = first.get("items").get(0);
            assertThat(latest.propertyNames()).containsExactlyInAnyOrder("id","occurredAt","userId",
                "username","displayName","action","targetType","targetId","detail");
            assertThat(latest.get("userId").asLong()).isEqualTo(actor);
            assertThat(latest.get("username").asString()).isEqualTo(name);
            assertThat(latest.get("displayName").asString()).isEqualTo("日志%_员");
            assertThat(latest.get("targetType").asString()).isEqualTo("APPOINTMENT");
            assertThat(latest.get("detail").asString()).isEqualTo("{broken");
            assertThat(latest.get("targetId").asLong()).isEqualTo(22);
            assertThat(latest.get("occurredAt").asString()).isEqualTo(today + "T23:59:59");
            assertThat(first.get("items").get(1).get("detail").isArray()).isTrue();
            var second = api("GET", byUser + "&page=1&size=2", hq, null, 200);
            assertThat(second.get("items").size()).isOne();
            assertThat(second.get("items").get(0).get("detail").get("ok").asBoolean()).isTrue();
            assertThat(api("GET", byUser + "&page=2147483647&size=100", hq, null, 200).get("items").size()).isZero();

            var filter = api("GET", byUser + "&targetType=aPpoIntMent&action=UPDATE&keyword=" + name, hq, null, 200);
            assertThat(filter.get("total").asInt()).isEqualTo(2);
            assertThat(api("GET", byUser + "&action=update", hq, null, 200).get("total").asInt()).isZero();
            assertThat(api("GET", byUser + "&keyword=432", hq, null, 200).get("items").get(0)
                .get("targetId").asLong()).isEqualTo(54321);
            String queryText = URLEncoder.encode("日志%_", StandardCharsets.UTF_8);
            assertThat(api("GET", byUser + "&keyword=" + queryText, hq, null, 200).get("total").asInt()).isEqualTo(3);
            assertThat(api("GET", byUser + "&keyword=%25%25", hq, null, 200).get("total").asInt()).isZero();
            assertThat(api("GET", byUser + "&to=" + start, hq, null, 200).get("total").asInt()).isEqualTo(2);
            var future = api("GET", byUser + "&from=" + today.plusDays(1), hq, null, 200);
            assertThat(future.get("items").get(0).get("detail").asInt()).isEqualTo(42);
        } finally {
            jdbc.sql("DELETE FROM operation_log WHERE user_id=?").param(actor).update();
            jdbc.sql("DELETE FROM app_user WHERE id=?").param(actor).update();
        }
    }

    private void insert(long actor, String username, LocalDateTime date, String action,
                        String targetType, long targetId, String detail) {
        jdbc.sql("""
            INSERT INTO operation_log(user_id,username,occurred_at,action,target_type,target_id,detail)
            VALUES (?,?,?,?,?,?,?)
            """).params(actor, username, date, action, targetType, targetId, detail).update();
    }

    @Test void restrictsRolesAndRejectsInvalidPagingOrRange() throws Exception {
        for (String user : new String[]{"front1", "dentist1"}) {
            assertThat(api("GET", LOGS, token(user), null, 403).get("code").asString()).isEqualTo("FORBIDDEN");
        }
        String hq = token("hq1");
        for (String query : new String[]{"page=-1", "size=0", "size=101", "from=2026-11-02&to=2026-11-01"}) {
            var error = api("GET", LOGS + "?" + query, hq, null, 400);
            assertThat(error.get("code").asString()).isEqualTo("INVALID_INPUT");
            assertThat(error.get("fieldErrors").get(0).get("field").asString()).isEqualTo(query.split("=")[0]);
        }
    }
}
