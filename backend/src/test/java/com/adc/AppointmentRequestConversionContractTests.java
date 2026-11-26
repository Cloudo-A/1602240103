package com.adc;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentRequestConversionContractTests extends ContractHttp {
    private static final String APPOINTMENTS = "/api/appointments";
    private static final String REQUESTS = "/api/appointment-requests";
    private static final String PATIENT_REQUESTS = "/api/patient/appointment-requests";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @Test void convertsInOneTransactionAndReflectsTheCancelledAppointment() throws Exception {
        String front = token("front1");
        String patient = newPatientToken();
        var request = api("POST", PATIENT_REQUESTS, patient, intent(), 201, key());
        long requestId = request.get("id").asLong();
        String requestPath = REQUESTS + "/" + requestId;
        var form = booking();
        form.put("requestId", Map.of("ignored", true));
        assertThat(api("POST", APPOINTMENTS + "/check", front, form, 200).get("ok").asBoolean()).isTrue();

        form.put("requestId", Long.MAX_VALUE);
        assertState(api("POST", APPOINTMENTS, front, form, 409, key()));
        form.put("requestId", requestId);
        api("POST", APPOINTMENTS, patient, form, 403, key());
        String requestKey = key();
        var appointment = api("POST", APPOINTMENTS, front, form, 201, requestKey);
        long appointmentId = appointment.get("id").asLong();
        assertThat(appointment.has("requestId")).isFalse();
        var confirmed = api("GET", requestPath, front, null, 200);
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("appointment").get("id").asLong()).isEqualTo(appointmentId);
        assertThat(confirmed.get("appointment").get("clinic").get("id"))
            .isNotEqualTo(request.get("clinic").get("id"));
        assertThat(confirmed.get("handledBy").asString()).isEqualTo("王丽");
        assertThat(confirmed.get("handledAt").asString()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
        assertThat(confirmed.get("version").asInt()).isEqualTo(request.get("version").asInt() + 1);
        assertThat(api("POST", APPOINTMENTS, front, form, 201, requestKey)).isEqualTo(appointment);

        var createdLog = log("CREATE_APPOINTMENT", "APPOINTMENT", appointmentId);
        assertThat(createdLog.get("id")).isEqualTo(appointment.get("id"));
        assertThat(createdLog.get("requestId").asLong()).isEqualTo(requestId);
        var confirmLog = log("CONFIRM_APPOINTMENT_REQUEST", "APPOINTMENT_REQUEST", requestId);
        assertThat(confirmLog.get("before")).isEqualTo(request);
        assertThat(confirmLog.get("after")).isEqualTo(confirmed);
        assertState(api("POST", APPOINTMENTS, front, form, 409, key()));

        api("POST", APPOINTMENTS + "/" + appointmentId + "/cancel", front,
            Map.of("version", appointment.get("version").asInt()), 200, key());
        assertThat(api("GET", requestPath, front, null, 200).get("appointment").get("status").asString())
            .isEqualTo("CANCELLED");
        var patientView = api("GET", PATIENT_REQUESTS, patient, null, 200).get(0);
        assertThat(patientView.get("id").asLong()).isEqualTo(requestId);
        assertThat(patientView.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(patientView.get("appointment").get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test void bookingConflictLeavesRequestAndAuditUntouched() throws Exception {
        String front = token("front1");
        var request = api("POST", PATIENT_REQUESTS, newPatientToken(), intent(), 201, key());
        long id = request.get("id").asLong();
        var form = booking();
        api("POST", APPOINTMENTS, front, form, 201, key());
        form.put("requestId", id);
        var conflict = api("POST", APPOINTMENTS, front, form, 409, key());
        assertThat(conflict.get("code").asString()).isEqualTo("CONFLICT");
        assertThat(api("GET", REQUESTS + "/" + id, front, null, 200)).isEqualTo(request);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM operation_log WHERE target_type='APPOINTMENT_REQUEST' AND target_id=?")
            .param(id).query(Long.class).single()).isZero();
    }

    @Test void twoFrontDeskAccountsCannotConvertTheSameRequest() throws Exception {
        String front = token("front1");
        String username = "front_" + key().replace("-", "").substring(0, 16);
        api("POST", "/api/users", token("hq1"), Map.of(
            "username", username, "password", "123456", "displayName", "并发前台", "role", "FRONT_DESK"), 201);
        String other = token(username);
        var request = api("POST", PATIENT_REQUESTS, newPatientToken(), intent(), 201, key());
        long id = request.get("id").asLong();
        var first = booking();
        var second = new LinkedHashMap<>(first);
        second.put("scheduledAt", LocalDateTime.parse(first.get("scheduledAt").toString()).plusMinutes(3).format(TIME));
        first.put("requestId", id);
        second.put("requestId", id);

        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> {
                gate.await();
                return send("POST", APPOINTMENTS, front, json.writeValueAsString(first), key());
            });
            var b = executor.submit(() -> {
                gate.await();
                return send("POST", APPOINTMENTS, other, json.writeValueAsString(second), key());
            });
            gate.countDown();
            var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
            var saved = results.stream().filter(response -> response.statusCode() == 201).findFirst().orElseThrow();
            var rejected = results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertState(json.readTree(rejected.body()));
            assertThat(api("GET", REQUESTS + "/" + id, front, null, 200).get("appointment").get("id"))
                .isEqualTo(json.readTree(saved.body()).get("id"));
            assertThat(jdbc.sql("SELECT COUNT(*) FROM appointment WHERE dentist_id=? AND scheduled_at IN (?,?)")
                .params(first.get("dentistId"), LocalDateTime.parse(first.get("scheduledAt").toString()),
                    LocalDateTime.parse(second.get("scheduledAt").toString())).query(Long.class).single()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void employeeAndPatientWithTheSameIdCanReplayTheSameRequestKeySeparately() throws Exception {
        long id = jdbc.sql("""
            SELECT GREATEST((SELECT COALESCE(MAX(id),0) FROM app_user),
                            (SELECT COALESCE(MAX(id),0) FROM patient_account)) + 1
            """).query(Long.class).single();
        String username = "shared_" + key().substring(0, 8);
        String phone = newPhone();
        jdbc.sql("""
            INSERT INTO app_user(id,username,password_hash,display_name,role)
            SELECT ?,?,password_hash,'同编号员工','FRONT_DESK' FROM app_user WHERE username='front1'
            """).params(id, username).update();
        jdbc.sql("""
            INSERT INTO patient_account(id,phone,name,password_hash,created_at)
            SELECT ?,?,'同编号患者',password_hash,? FROM app_user WHERE username=?
            """).params(id, phone, LocalDateTime.now(clock).withNano(0), username).update();

        String front = token(username);
        String patient = api("POST", "/api/patient-auth/login", null, Map.of("phone", phone, "password", "123456"), 200)
            .get("token").asString();
        var patientFile = Map.of("name", "同编号档案" + key().substring(0, 8), "birthDate", "1992-01-01",
            "phone", "13988890001", "billingAddress", "测试地址", "emergencyName", "家属",
            "emergencyPhone", "13988890002", "allergyIds", List.of());

        String sharedKey = key();
        var employeeResult = api("POST", "/api/patients", front, patientFile, 201, sharedKey);
        var patientResult = api("POST", PATIENT_REQUESTS, patient, intent(), 201, sharedKey);
        assertThat(api("POST", "/api/patients", front, patientFile, 201, sharedKey)).isEqualTo(employeeResult);
        assertThat(api("POST", PATIENT_REQUESTS, patient, intent(), 201, sharedKey)).isEqualTo(patientResult);
        var storedTypes = jdbc.sql("SELECT account_type FROM request_record WHERE request_id=? AND user_id=? ORDER BY account_type")
            .params(sharedKey, id).query(String.class).list();
        assertThat(storedTypes).containsExactly("EMPLOYEE", "PATIENT");
    }

    private String newPatientToken() throws Exception {
        return api("POST", "/api/patient-auth/register", null, Map.of(
            "phone", newPhone(), "name", "转预约测试", "code", "123456", "password", "Test-pass_6"), 201).get("token").asString();
    }

    private Map<String,Object> intent() {
        long clinic = jdbc.sql("SELECT id FROM clinic WHERE name='天河诊所'").query(Long.class).single();
        return Map.of("clinicId", clinic, "preferredDate", LocalDate.now(clock).plusDays(1).toString(),
            "timeSlot", "MORNING", "itemIds", List.of(), "note", "转预约测试");
    }

    private Map<String,Object> booking() {
        long patient = jdbc.sql("SELECT id FROM patient WHERE account_no='P000001'").query(Long.class).single();
        long clinic = jdbc.sql("SELECT id FROM clinic WHERE name='越秀诊所'").query(Long.class).single();
        long dentist = jdbc.sql("SELECT id FROM dentist WHERE employee_no='D001'").query(Long.class).single();
        long item = jdbc.sql("SELECT id FROM treatment_item WHERE name='口腔检查'").query(Long.class).single();
        String slot = LocalDateTime.now(clock).plusDays(3)
            .plusSeconds(ThreadLocalRandom.current().nextLong(1, 365L * 86400)).format(TIME);
        return new LinkedHashMap<>(Map.of("patientId", patient, "clinicId", clinic, "dentistId", dentist,
            "scheduledAt", slot, "itemIds", List.of(item)));
    }

    private JsonNode log(String action, String type, long id) {
        var raw = jdbc.sql("SELECT detail FROM operation_log WHERE action=? AND target_type=? AND target_id=?")
            .params(action, type, id).query(String.class).single();
        return json.readTree(raw);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static String newPhone() {
        return String.format(Locale.ROOT, "1%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    }

    private static void assertState(JsonNode error) {
        assertThat(error.get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        assertThat(error.get("message").asString()).isEqualTo("该预约申请已被处理");
    }
}
