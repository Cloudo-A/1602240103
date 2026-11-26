package com.adc;

import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class StatsContractTests extends ContractHttp {
    private static final String CANCEL = "/api/stats/cancellations";
    private static final String DEMAND = "/api/stats/demand-qualification";
    private static final String DAY = "?from=2004-02-29&to=2004-02-29";
    @Autowired JdbcClient jdbc;
    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private final String tag = UUID.randomUUID().toString().substring(0,12);

    @AfterEach void removeTestRows() { cleanup.forEach(Runnable::run); }

    private long insert(String table, String columns, String values, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("insert into "+table+" ("+columns+") values ("+values+")").params(args).update(keys,"id");
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        cleanup.push(() -> jdbc.sql("delete from "+table+" where id=?").param(id).update());
        return id;
    }

    private long clinic(String suffix, boolean active) {
        return insert("clinic","name,postal_address,manager_name,phone,email,office_area,active",
            "?,'广州','主管','100','hq@adc.example',100,?",suffix+tag,active);
    }

    private long dentist(String employee, boolean active) {
        return insert("dentist","employee_no,name,birth_date,home_address,phone,email,active",
            "?,?,'1980-01-01','广州','100','d@adc.example',?",employee,employee,active);
    }

    private void holding(long dentist, long qualification, boolean verified) {
        jdbc.sql("insert into qualification_holding(dentist_id,qualification_id,verified_at) values (?,?,?)")
            .params(dentist,qualification,verified ? "2000-01-01 10:00:00" : null).update();
        cleanup.push(() -> jdbc.sql("delete from qualification_holding where dentist_id=? and qualification_id=?")
            .params(dentist,qualification).update());
    }

    private long appointment(long patient, long doctor, long clinic, String at, boolean arrived, boolean cancelled, long item) {
        var id = insert("appointment","patient_id,dentist_id,clinic_id,scheduled_at,arrived,cancelled_at,cancelled_by",
            "?,?,?,?,?,?,?",patient,doctor,clinic,at,arrived,cancelled ? at : null,cancelled ? "测试前台" : null);
        jdbc.sql("insert into appointment_item(appointment_id,item_id) values (?,?)").params(id,item).update();
        cleanup.push(() -> jdbc.sql("delete from appointment_item where appointment_id=?").param(id).update());
        return id;
    }

    private void treated(long booked, long clinic, long doctor, long item, String at, String quantity) {
        var visit = insert("visit","appointment_id,clinic_id,dentist_id,arrived_at,registered_at,confirmed_at","?,?,?,?,?,?",
            booked,clinic,doctor,at,at,at);
        insert("treatment","visit_id,item_id,quantity,unit_price,item_name,unit_name","?,?,?,10,'测试','毫升'",visit,item,quantity);
    }

    private JsonNode byId(JsonNode array, String key, long id) {
        return array.values().stream().filter(v -> v.get(key).asLong()==id).findFirst().orElseThrow();
    }

    @Test void usesAppointmentDateForCancellationsAndIntentButVisitDateAndActualClinicForTreatment() throws Exception {
        var hq = token("hq1");
        long clinicA = clinic("预约诊所",true);
        long clinicB = clinic("实际诊所",false);
        long doctor = dentist("S"+tag,true);
        long patient = insert("patient","account_no,name,birth_date,billing_address,phone,emergency_name,emergency_phone",
            "?,?,'1981-01-01','地址','100','联系人','101'","S"+tag,"测试"+tag);
        long item = jdbc.sql("select id from treatment_item where name='局部麻醉'").query(Long.class).single();
        long first = appointment(patient,doctor,clinicA,"2004-02-29 00:00:00",false,false,item);
        long middle = appointment(patient,doctor,clinicA,"2004-02-29 12:00:00",false,true,item);
        long last = appointment(patient,doctor,clinicA,"2004-02-29 23:59:59",true,false,item);
        treated(last,clinicA,doctor,item,"2004-03-01 00:00:00","2.5");
        long outside = appointment(patient,doctor,clinicA,"2004-02-28 23:59:59",true,false,item);
        treated(outside,clinicB,doctor,item,"2004-02-29 00:00:00","1.5");
        String filter = "&clinicIds="+clinicB+","+clinicA;
        var summary = api("GET",CANCEL+DAY+filter,hq,null,200);
        assertThat(summary.get("periodEnded").asBoolean()).isTrue();
        assertThat(summary.get("generatedAt").asString()).hasSize(19);
        var rows = summary.get("rows");
        assertThat(rows.size()).isEqualTo(2);
        var a = rows.get(0);
        assertThat(a.get("clinicId").asLong()).isEqualTo(clinicA);
        assertThat(a.get("total").asLong()).isEqualTo(3);
        assertThat(a.get("cancelled").asLong()).isOne();
        assertThat(a.get("pendingVerify").asLong()).isOne();
        assertThat(a.get("rate").decimalValue()).isEqualByComparingTo("0.3333");
        var b = rows.get(1);
        assertThat(b.get("clinicId").asLong()).isEqualTo(clinicB);
        assertThat(b.get("total").asLong()).isZero();
        assertThat(b.get("rate").isNull()).isTrue();
        assertThat(byId(api("GET",CANCEL+DAY,hq,null,200).get("rows"),"clinicId",clinicB)).isEqualTo(b);
        var detail = api("GET",CANCEL+"/details"+DAY+"&clinicId="+clinicA,hq,null,200);
        assertThat(detail.values()).extracting(v -> v.get("id").asLong()).containsExactly(first,middle,last);
        for (var view : detail) assertThat(view).isEqualTo(api("GET","/api/appointments/"+view.get("id").asLong(),hq,null,200));
        var cells = api("GET",DEMAND+DAY+filter,hq,null,200).get("demand");
        assertThat(cells.size()).isEqualTo(2);
        assertThat(cells.get(0).get("intendedCount").asLong()).isEqualTo(2);
        assertThat(cells.get(0).get("actualQuantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(cells.get(1).get("clinicId").asLong()).isEqualTo(clinicB);
        assertThat(cells.get(1).get("intendedCount").asLong()).isZero();
        assertThat(cells.get(1).get("actualQuantity").decimalValue()).isEqualByComparingTo("1.5");
    }

    @Test void reportsEmptyAndVerifiedQualificationsAndEveryPendingActiveHolding() throws Exception {
        var hq = token("hq1");
        long empty = insert("qualification","name","?","待确认"+tag);
        long verified = insert("qualification","name","?","已核实"+tag);
        String active = "A"+tag, pending = "P"+tag, inactive = "I"+tag;
        long first = dentist(active,true), second = dentist(pending,true), third = dentist(inactive,false);
        holding(first,verified,true);
        holding(first,empty,false);
        holding(second,empty,false);
        holding(third,empty,true);
        var report = api("GET",DEMAND+DAY,hq,null,200);
        var filtered = api("GET",DEMAND+DAY+"&clinicIds=9223372036854775807",hq,null,200);
        assertThat(filtered.get("demand").size()).isZero();
        assertThat(filtered.get("qualifications")).isEqualTo(report.get("qualifications"));
        assertThat(filtered.get("incomplete")).isEqualTo(report.get("incomplete"));
        var blank = byId(report.get("qualifications"),"qualificationId",empty);
        assertThat(blank.get("verifiedCount").asInt()).isZero();
        assertThat(blank.get("dentists").size()).isZero();
        var yes = byId(report.get("qualifications"),"qualificationId",verified);
        assertThat(yes.get("verifiedCount").asInt()).isOne();
        assertThat(yes.get("dentists").get(0).get("employeeNo").asString()).isEqualTo(active);
        var issues = report.get("incomplete").values();
        assertThat(issues.stream().filter(i -> pending.equals(i.get("employeeNo").asString())).map(i -> i.get("issue").asString()))
            .containsExactly("待确认"+tag+"资质待核实","暂无已核实资质");
        assertThat(issues.stream().filter(i -> active.equals(i.get("employeeNo").asString())).map(i -> i.get("issue").asString()))
            .containsExactly("待确认"+tag+"资质待核实");
        assertThat(issues).noneMatch(i -> inactive.equals(i.get("employeeNo").asString()));
    }

    @Test void validatesPeriodAndEnforcesHeadquartersAccess() throws Exception {
        var hq = token("hq1");
        var front = token("front1");
        var doctor = token("dentist1");
        for (String route : List.of(CANCEL, CANCEL+"/details", DEMAND)) {
            String path = route+DAY+(route.endsWith("details") ? "&clinicId=1" : "");
            assertThat(api("GET",path,front,null,403).get("code").asString()).isEqualTo("FORBIDDEN");
            api("GET",path,doctor,null,403);
            var invalid = api("GET",route+"?from=2026-11-01&to=2026-10-31",hq,null,400);
            assertThat(invalid.get("fieldErrors").get(0).get("field").asString()).isEqualTo("from");
            assertThat(api("GET",route,hq,null,400).get("fieldErrors").size()).isEqualTo(2);
        }
        assertThat(api("GET",CANCEL+"/details"+DAY,hq,null,400).get("fieldErrors").get(0).get("field").asString()).isEqualTo("clinicId");
    }
}
