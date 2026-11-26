package com.adc;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

class SettlementContractTests extends ContractHttp {
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private String visit(String front, boolean confirmed) throws Exception {
        var patient = api("POST", "/api/patients", front, Map.of(
            "name", "结算测试-" + UUID.randomUUID(), "birthDate", "1990-03-05", "phone", "13900001234",
            "billingAddress", "测试地址", "emergencyName", "联系人", "emergencyPhone", "13900001235",
            "allergyIds", List.of()), 201);
        long patientId = patient.get("id").asLong();
        // Independent appointments can share a day but cannot share a dentist and minute.
        var scheduledAt = LocalDateTime.now(clock).plusDays(3)
            .plusSeconds(ThreadLocalRandom.current().nextLong(365L * 86400)).withNano(0);
        var arrivedAt = LocalDateTime.now(clock).minusHours(1).withNano(0);
        jdbc.sql("""
            INSERT INTO appointment (patient_id,dentist_id,clinic_id,scheduled_at,arrived)
            SELECT ?,d.id,c.id,?,true FROM dentist d CROSS JOIN clinic c
            WHERE d.employee_no='D001' AND c.name='天河诊所'
            """).params(patientId, scheduledAt).update();
        jdbc.sql("""
            INSERT INTO visit (appointment_id,dentist_id,clinic_id,arrived_at,registered_at)
            SELECT id,dentist_id,clinic_id,?,? FROM appointment WHERE patient_id=?
            """).params(arrivedAt, arrivedAt, patientId).update();
        long visitId = jdbc.sql("""
            SELECT v.id FROM visit v JOIN appointment a ON a.id=v.appointment_id WHERE a.patient_id=?
            """).param(patientId).query(Long.class).single();
        if (confirmed) {
            jdbc.sql("UPDATE visit SET problem='演示检查',confirmed_at=? WHERE id=?")
                .params(arrivedAt, visitId).update();
            jdbc.sql("""
                INSERT INTO treatment (visit_id,item_id,quantity,unit_price,item_name,unit_name)
                SELECT ?,id,0.125,1.00,name,standard_unit FROM treatment_item WHERE name='局部麻醉'
                """).param(visitId).update();
        }
        return "/api/visits/" + visitId;
    }

    @Test void previewsIssuesReplaysAndReprints() throws Exception {
        String front = token("front1");
        String visit = visit(front, true);
        var preview = api("POST", visit + "/settlement-preview", front, null, 200, UUID.randomUUID().toString());
        assertThat(preview.get("existing").asBoolean()).isFalse();
        assertThat(preview.get("lines").get(0).get("subtotal").decimalValue()).isEqualByComparingTo("0.13");
        assertThat(preview.get("total").decimalValue()).isEqualByComparingTo("0.13");

        String issuePath = visit + "/settlement";
        var body = Map.of("checkToken", preview.get("checkToken").asString());
        String key = UUID.randomUUID().toString();
        var issued = api("POST", issuePath, front, body, 201, key);
        var number = issued.get("settlementNo").asString();
        assertThat(number).matches("JS[0-9]{8}-[0-9]{4}");
        assertThat(issued.get("issuedBy").asString()).isEqualTo("王丽");
        assertThat(issued.get("total")).isEqualTo(preview.get("total"));
        assertThat(issued.get("lines")).isEqualTo(preview.get("lines"));
        assertThat(api("POST", issuePath, front, body, 201, key)).isEqualTo(issued);
        assertThat(api("POST", issuePath, front, body, 200, UUID.randomUUID().toString())).isEqualTo(issued);

        var existing = api("POST", visit + "/settlement-preview", front, null, 200);
        assertThat(existing.get("existing").asBoolean()).isTrue();
        assertThat(existing.get("settlement")).isEqualTo(issued);
        assertThat(existing.get("lines").isNull() && existing.get("checkToken").isNull() && existing.get("total").isNull())
            .isTrue();
        String account = issued.get("patient").get("accountNo").asString();
        jdbc.sql("UPDATE patient SET name='结算后更名' WHERE account_no=?").param(account).update();
        assertThat(api("GET", "/api/settlements/" + number, front, null, 200)).isEqualTo(issued);
        assertThat(api("GET", "/api/settlements?keyword=" + account, front, null, 200).values()).containsExactly(issued);
        LocalDate date = LocalDate.parse(issued.get("issuedAt").asString().substring(0, 10));
        assertThat(api("GET", "/api/settlements?from=" + date + "&to=" + date + "&keyword=" + number,
            front, null, 200).values()).containsExactly(issued);
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM operation_log WHERE target_type='SETTLEMENT'
              AND target_id=(SELECT id FROM settlement WHERE settlement_no=?)
            """).param(number).query(Long.class).single()).isEqualTo(1);
        api("GET", "/api/settlements", token("hq1"), null, 403);
    }

    @Test void rejectsUnconfirmedMissingAndStaleTokens() throws Exception {
        String front = token("front1");
        String unconfirmed = visit(front, false);
        var state = api("POST", unconfirmed + "/settlement", front, Map.of("checkToken","x"), 409);
        assertThat(state.get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        var blocked = api("POST", unconfirmed + "/settlement-preview", front, null, 409);
        assertThat(blocked.get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        jdbc.sql("UPDATE visit SET confirmed_at=arrived_at,no_treatment_reason='患者改期' WHERE id=?")
            .param(Long.parseLong(unconfirmed.substring(unconfirmed.lastIndexOf('/') + 1))).update();
        assertThat(api("POST", unconfirmed + "/settlement", front, Map.of("checkToken","x"), 409).get("code").asString())
            .isEqualTo("STATE_NOT_ALLOWED");

        String confirmed = visit(front, true);
        var missing = api("POST", confirmed + "/settlement", front, Map.of(), 400);
        assertThat(missing.get("fieldErrors").get(0).get("field").asString()).isEqualTo("checkToken");
        String previewId = UUID.randomUUID().toString();
        var preview = api("POST", confirmed + "/settlement-preview", front, null, 200, previewId);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_record WHERE request_id=?").param(previewId)
            .query(Long.class).single()).isZero();
        jdbc.sql("UPDATE visit SET version=version+1 WHERE id=?")
            .param(Long.parseLong(confirmed.substring(confirmed.lastIndexOf('/') + 1))).update();
        var changed = api("POST", confirmed + "/settlement", front,
            Map.of("checkToken", preview.get("checkToken").asString()), 409);
        assertThat(changed.get("code").asString()).isEqualTo("CHANGED");
        assertThat(changed.get("current").isNull()).isTrue();
        api("POST", confirmed + "/settlement-preview", token("dentist1"), null, 403);
    }

    @Test void locksSameVisitAndAllocatesDistinctNumbersForParallelVisits() throws Exception {
        String front = token("front1");
        String a = visit(front, true);
        String b = visit(front, true);
        String first = tokenBody(a, front);
        String second = tokenBody(b, front);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var one = pool.submit(issueAfter(gate, a, front, first));
            var same = pool.submit(issueAfter(gate, a, front, first));
            var other = pool.submit(issueAfter(gate, b, front, second));
            gate.countDown();
            var a1 = one.get(25, TimeUnit.SECONDS);
            var a2 = same.get(25, TimeUnit.SECONDS);
            var b1 = other.get(25, TimeUnit.SECONDS);
            assertThat(List.of(a1.statusCode(), a2.statusCode())).containsExactlyInAnyOrder(201, 200);
            assertThat(a1.body()).isEqualTo(a2.body());
            assertThat(b1.statusCode()).as(b1.body()).isEqualTo(201);
            assertThat(json.readTree(b1.body()).get("settlementNo"))
                .isNotEqualTo(json.readTree(a1.body()).get("settlementNo"));
        } finally {
            pool.shutdownNow();
        }
    }

    private String tokenBody(String visit, String front) throws Exception {
        var preview = api("POST", visit + "/settlement-preview", front, null, 200);
        return json.writeValueAsString(Map.of("checkToken", preview.get("checkToken").asString()));
    }

    private Callable<HttpResponse<String>> issueAfter(CountDownLatch gate, String visit, String front, String body) {
        return () -> {
            gate.await();
            return send("POST", visit + "/settlement", front, body, UUID.randomUUID().toString());
        };
    }
}
