package com.adc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class PatientDemoDataTests extends ContractHttp {
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @Test void seededAccountsLogInAndSeeTheirOwnRequests() throws Exception {
        long installed = jdbc.sql("""
            SELECT UNIX_TIMESTAMP(installed_on) FROM flyway_schema_history WHERE version='14' AND success
            """).query(Long.class).single();
        LocalDate day = Instant.ofEpochSecond(installed).atZone(clock.getZone()).toLocalDate();
        var zhang = requests("13900000001", "张伟");
        var li = requests("13900000002", "李娜");
        var zhangPending = request(zhang, "PENDING");
        assertThat(zhangPending.get("preferredDate").asString()).isEqualTo(day.plusDays(1).toString());
        assertThat(zhangPending.get("clinic").get("name").asString()).isEqualTo("天河诊所");
        assertThat(zhangPending.get("timeSlot").asString()).isEqualTo("MORNING");
        assertThat(zhangPending.get("items").values()).extracting(i -> i.get("name").asString())
            .containsExactlyInAnyOrder("口腔检查","洁牙");
        var liPending = request(li, "PENDING");
        assertThat(liPending.get("preferredDate").asString()).isEqualTo(day.plusDays(2).toString());
        assertThat(liPending.get("clinic").get("name").asString()).isEqualTo("越秀诊所");
        assertThat(liPending.get("timeSlot").asString()).isEqualTo("AFTERNOON");
        String frontName = jdbc.sql("SELECT display_name FROM app_user WHERE username='front1'").query(String.class).single();
        var invalid = request(li, "INVALID");
        assertThat(invalid.get("invalidReason").asString()).isNotBlank();
        assertThat(invalid.get("handledBy").asString()).isEqualTo(frontName);
        assertThat(invalid.get("handledAt").isString()).isTrue();
        assertThat(request(li, "WITHDRAWN").get("withdrawnAt").isString()).isTrue();

        // A partially populated day can lack the 09:00 demo visit. The other four examples still exist.
        var matching = jdbc.sql("""
            SELECT a.id FROM appointment a JOIN patient p ON p.id=a.patient_id
            JOIN appointment history ON history.patient_id=p.id AND history.scheduled_at='2026-07-03 09:00'
                  AND history.arrived AND history.dentist_id=a.dentist_id AND history.clinic_id=a.clinic_id
            JOIN visit v ON v.appointment_id=a.id AND v.clinic_id=a.clinic_id AND v.dentist_id=a.dentist_id
            WHERE p.account_no='P000001' AND a.scheduled_at=? AND a.arrived AND a.cancelled_at IS NULL
              AND v.arrived_at=? AND v.registered_at=v.arrived_at
              AND (SELECT COUNT(*) FROM appointment_item ai WHERE ai.appointment_id=a.id)=2
              AND (SELECT COUNT(*) FROM appointment_item ai JOIN appointment_item hi ON hi.item_id=ai.item_id
                   WHERE ai.appointment_id=a.id AND hi.appointment_id=history.id)=2
            """).params(day.atTime(9, 0), day.atTime(9, 5)).query(Long.class).optional();
        if (matching.isPresent()) {
            var confirmed = request(zhang, "CONFIRMED");
            assertThat(LocalDateTime.parse(confirmed.get("createdAt").asString()).toLocalDate()).isEqualTo(day.minusDays(1));
            assertThat(confirmed.get("handledBy").asString()).isEqualTo(frontName);
            assertThat(confirmed.get("appointment").get("id").asLong()).isEqualTo(matching.get());
            assertThat(zhang.size()).isEqualTo(2);
        } else {
            assertThat(zhang.size()).isEqualTo(1);
        }
        assertThat(li.size()).isEqualTo(3);
    }

    private JsonNode requests(String phone, String name) throws Exception {
        var login = api("POST", "/api/patient-auth/login", null, Map.of("phone", phone, "password", "123456"), 200);
        var patient = login.get("patient");
        assertThat(patient.propertyNames()).containsExactlyInAnyOrder("id","name","phone","createdAt");
        assertThat(patient.get("name").asString()).isEqualTo(name);
        String bearer = login.get("token").asString();
        var result = api("GET", "/api/patient/appointment-requests", bearer, null, 200);
        assertThat(result.values()).allSatisfy(row -> {
            assertThat(row.get("contactName").asString()).isEqualTo(name);
            assertThat(row.get("contactPhone").asString()).isEqualTo(phone);
        });
        assertThat(send("POST", "/api/patient-auth/logout", bearer, null, null).statusCode()).isEqualTo(200);
        return result;
    }

    private JsonNode request(JsonNode rows, String status) {
        var matches = rows.values().stream().filter(row -> row.get("status").asString().equals(status)).toList();
        assertThat(matches).as("演示预约意向 %s", status).hasSize(1);
        return matches.get(0);
    }
}
