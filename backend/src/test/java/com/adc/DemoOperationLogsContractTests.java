package com.adc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class DemoOperationLogsContractTests extends ContractHttp {
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @Test void historicalEventsReferenceTheSeededBusinessObjectsAndTheirOperators() throws Exception {
        String hq = token("hq1");
        var history = logs(hq, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 11, 18));
        var sample = jdbc.sql("""
            SELECT a.id AS appointment_id, v.id AS visit_id, s.id AS settlement_id,
                   s.settlement_no, u.id AS dentist_user_id, u.username
            FROM settlement s JOIN visit v ON v.id = s.visit_id
            JOIN appointment a ON a.id = v.appointment_id JOIN app_user u ON u.dentist_id = v.dentist_id
            WHERE s.settlement_no = 'JS20260703-0001'
            """).query((rs, row) -> new Sample(rs.getLong("appointment_id"), rs.getLong("visit_id"),
                rs.getLong("settlement_id"), rs.getString("settlement_no"),
                rs.getLong("dentist_user_id"), rs.getString("username"))).single();
        var created = event(history, "CREATE_APPOINTMENT", "APPOINTMENT", sample.appointmentId(), "2026-06-30T10:00:00");
        assertThat(created.get("username").asString()).isEqualTo("front1");
        assertThat(created.get("detail").get("id").asLong()).isEqualTo(sample.appointmentId());

        long cancelledId = jdbc.sql("""
            SELECT a.id FROM appointment a JOIN patient p ON p.id = a.patient_id
            WHERE a.scheduled_at = '2026-07-08 14:00:00' AND p.account_no = 'P000002' AND a.cancelled_by = '王丽'
            """).query(Long.class).single();
        var cancelled = event(history, "CANCEL_APPOINTMENT", "APPOINTMENT", cancelledId, "2026-07-07T18:00:00");
        assertThat(cancelled.get("detail").get("status").asString()).isEqualTo("CANCELLED");
        var arrival = event(history, "REGISTER_ARRIVAL", "APPOINTMENT", sample.appointmentId(), "2026-07-03T09:05:00");
        assertThat(arrival.get("detail").propertyNames()).containsExactlyInAnyOrder(
            "visitId", "arrivedAt", "registeredAt", "dentistId", "clinicId");
        assertThat(arrival.get("detail").get("visitId").asLong()).isEqualTo(sample.visitId());
        var confirmation = event(history, "CONFIRM_VISIT", "VISIT", sample.visitId(), "2026-07-03T09:45:00");
        assertThat(confirmation.get("userId").asLong()).isEqualTo(sample.dentistUserId());
        assertThat(confirmation.get("username").asString()).isEqualTo(sample.username());
        assertThat(confirmation.get("detail").get("after").get("status").asString()).isEqualTo("CONFIRMED");
        var visit = api("GET", "/api/visits/" + sample.visitId(), hq, null, 200);
        assertThat(confirmation.get("detail").get("after").propertyNames()).containsExactlyInAnyOrderElementsOf(visit.propertyNames());
        assertThat(confirmation.get("detail").get("after").get("arrivedAt")).isEqualTo(visit.get("arrivedAt"));
        var bill = event(history, "ISSUE_SETTLEMENT", "SETTLEMENT", sample.settlementId(), "2026-07-03T10:10:00");
        assertThat(bill.get("username").asString()).isEqualTo("front1");
        assertThat(bill.get("detail").get("settlementNo").asString()).isEqualTo(sample.settlementNo());
        assertThat(bill.get("detail").get("visitId").asLong()).isEqualTo(sample.visitId());
        String front = token("front1");
        assertThat(bill.get("detail")).isEqualTo(api("GET", "/api/settlements/" + sample.settlementNo(), front, null, 200));

        long quarterId = jdbc.sql("SELECT id FROM quarter_close WHERE year=2026 AND quarter=2").query(Long.class).single();
        var quarter = event(history, "CONFIRM_QUARTER", "QUARTER_CLOSE", quarterId, "2026-07-08T16:00:00");
        assertThat(quarter.get("username").asString()).isEqualTo("hq1");
        assertThat(quarter.get("detail").get("companyNetProfit").decimalValue()).isEqualByComparingTo("-5500.00");
        assertPriceChange(history, "洁牙", "2026-10-01", "150.00");
        assertPriceChange(history, "局部麻醉", "2026-07-01", "35.00");

        var unaffiliatedVisits = jdbc.sql("""
            SELECT v.id FROM visit v JOIN appointment a ON a.id = v.appointment_id
            WHERE a.scheduled_at IN ('2026-08-26 10:00:00', '2026-11-17 10:00:00')
              AND v.no_treatment_reason IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.dentist_id = v.dentist_id)
            """).query(Long.class).list();
        assertThat(unaffiliatedVisits).hasSize(2);
        assertThat(history).noneMatch(log -> action(log, "END_WITHOUT_TREATMENT", "VISIT")
            && unaffiliatedVisits.contains(log.get("targetId").asLong()));
    }

    @Test void recentWindowAndClinicChangeUseBeijingCalendarDates() throws Exception {
        String hq = token("hq1");
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(29);
        var recent = logs(hq, from, today);
        for (var log : recent) {
            assertThat(timestamp(log).toLocalDate()).isBetween(from, today);
        }
        long installed = jdbc.sql("""
            SELECT UNIX_TIMESTAMP(installed_on) FROM flyway_schema_history WHERE version = '12' AND success
            """).query(Long.class).single();
        LocalDate migrationDate = Instant.ofEpochSecond(installed).atZone(clock.getZone()).toLocalDate();
        long clinicId = jdbc.sql("""
            SELECT a.clinic_id FROM appointment a JOIN patient p ON p.id = a.patient_id
            WHERE p.account_no = 'P000001' AND a.scheduled_at = '2026-07-03 09:00:00' AND a.arrived
            """).query(Long.class).single();
        // V12 runs once. Older test databases keep their original audit dates on subsequent test runs.
        LocalDate changeDate = migrationDate.minusDays(2);
        var changeWindow = changeDate.isBefore(from)
            ? logs(hq, changeDate.minusDays(1), changeDate.plusDays(1)) : recent;
        var clinicChange = changeWindow.stream().filter(log -> action(log, "UPDATE", "CLINIC")
            && log.get("targetId").asLong() == clinicId && log.get("username").asString().equals("hq1")
            && !timestamp(log).toLocalDate().isBefore(changeDate.minusDays(1))
            && !timestamp(log).toLocalDate().isAfter(changeDate.plusDays(1))).toList();
        assertThat(clinicChange).anySatisfy(log -> {
            assertThat(action(log, "UPDATE", "CLINIC")).isTrue();
            assertThat(log.get("targetId").asLong()).isEqualTo(clinicId);
            assertThat(log.get("username").asString()).isEqualTo("hq1");
            assertThat(timestamp(log).toLocalDate()).isBetween(changeDate.minusDays(1), changeDate.plusDays(1));
            var detail = log.get("detail");
            assertThat(detail.get("before").get("id").asLong()).isEqualTo(clinicId);
            assertThat(detail.get("after").get("id").asLong()).isEqualTo(clinicId);
            assertThat(detail.get("before")).isNotEqualTo(detail.get("after"));
        });
        // The demo clinic change is the final row of V12. Later tests may use a clock ahead of wall time.
        long lastSeedId = clinicChange.get(0).get("id").asLong();
        var latest = jdbc.sql("SELECT MAX(occurred_at) FROM operation_log WHERE id<=?")
            .param(lastSeedId).query(LocalDateTime.class).single();
        assertThat(latest).isBeforeOrEqualTo(LocalDateTime.now(clock));
    }

    private record Sample(long appointmentId, long visitId, long settlementId, String settlementNo,
                          long dentistUserId, String username) {}

    private List<JsonNode> logs(String hq, LocalDate from, LocalDate to) throws Exception {
        var results = new ArrayList<JsonNode>();
        int page = 0;
        long total;
        do {
            var body = api("GET", "/api/operation-logs?from=" + from + "&to=" + to
                + "&size=100&page=" + page++, hq, null, 200);
            total = body.get("total").asLong();
            var items = body.get("items");
            if (items.isEmpty()) break;
            items.forEach(results::add);
        } while (results.size() < total);
        assertThat((long) results.size()).isEqualTo(total);
        return results;
    }

    private static JsonNode event(List<JsonNode> logs, String action, String type, long target, String time) {
        var matching = logs.stream().filter(log -> action(log, action, type)
            && log.get("targetId").asLong() == target && log.get("occurredAt").asString().equals(time)).toList();
        assertThat(matching).as("%s %s %d at %s", action, type, target, time).hasSize(1);
        return matching.get(0);
    }

    private static boolean action(JsonNode log, String action, String targetType) {
        return log.get("action").asString().equals(action) && log.get("targetType").asString().equals(targetType);
    }

    private static LocalDateTime timestamp(JsonNode log) {
        return LocalDateTime.parse(log.get("occurredAt").asString());
    }

    private void assertPriceChange(List<JsonNode> logs, String name, String effectiveDate, String price) {
        long id = jdbc.sql("SELECT id FROM treatment_item WHERE name=?").param(name).query(Long.class).single();
        assertThat(logs.stream().filter(log -> action(log, "ADD_STANDARD_PRICE", "TREATMENT_ITEM")
            && log.get("targetId").asLong() == id).toList()).anySatisfy(log -> {
                assertThat(log.get("username").asString()).isEqualTo("hq1");
                assertThat(timestamp(log).toLocalDate()).isBefore(LocalDate.parse(effectiveDate));
                var detail = log.get("detail");
                assertThat(detail.get("before").get("prices").values())
                    .noneMatch(p -> p.get("effectiveFrom").asString().equals(effectiveDate));
                assertThat(detail.get("after").get("prices").values()).anySatisfy(p -> {
                    assertThat(p.get("effectiveFrom").asString()).isEqualTo(effectiveDate);
                    assertThat(p.get("unitPrice").decimalValue()).isEqualByComparingTo(price);
                });
            });
    }
}
