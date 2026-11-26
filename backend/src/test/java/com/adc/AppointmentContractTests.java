package com.adc;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

@Import(AppointmentContractTests.TestTime.class)
class AppointmentContractTests extends ContractHttp {
    private static final String API = "/api/appointments";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @TestConfiguration
    static class TestTime {
        @Bean @Primary Clock appointmentTestClock() {
            var fixed = LocalDate.now(BEIJING).atTime(12, 34, 45).atZone(BEIJING).toInstant();
            return Clock.fixed(fixed, BEIJING);
        }
    }

    private long key(String sql, String value) {
        return jdbc.sql(sql).param(value).query(Long.class).single();
    }

    private Map<String,Object> booking() {
        var time = LocalDateTime.now(clock).plusYears(1)
            .plusSeconds(ThreadLocalRandom.current().nextLong(1, 315_360_000L));
        return new HashMap<>(Map.of(
            "patientId", key("select id from patient where account_no=?", "P000001"),
            "clinicId", key("select id from clinic where name=?", "天河诊所"),
            "dentistId", key("select id from dentist where employee_no=?", "D001"),
            "scheduledAt", time.format(TIME),
            "itemIds", List.of(key("select id from treatment_item where name=?", "洁牙"))));
    }

    @Test void readCheckCancelAndReuseTheReleasedSlot() throws Exception {
        var front = token("front1");
        var body = booking();
        var check = api("POST", API+"/check", front, body, 200);
        assertThat(check.get("ok").asBoolean()).isTrue();
        assertThat(check.get("problems").size()).isZero();
        var rid = UUID.randomUUID().toString();
        var appt = api("POST", API, front, body, 201, rid);
        assertThat(api("POST", API, front, body, 201, rid)).isEqualTo(appt);
        var path = API + "/" + appt.get("id").asLong();
        assertThat(appt.get("status").asString()).isEqualTo("VALID");
        assertThat(appt.get("items").get(0).get("name").asString()).isEqualTo("洁牙");
        assertThat(appt.get("scheduledAt").asString()).isEqualTo(body.get("scheduledAt"));
        var collision = api("POST", API, front, body, 409);
        String patientConflict = "患者" + appt.get("patient").get("name").asString() + "在该时间已有预约";
        String dentistConflict = appt.get("dentist").get("name").asString() + "医生在该时间已有预约";
        assertThat(collision.get("message").asString()).contains(patientConflict, dentistConflict);
        assertThat(api("POST", API+"/check", front, body, 200).get("problems").values())
            .extracting(problem -> problem.asString()).containsExactly(patientConflict, dentistConflict);
        var patientOnly = new HashMap<>(body);
        patientOnly.put("dentistId", key("select id from dentist where employee_no=?", "D002"));
        assertThat(api("POST", API, front, patientOnly, 409).get("message").asString()).isEqualTo(patientConflict);
        var doctor = token("dentist1");
        assertThat(api("GET", path, doctor, null, 200)).isEqualTo(appt);
        api("GET", path, token("dentist2"), null, 403);
        var day = body.get("scheduledAt").toString().substring(0,10);
        var filter = "?from="+day+"&to="+day+"&clinicId="+body.get("clinicId")+"&patientId="+body.get("patientId")
            +"&dentistId="+body.get("dentistId")+"&status=ARRIVED,VALID";
        assertThat(api("GET", API+filter, token("hq1"), null, 200).values()).contains(appt);
        assertThat(api("GET", API+filter, token("dentist2"), null, 200).values()).doesNotContain(appt);
        assertThat(api("GET", API+"?patientId="+body.get("patientId"), front, null, 200).values()).contains(appt);
        assertThat(api("GET", API, front, null, 200).values()).doesNotContain(appt);
        api("POST", path+"/cancel", doctor, Map.of("version",0), 403);
        var stale = api("POST", path+"/cancel", front, Map.of("version",1), 409);
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current")).isEqualTo(appt);
        var cancelled = api("POST", path+"/cancel", front, Map.of("version",0), 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo("王丽");
        assertThat(cancelled.get("version").asInt()).isOne();
        assertThat(api("POST", path+"/cancel", front, Map.of("version",0), 200)).isEqualTo(cancelled);
        assertThat(api("POST", path+"/arrive", front, Map.of("version",1), 409).get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        api("POST", API, front, body, 201);
    }

    @Test void collectsInvalidFieldsWithoutMakingAnAppointment() throws Exception {
        var front = token("front1");
        var body = Map.of("scheduledAt","2020-01-01T09:00:00","patientId",-1,"clinicId",-1,"dentistId",-1,"itemIds",List.of(-1));
        var check = api("POST", API+"/check", front, body, 200);
        assertThat(check.get("ok").asBoolean()).isFalse();
        assertThat(check.get("problems").size()).isEqualTo(5);
        var fields = api("POST", API, front, body, 400).get("fieldErrors");
        assertThat(fields.values()).extracting(f -> f.get("field").asString())
            .containsExactlyInAnyOrder("scheduledAt","patientId","clinicId","dentistId","itemIds");
        api("POST", API+"/check", token("hq1"), body, 403);
    }

    @Test void arrivalIsAtomicAndRetainsBookedDoctorAndClinic() throws Exception {
        var front = token("front1");
        var body = booking();
        var appt = api("POST", API, front, body, 201);
        var path = API + "/" + appt.get("id").asLong();
        var actualDentist = key("select id from dentist where employee_no=?", "D002");
        var actualClinic = key("select id from clinic where name=?", "越秀诊所");
        var arrive = new HashMap<String,Object>(Map.of("version",0, "arrivedAt",body.get("scheduledAt"),
            "dentistId",actualDentist, "clinicId",actualClinic));
        assertThat(api("POST", path+"/arrive", front, arrive, 400).get("fieldErrors").get(0).get("field").asString()).isEqualTo("arrivedAt");
        arrive.put("arrivedAt", LocalDateTime.now(clock).minusMinutes(1).format(TIME));
        var response = api("POST", path+"/arrive", front, arrive, 200);
        var current = response.get("appointment");
        assertThat(current.get("status").asString()).isEqualTo("ARRIVED");
        assertThat(current.get("version").asInt()).isOne();
        assertThat(current.get("dentist")).isEqualTo(appt.get("dentist"));
        assertThat(current.get("clinic")).isEqualTo(appt.get("clinic"));
        assertThat(api("GET", path, front, null, 200)).isEqualTo(current);
        assertThat(current.get("visitId")).isEqualTo(response.get("visitId"));
        assertThat(jdbc.sql("select dentist_id,clinic_id from visit where id=? and appointment_id=?")
            .params(response.get("visitId").asLong(), appt.get("id").asLong())
            .query((r,n) -> List.of(r.getLong(1),r.getLong(2))).single()).containsExactly(actualDentist,actualClinic);
        assertThat(api("POST", path+"/arrive", front, arrive, 200)).isEqualTo(response);
        assertThat(api("POST", path+"/cancel", front, Map.of("version",1), 409).get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
    }

    @Test void simultaneousRequestsCanOnlyReserveTheSameDentistOnce() throws Exception {
        var front = token("front1");
        var first = booking();
        var second = new HashMap<>(first);
        second.put("patientId", key("select id from patient where account_no=?", "P000002"));
        var ready = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var calls = List.of(first, second).stream().map(body -> pool.submit(() -> {
                ready.countDown();
                if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("未能同时开始请求");
                return send("POST", API, front, json.writeValueAsString(body), UUID.randomUUID().toString());
            })).toList();
            var one = calls.get(0).get(20, TimeUnit.SECONDS);
            var two = calls.get(1).get(20, TimeUnit.SECONDS);
            assertThat(List.of(one.statusCode(), two.statusCode())).containsExactlyInAnyOrder(201,409);
            var error = json.readTree((one.statusCode()==409 ? one : two).body());
            assertThat(error.get("code").asString()).isEqualTo("CONFLICT");
            String dentist = jdbc.sql("select name from dentist where id=?").param(first.get("dentistId")).query(String.class).single();
            assertThat(error.get("message").asString()).isEqualTo(dentist + "医生在该时间已有预约");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void acceptsTheCurrentMinuteAndKeepsSeparateSlotsForDifferentSeconds() throws Exception {
        var front = token("front1");
        var body = booking();
        var start = LocalDateTime.now(clock).withSecond(0);
        body.put("scheduledAt",start.minusSeconds(1).format(TIME));
        assertThat(api("POST",API+"/check",front,body,200).get("ok").asBoolean()).isFalse();
        assertThat(api("POST",API,front,body,400).get("fieldErrors").get(0).get("field").asString()).isEqualTo("scheduledAt");
        var paths = new ArrayList<String>();
        try {
            for (int second : List.of(0, 1)) {
                String scheduled = start.plusSeconds(second).format(TIME);
                body.put("scheduledAt",scheduled);
                assertThat(api("POST",API+"/check",front,body,200).get("ok").asBoolean()).isTrue();
                var created = api("POST",API,front,body,201);
                paths.add(API+"/"+created.get("id").asLong()+"/cancel");
                assertThat(created.get("scheduledAt").asString()).isEqualTo(scheduled);
            }
        } finally {
            for (String path : paths) api("POST",path,front,Map.of("version",0),200);
        }
    }

    @Test void overdueAppointmentRemainsPendingAndArrivalUsesItsBookedReferences() throws Exception {
        var front = token("front1");
        var body = booking();
        var appt = api("POST", API, front, body, 201);
        var id = appt.get("id").asLong();
        var past = LocalDateTime.parse((String) body.get("scheduledAt")).minusYears(30);
        jdbc.sql("update appointment set scheduled_at=? where id=?").params(past, id).update();
        var path = API + "/" + id;
        var pending = api("GET", path, front, null, 200);
        assertThat(pending.get("status").asString()).isEqualTo("PENDING_VERIFY");
        assertThat(api("GET", API+"?patientId="+body.get("patientId")+"&status=PENDING_VERIFY", front, null, 200).values())
            .contains(pending);
        var arrival = api("POST", path+"/arrive", front, Map.of("version",0,"arrivedAt",past.format(TIME)), 200);
        assertThat(jdbc.sql("select dentist_id,clinic_id from visit where id=?").param(arrival.get("visitId").asLong())
            .query((r,n) -> List.of(r.getLong(1),r.getLong(2))).single())
            .containsExactly((Long)body.get("dentistId"),(Long)body.get("clinicId"));
    }
}
