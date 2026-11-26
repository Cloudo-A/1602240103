
package com.adc;

import com.adc.demo.RollingDemoData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class RollingDemoDataTests extends ContractHttp {
    @Autowired JdbcClient jdbc;
    @Autowired RollingDemoData rolling;
    @Autowired ApplicationContext application;
    @Autowired DataSource dataSource;

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026,11,26,0,10);
    private static final String ROLLING_APPOINTMENTS = """
        FROM appointment a JOIN patient p ON p.id=a.patient_id
        LEFT JOIN visit v ON v.appointment_id=a.id LEFT JOIN settlement s ON s.visit_id=v.id
        WHERE p.account_no BETWEEN 'P000009' AND 'P000030'
        """;

    @Test void migrationRegistersTheDemoRosterAndBothDentistsCanSignIn() throws Exception {
        assertThat(jdbc.sql("""
            SELECT CONCAT(u.username,':',d.employee_no) FROM app_user u JOIN dentist d ON d.id=u.dentist_id
            WHERE u.username IN ('dentist3','dentist4') AND u.active ORDER BY u.username
            """).query(String.class).list()).containsExactly("dentist3:D003", "dentist4:D004");
        assertThat(token("dentist3")).isNotBlank();
        assertThat(token("dentist4")).isNotBlank();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM patient WHERE account_no BETWEEN 'P000009' AND 'P000030'")
            .query(Long.class).single()).isEqualTo(22);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM patient_account WHERE phone LIKE '1392600%'")
            .query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_record WHERE request_id='demo-rolling:roster' AND user_id=0")
            .query(Long.class).single()).isEqualTo(1);
        var error = api("POST", "/api/appointments", token("front1"), Map.of(), 400, "demo-rolling:roster");
        assertThat(error.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.get("fieldErrors").get(0).get("field").asString()).isEqualTo("X-Request-Id");
        assertThat(error.toString()).doesNotContain("demo-rolling:roster");
    }

    @Test @Transactional void fillsHistoryOnceWithCorrectAmountsActorsAndAnUnchangedVisitorBooking() throws Exception {
        jdbc.sql("""
            INSERT INTO appointment(patient_id,dentist_id,clinic_id,scheduled_at,version)
            SELECT patient_id,dentist_id,clinic_id,'2026-08-02 09:00:00',7
            FROM appointment WHERE scheduled_at='2026-07-03 09:00:00' LIMIT 1
            """).update();
        var before = visitor();
        rolling.runAt(AS_OF);
        var after = counts();
        rolling.runAt(AS_OF);
        assertThat(counts()).isEqualTo(after);
        assertThat(visitor()).isEqualTo(before);
        long total = after.get("bookings");
        assertThat(total).isBetween(860L,1150L);
        assertThat(after.get("visits") * 100.0 / total).isBetween(80.0,94.0);
        assertThat(after.get("bills") * 100.0 / total).isBetween(75.0,88.0);
        assertThat(after.get("cancelled") * 100.0 / total).isBetween(4.0,13.0);
        assertThat(count("SELECT COUNT(*) " + ROLLING_APPOINTMENTS + " AND v.no_treatment_reason IS NOT NULL") * 100.0 / total)
            .isBetween(2.0,9.0);
        assertThat(count("SELECT COUNT(*) " + ROLLING_APPOINTMENTS + " AND a.cancelled_at IS NULL AND NOT a.arrived") * 100.0 / total)
            .isBetween(2.0,10.0);
        assertThat(count("SELECT COUNT(*) " + ROLLING_APPOINTMENTS + """
            AND s.issued_at >= DATE(v.arrived_at) + INTERVAL 1 DAY
            """) * 100.0 / total).isBetween(3.0,12.0);
        assertThat(count("SELECT COUNT(*) " + ROLLING_APPOINTMENTS + """
            AND ((a.arrived AND v.id IS NULL) OR (NOT a.arrived AND v.id IS NOT NULL)
                 OR (s.id IS NOT NULL AND s.total<>(SELECT SUM(ROUND(t.quantity*t.unit_price,2))
                                                  FROM treatment t WHERE t.visit_id=v.id)))
            """)).isZero();
        for (String column : List.of("dentist_id", "patient_id")) {
            assertThat(count("SELECT COUNT(*) FROM (SELECT " + column + """
                ,scheduled_at FROM appointment WHERE cancelled_at IS NULL
                GROUP BY
                """ + column + ",scheduled_at HAVING COUNT(*)>1) conflicts")).isZero();
        }
        assertThat(count("SELECT COUNT(*) " + ROLLING_APPOINTMENTS + """
            AND (SELECT COUNT(*) FROM operation_log l WHERE
                (l.target_type='APPOINTMENT' AND l.target_id=a.id
                     AND l.action IN ('CREATE_APPOINTMENT','REGISTER_ARRIVAL','CANCEL_APPOINTMENT'))
                OR (l.target_type='VISIT' AND l.target_id=v.id
                     AND l.action IN ('CONFIRM_VISIT','END_WITHOUT_TREATMENT'))
                OR (l.target_type='SETTLEMENT' AND l.target_id=s.id AND l.action='ISSUE_SETTLEMENT'))
                    <> 1+(a.cancelled_at IS NOT NULL)+2*(v.id IS NOT NULL)+(s.id IS NOT NULL)
            """)).isZero();
        assertThat(count("SELECT COUNT(DISTINCT l.username) " + ROLLING_APPOINTMENTS.replace(
            "WHERE p.account_no", "JOIN operation_log l ON l.target_id=v.id AND l.target_type='VISIT' WHERE p.account_no") + """
            AND l.action='CONFIRM_VISIT' AND l.username IN ('dentist3','dentist4')
            """)).isEqualTo(2);

        var legacy = (JsonNode) json.valueToTree(ReflectionTestUtils.invokeMethod(application.getBean("quarterController"), "prepare", 2026, 3));
        assertThat(legacy.get("unsettledVisits").values()).singleElement().satisfies(visit -> {
            assertThat(visit.get("patient").get("accountNo").asString()).isEqualTo("P000008");
            assertThat(visit.get("arrivedAt").asString()).isEqualTo("2026-09-29T10:35:00");
        });
        var rows = legacy.get("rows").values().stream().map(dentist -> Map.of(
            "dentistId", dentist.get("dentistId").asLong(), "supplyCost", 0, "operatingCost", 0, "basis", "滚动演示验收")).toList();
        Class<?> input = Class.forName("com.adc.quarter.QuarterDtos$Input");
        var form = json.treeToValue(json.valueToTree(Map.of("rows", rows)), input);
        var preview = (JsonNode) json.valueToTree(ReflectionTestUtils.invokeMethod(application.getBean("quarterController"),
            "preview", 2026, 3, form));
        assertThat(preview.get("problems").values()).extracting(JsonNode::asString)
            .containsExactly("本季度还有 1 次已确认但未开结算单的就诊，请前台开单后再确认");
    }

    @Test @Transactional void holdsRecentBillsAndOnlyIssuesDueOnesBeforeTheCutoff() {
        rolling.runAt(AS_OF);
        var held = jdbc.sql("""
            SELECT v.id FROM visit v JOIN request_record r ON r.request_id=CONCAT('demo-rolling:bill:',v.id)
            WHERE DATE(v.arrived_at) IN ('2026-11-24','2026-11-25')
              AND r.created_at=DATE(v.arrived_at)+INTERVAL 3 DAY ORDER BY v.arrived_at
            """).query(Long.class).list();
        assertThat(held).hasSize(2);
        var next = AS_OF.plusDays(1);
        rolling.runAt(next);
        assertThat(jdbc.sql("SELECT issued_at FROM settlement WHERE visit_id=?").param(held.get(0))
            .query(LocalDateTime.class).single()).isBefore(next);
        assertThat(jdbc.sql("SELECT id FROM settlement WHERE visit_id=?").param(held.get(1))
            .query(Long.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM settlement WHERE issued_at>=:now AND visit_id IN (:ids)")
            .param("now", next).param("ids", held)
            .query(Long.class).single()).isZero();
        assertThat(count("""
            SELECT COUNT(*) FROM appointment_request r JOIN patient_account p ON p.id=r.patient_account_id
            WHERE p.phone LIKE '1392600%' AND r.status='PENDING' AND r.created_at>='2026-11-26'
            """)).isPositive();
        assertThat(count("""
            SELECT COUNT(DISTINCT r.status) FROM appointment_request r JOIN patient_account p ON p.id=r.patient_account_id
            WHERE p.phone LIKE '1392600%'
            """)).isEqualTo(4);
    }

    @Test void skipsTheRunWhileAnotherSessionHoldsTheSharedScheduleLock() throws Exception {
        long before = count("SELECT COUNT(*) FROM request_record WHERE request_id LIKE 'demo-rolling:day:%'");
        try (var connection = dataSource.getConnection();
             var lock = connection.createStatement()) {
            lock.execute("SELECT GET_LOCK(SHA2(CONCAT(DATABASE(), ':demo-rolling'),256),0)");
            try {
                rolling.runAt(AS_OF);
                assertThat(count("SELECT COUNT(*) FROM request_record WHERE request_id LIKE 'demo-rolling:day:%'")).isEqualTo(before);
            } finally {
                lock.execute("SELECT RELEASE_LOCK(SHA2(CONCAT(DATABASE(), ':demo-rolling'),256))");
            }
        }
    }

    @Test @Transactional void convertsADueRequestOnThePreferredDayWithoutCreatingAFutureVisit() {
        var runAt = AS_OF.plusDays(1);
        var day = runAt.toLocalDate();
        jdbc.sql("""
            INSERT INTO appointment(patient_id,dentist_id,clinic_id,scheduled_at)
            SELECT patient_id,dentist_id,clinic_id,? FROM appointment WHERE scheduled_at='2026-07-03 09:00:00' LIMIT 1
            """).param(day.atTime(8, 0)).update();
        var holder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO appointment_request(patient_account_id,contact_name,contact_phone,clinic_id,preferred_date,
                                            time_slot,created_at)
            SELECT p.id,p.name,p.phone,c.id,?,'MORNING','2026-11-24 00:00:00'
            FROM patient_account p CROSS JOIN clinic c WHERE p.phone='13926000001' ORDER BY c.id LIMIT 1
            """).param(day).update(holder, "id");
        long request = holder.getKey().longValue();
        long account = jdbc.sql("SELECT patient_account_id FROM appointment_request WHERE id=?")
            .param(request).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO request_record(account_type,request_id,user_id,action,content_hash,created_at,response_body)
            VALUES ('EMPLOYEE',?,0,'DEMO_ROLLING','',?,?)
            """).params("demo-rolling:request:" + request, day.atStartOfDay(), json.writeValueAsBytes(List.of(request, account))).update();
        rolling.runAt(runAt);
        record Scheduled(long id, LocalDateTime scheduledAt) {}
        var booking = jdbc.sql("""
            SELECT a.id,a.scheduled_at FROM appointment_request r JOIN appointment a ON a.id=r.appointment_id
            WHERE r.id=? AND r.status='CONFIRMED' AND r.handled_at='2026-11-25 08:30:00'
            """).param(request).query(Scheduled.class).single();
        long appointment = booking.id();
        assertThat(booking.scheduledAt().toLocalDate()).isEqualTo(day);
        assertThat(jdbc.sql("SELECT id FROM visit WHERE appointment_id=?").param(appointment).query(Long.class).optional()).isEmpty();
        var logged = jdbc.sql("""
            SELECT detail FROM operation_log WHERE target_type='APPOINTMENT' AND target_id=? AND action='CREATE_APPOINTMENT'
            """).param(appointment).query(String.class).single();
        assertThat(json.readTree(logged).get("requestId").asLong()).isEqualTo(request);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private Map<String,Object> visitor() {
        return jdbc.sql("SELECT * FROM appointment WHERE scheduled_at='2026-08-02 09:00:00' AND version=7")
            .query().singleRow();
    }

    private Map<String,Long> counts() {
        return Map.of("bookings", count("SELECT COUNT(*) " + ROLLING_APPOINTMENTS),
            "visits", count("SELECT COUNT(v.id) " + ROLLING_APPOINTMENTS),
            "bills", count("SELECT COUNT(s.id) " + ROLLING_APPOINTMENTS),
            "cancelled", count("SELECT COUNT(a.cancelled_at) " + ROLLING_APPOINTMENTS),
            "logs", count("SELECT COUNT(*) FROM operation_log"),
            "requests", count("SELECT COUNT(*) FROM appointment_request"));
    }
}
