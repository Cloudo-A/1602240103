package com.adc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class QuarterContractTests extends ContractHttp {
    private static final String API = "/api/quarters";
    @Autowired JdbcClient jdbc;

    @Test void seedsQ2AndLeavesQ3PendingAndRestrictsAllEndpointsToHeadquarters() throws Exception {
        String hq = token("hq1");
        var q2 = api("GET", API + "/2026/2", hq, null, 200);
        var existing = q2.get("existing");
        assertThat(existing.get("firstConfirmedAt").asString()).isEqualTo("2026-07-08T16:00:00");
        assertThat(existing.get("collectionWorkdays").asInt()).isEqualTo(6);
        assertThat(existing.get("companyNetProfit").decimalValue()).isEqualByComparingTo("-5500.00");
        assertThat(existing.get("incentiveFund").isNull()).isTrue();
        assertThat(existing.get("fundStatus").asString()).isEqualTo("TRANSFERRED");
        assertThat(existing.get("fundTransferAt")).isEqualTo(existing.get("confirmedAt"));
        assertThat(existing.get("fundNote").asString()).contains("筹备期运营费用");
        assertThat(existing.get("rows").values()).extracting(row -> row.get("operatingCost").decimalValue())
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("1800.00"), new BigDecimal("1500.00"),
                new BigDecimal("1200.00"), new BigDecimal("1000.00"));
        assertThat(api("GET", API, hq, null, 200).values()).contains(existing);

        var q3 = api("GET", API + "/2026/3", hq, null, 200);
        assertThat(q3.get("existing").isNull()).isTrue();
        assertThat(q3.get("unsettledVisits").values()).anySatisfy(visit -> {
            assertThat(visit.get("patient").get("accountNo").asString()).isEqualTo("P000008");
            assertThat(visit.get("arrivedAt").asString()).isEqualTo("2026-09-29T10:35:00");
        });
        for (String name : List.of("front1", "dentist1")) {
            String bearer = token(name);
            assertThat(api("GET", API, bearer, null, 403).get("code").asString()).isEqualTo("FORBIDDEN");
            api("GET", API + "/2026/2", bearer, null, 403);
            api("POST", API + "/2026/2/preview", bearer, Map.of(), 403);
            api("POST", API + "/2026/2/confirm", bearer, Map.of(), 403);
        }
        var badQuarter = api("GET", API + "/2026/5", hq, null, 400);
        assertThat(badQuarter.get("fieldErrors").get(0).get("field").asString()).isEqualTo("quarter");
    }

    @Test void previewAggregatesBothBusinessBlockersAndStillCalculatesUsableRows() throws Exception {
        String hq = token("hq1");
        String front = token("front1");
        YearQuarter future = new YearQuarter(2090, 4);
        addUnbilledVisit(front, future, new BigDecimal("25.00"));
        String path = future.path();
        var prepare = api("GET", path, hq, null, 200);
        assertThat(prepare.get("quarterEnded").asBoolean()).isFalse();
        var rows = costs(prepare);
        rows.get(0).put("supplyCost", null);
        rows.get(0).put("basis", " ");
        int outstanding = prepare.get("unsettledVisits").size();
        assertThat(outstanding).isPositive();
        String previewRequest = UUID.randomUUID().toString();
        var preview = api("POST", path + "/preview", hq, Map.of("rows", rows), 200, previewRequest);
        assertThat(preview.get("problems").values()).extracting(JsonNode::asString)
            .anyMatch(msg -> msg.contains("季度") && msg.contains("没有结束"))
            .anyMatch(msg -> msg.contains("还有 " + outstanding + " 次") && msg.contains("未开结算单"))
            .anyMatch(msg -> msg.contains("医疗用品成本"))
            .anyMatch(msg -> msg.contains("依据"));
        assertThat(preview.get("checkToken").isNull()).isTrue();
        assertThat(preview.get("rows").get(0).get("netProfit").isNull()).isTrue();
        assertThat(preview.get("rows").get(1).get("netProfit").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(preview.get("companyNetProfit").isNull() && preview.get("incentiveFund").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_record WHERE request_id=?").param(previewRequest)
            .query(Long.class).single()).isZero();
        assertThat(api("POST", path + "/confirm", hq, Map.of("rows", rows), 409).get("code").asString())
            .isEqualTo("STATE_NOT_ALLOWED");
    }

    @Test void confirmsOnlyAfterBillingAndPreservesFirstConfirmationOnReplacement() throws Exception {
        String hq = token("hq1");
        String front = token("front1");
        YearQuarter quarter = unusedClosedQuarter();
        String path = quarter.path();
        long visit = addUnbilledVisit(front, quarter, new BigDecimal("100.05"));
        var before = api("GET", path, hq, null, 200);
        assertThat(before.get("unsettledVisits").values()).extracting(row -> row.get("id").asLong())
            .containsExactly(visit);
        var rows = costs(before);
        var blocked = api("POST", path + "/preview", hq, Map.of("rows", rows), 200);
        assertThat(blocked.get("problems").values()).extracting(JsonNode::asString)
            .anyMatch(msg -> msg.contains("还有 1 次") && msg.contains("未开结算单"));
        assertThat(blocked.get("checkToken").isNull()).isTrue();
        assertThat(api("POST", path + "/confirm", hq, Map.of("rows", rows, "checkToken", "old"), 409)
            .get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");

        settle(front, visit);

        var prepare = api("GET", path, hq, null, 200);
        assertThat(prepare.get("unsettledVisits").isEmpty()).isTrue();
        assertThat(preparedRow(prepare, "D001").get("revenue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(preparedRow(prepare, "D002").get("revenue").decimalValue()).isEqualByComparingTo("100.05");

        var preview = api("POST", path + "/preview", hq, Map.of("rows", rows), 200);
        assertThat(preview.get("problems").isEmpty()).isTrue();
        assertThat(preview.get("companyNetProfit").decimalValue()).isEqualByComparingTo("100.05");
        assertThat(preview.get("incentiveFund").decimalValue()).isEqualByComparingTo("10.01");
        settle(front, addUnbilledVisit(front, quarter, new BigDecimal("0.01")));
        var stale = api("POST", path + "/confirm", hq,
            Map.of("rows", rows, "checkToken", preview.get("checkToken").asString()), 409);
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current").isNull()).isTrue();
        preview = api("POST", path + "/preview", hq, Map.of("rows", rows), 200);
        assertThat(preview.get("companyNetProfit").decimalValue()).isEqualByComparingTo("100.06");

        String key = UUID.randomUUID().toString();
        var command = Map.of("rows", rows, "checkToken", preview.get("checkToken").asString());
        var first = api("POST", path + "/confirm", hq, command, 200, key);
        assertThat(first.get("fundStatus").asString()).isEqualTo("ACCRUED");
        assertThat(first.get("fundTransferAt").isNull()).isTrue();
        assertThat(api("POST", path + "/confirm", hq, command, 200, key)).isEqualTo(first);
        long id = first.get("id").asLong();
        assertThat(auditCount(id)).isEqualTo(1);

        rows.get(0).put("operatingCost", 200);
        var revision = api("POST", path + "/preview", hq, Map.of("rows", rows), 200);
        assertThat(revision.get("fundNoteRequired").asBoolean()).isTrue();
        assertThat(revision.get("incentiveFund").isNull()).isTrue();
        var request = new LinkedHashMap<String,Object>();
        request.put("rows", rows);
        request.put("checkToken", revision.get("checkToken").asString());
        var missing = api("POST", path + "/confirm", hq, request, 400);
        assertThat(missing.get("fieldErrors").values()).extracting(field -> field.get("field").asString())
            .contains("fundNote");
        request.put("fundNote", "经营亏损，基金转总部处理");
        Thread.sleep(1100); // Different persisted seconds distinguish first and latest confirmation.
        var second = api("POST", path + "/confirm", hq, request, 200);
        assertThat(second.get("id").asLong()).isEqualTo(id);
        assertThat(second.get("firstConfirmedAt")).isEqualTo(first.get("firstConfirmedAt"));
        assertThat(second.get("confirmedAt").asString()).isGreaterThan(first.get("confirmedAt").asString());
        assertThat(second.get("collectionWorkdays")).isEqualTo(first.get("collectionWorkdays"));
        assertThat(second.get("fundStatus").asString()).isEqualTo("TRANSFERRED");
        assertThat(second.get("fundTransferAt")).isEqualTo(second.get("confirmedAt"));
        assertThat(api("GET", path, hq, null, 200).get("existing")).isEqualTo(second);
        assertThat(auditCount(id)).isEqualTo(2);
    }

    private record YearQuarter(int year, int quarter) {
        String path() { return API + "/" + year + "/" + quarter; }
        LocalDate start() { return LocalDate.of(year, (quarter - 1) * 3 + 1, 1); }
    }

    private YearQuarter unusedClosedQuarter() {
        for (int year = 2024; year >= 2000; year--) {
            for (int q = 1; q <= 4; q++) {
                var range = new YearQuarter(year, q);
                boolean unused = jdbc.sql("""
                    SELECT NOT EXISTS (SELECT 1 FROM visit WHERE arrived_at >= ? AND arrived_at < ?)
                       AND NOT EXISTS (SELECT 1 FROM quarter_close WHERE year=? AND quarter=?)
                    """).params(range.start(), range.start().plusMonths(3), year, q).query(Boolean.class).single();
                if (unused) return range;
            }
        }
        throw new IllegalStateException("No unused historical test quarter");
    }

    private long addUnbilledVisit(String front, YearQuarter quarter, BigDecimal unitPrice) throws Exception {
        var patient = api("POST", "/api/patients", front, Map.of(
            "name", "季度测试-" + UUID.randomUUID(), "birthDate", "1990-01-01", "billingAddress", "测试地址",
            "phone", "13900010101", "emergencyName", "联系人", "emergencyPhone", "13900010102", "allergyIds", List.of()), 201);
        long patientId = patient.get("id").asLong();
        LocalDateTime arrived = quarter.start().plusDays(10).atTime(9, 5);
        LocalDateTime scheduled = arrived.plusMonths(3).plusSeconds(ThreadLocalRandom.current().nextInt(864000));
        jdbc.sql("""
            INSERT INTO appointment(patient_id,dentist_id,clinic_id,scheduled_at,arrived)
            SELECT ?,d.id,c.id,?,true FROM dentist d CROSS JOIN clinic c
            WHERE d.employee_no='D001' AND c.name='天河诊所'
            """).params(patientId, scheduled).update();
        jdbc.sql("""
            INSERT INTO visit(appointment_id,dentist_id,clinic_id,arrived_at,registered_at,confirmed_at,problem)
            SELECT a.id,d.id,a.clinic_id,?,?,?,'检查' FROM appointment a CROSS JOIN dentist d
            WHERE a.patient_id=? AND d.employee_no='D002'
            """).params(arrived, arrived, arrived.plusMinutes(40), patientId).update();
        long visit = jdbc.sql("""
            SELECT v.id FROM visit v JOIN appointment a ON a.id=v.appointment_id WHERE a.patient_id=?
            """).param(patientId).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO treatment(visit_id,item_id,quantity,unit_price,item_name,unit_name)
            SELECT ?,id,1,?,name,standard_unit FROM treatment_item WHERE name='口腔检查'
            """).params(visit, unitPrice).update();
        return visit;
    }

    private static List<Map<String,Object>> costs(JsonNode prepare) {
        var out = new ArrayList<Map<String,Object>>();
        for (var row : prepare.get("rows")) {
            out.add(new LinkedHashMap<>(Map.of("dentistId", row.get("dentistId").asLong(),
                "supplyCost", 0, "operatingCost", 0, "basis", "季度测试核对依据")));
        }
        return out;
    }

    private void settle(String front, long visit) throws Exception {
        String path = "/api/visits/" + visit;
        var preview = api("POST", path + "/settlement-preview", front, null, 200);
        api("POST", path + "/settlement", front, Map.of("checkToken", preview.get("checkToken").asString()), 201);
    }

    private static JsonNode preparedRow(JsonNode prepare, String employeeNo) {
        return prepare.get("rows").values().stream()
            .filter(row -> employeeNo.equals(row.get("employeeNo").asString())).findFirst().orElseThrow();
    }

    private long auditCount(long targetId) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM operation_log
            WHERE action='CONFIRM_QUARTER' AND target_type='QUARTER_CLOSE' AND target_id=?
            """).param(targetId).query(Long.class).single();
    }
}
