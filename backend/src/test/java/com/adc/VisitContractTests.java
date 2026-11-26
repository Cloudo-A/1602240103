package com.adc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

class VisitContractTests extends ContractHttp {
    @Autowired JdbcClient jdbc;

    private record Fixture(long patientId, long itemId, long visitId, long dentistId) {
        String path() { return "/api/visits/" + visitId; }
        String medical() { return "/api/patients/" + patientId + "/medical-record"; }
    }

    private Fixture fixture(String front, String hq) throws Exception {
        String key = UUID.randomUUID().toString();
        var patient = api("POST","/api/patients",front,Map.of("name",key,"birthDate","1989-05-01",
            "billingAddress","广州","phone","13911113333","emergencyName","张芳","emergencyPhone","13911112222"),201);
        var item = api("POST","/api/treatment-items",hq,Map.of("name","治疗-"+key,"standardUnit","毫升",
            "quantityScale",1,"firstPrice",Map.of("effectiveFrom","2026-01-01","unitPrice",new BigDecimal("2.55"))),201);
        long patientId = patient.get("id").asLong();
        long itemId = item.get("id").asLong();
        long dentist = jdbc.sql("SELECT id FROM dentist WHERE employee_no='D001'").query(Long.class).single();
        long clinic = jdbc.sql("SELECT MIN(id) FROM clinic WHERE active=true").query(Long.class).single();
        var scheduled = LocalDateTime.of(2050,1,1,0,0).plusSeconds(ThreadLocalRandom.current().nextLong(1_000_000_000));
        // Directly establish a historical arrival; the appointments contract independently tests its HTTP workflow.
        jdbc.sql("INSERT INTO appointment(patient_id,dentist_id,clinic_id,scheduled_at,arrived) VALUES(?,?,?,?,true)")
            .params(patientId,dentist,clinic,scheduled).update();
        long appointment = jdbc.sql("SELECT id FROM appointment WHERE patient_id=?").param(patientId).query(Long.class).single();
        jdbc.sql("INSERT INTO appointment_item(appointment_id,item_id) VALUES(?,?)").params(appointment,itemId).update();
        jdbc.sql("""
            INSERT INTO visit(appointment_id,dentist_id,clinic_id,arrived_at,registered_at)
            VALUES(?,?,?,'2026-08-01 09:00:00','2026-08-01 09:01:00')
            """).params(appointment,dentist,clinic).update();
        long visit = jdbc.sql("SELECT id FROM visit WHERE appointment_id=?").param(appointment).query(Long.class).single();
        return new Fixture(patientId,itemId,visit,dentist);
    }

    @Test void pricesOnArrivalDateCorrectsBeforeBillingAndShowsSavedMedicalHistory() throws Exception {
        String front = token("front1"), hq = token("hq1"), doctor = token("dentist1"), other = token("dentist2");
        Fixture f = fixture(front,hq);
        String path = f.path();
        api("POST","/api/treatment-items/"+f.itemId+"/prices",hq,Map.of("effectiveFrom","2026-08-02","unitPrice",99),200);
        var detail = api("GET",path,front,null,200);
        assertThat(detail.get("status").asString()).isEqualTo("UNCONFIRMED");
        assertThat(detail.get("total").isNull()).isTrue();
        assertThat(detail.get("intendedItems").get(0).get("id").asLong()).isEqualTo(f.itemId);
        assertThat(api("GET",f.medical(),other,null,200).get("visits").size()).isZero();
        api("GET",f.medical(),front,null,403);
        api("GET",path,other,null,403);
        var line = Map.of("itemId",f.itemId,"quantity",new BigDecimal("0.5"));
        Map<String,Object> input = new HashMap<>(Map.of("problem","牙痛","allergyIds",List.of(),"version",0,"treatments",List.of(line)));
        api("POST",path+"/confirm",front,input,403);
        api("POST",path+"/confirm",other,input,403);
        api("POST",path+"/price-lines",other,input,403);
        api("POST",path+"/end-without-treatment",other,Map.of("version",0,"reason","改期"),403);

        var preview = api("POST",path+"/price-lines",doctor,Map.of("treatments",List.of(line,
            Map.of("itemId",f.itemId,"quantity","错误"),Map.of("itemId",Long.MAX_VALUE,"quantity",1))),200);
        assertThat(preview.get("problems").size()).isGreaterThanOrEqualTo(2);
        assertThat(preview.get("lines").size()).isOne();
        var priced = preview.get("lines").get(0);
        assertThat(priced.get("subtotal").decimalValue()).isEqualByComparingTo("1.28");
        assertThat(priced.get("priceEffectiveFrom").asString()).isEqualTo("2026-01-01");
        assertThat(api("GET",path,doctor,null,200)).isEqualTo(detail);

        var expensive = api("POST","/api/treatment-items",hq,Map.of("name","高价-"+UUID.randomUUID(),
            "standardUnit","次","quantityScale",0,"firstPrice",Map.of("effectiveFrom","2026-01-01","unitPrice",100000000)),201);
        var large = Map.of("itemId",expensive.get("id").asLong(),"quantity",100000000);
        input.put("treatments",List.of(large));
        assertThat(api("POST",path+"/price-lines",doctor,input,200).get("problems").size()).isOne();
        var overflow = api("POST",path+"/confirm",doctor,input,400);
        assertThat(overflow.get("fieldErrors").get(0).get("field").asString()).isEqualTo("treatments");

        input.put("treatments",List.of(Map.of("itemId",f.itemId,"quantity",new BigDecimal("0.55")),line));
        var invalid = api("POST",path+"/confirm",doctor,input,400);
        assertThat(invalid.get("fieldErrors").values()).extracting(e -> e.get("field").asString())
            .contains("treatments[0].quantity","treatments[1].itemId");
        input.put("treatments",List.of(line));
        input.remove("allergyIds");
        input.put("problem"," ");
        assertThat(api("POST",path+"/confirm",doctor,input,400).get("fieldErrors").values())
            .extracting(e -> e.get("field").asString()).contains("problem","allergyIds");
        input.put("allergyIds",List.of());
        input.put("problem","牙痛");
        var first = api("POST",path+"/confirm",doctor,input,200);
        assertThat(first.get("version").asInt()).isOne();
        assertThat(first.get("total").decimalValue()).isEqualByComparingTo("1.28");
        assertThat(first.get("treatments").get(0).get("priceEffectiveFrom").isNull()).isTrue();
        assertThat(first.get("patient").get("allergyVerifiedAt").isString()).isTrue();
        var stale = api("POST",path+"/confirm",doctor,input,409);
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current")).isEqualTo(first);
        api("POST","/api/treatment-items/"+f.itemId+"/prices",hq,Map.of("effectiveFrom","2026-07-01","unitPrice",4),200);
        assertThat(api("GET",path,hq,null,200).get("total").decimalValue()).isEqualByComparingTo("1.28");
        input.put("version",1);
        input.put("treatments",List.of(Map.of("itemId",f.itemId,"quantity",2)));
        var changed = api("POST",path+"/confirm",doctor,input,200);
        assertThat(changed.get("version").asInt()).isEqualTo(2);
        assertThat(changed.get("treatments").size()).isOne();
        assertThat(changed.get("total").decimalValue()).isEqualByComparingTo("8.00");
        var history = api("GET",f.medical(),other,null,200);
        assertThat(history.get("visits").get(0).get("treatments").get(0).propertyNames())
            .containsExactlyInAnyOrder("itemName","unitName","quantity","unitPrice","subtotal");
        assertThat(history.get("visits").get(0).get("status").asString()).isEqualTo("CONFIRMED");
        String filter="/api/visits?from=2026-08-01&to=2026-08-01&status=CONFIRMED,SETTLED&dentistId="+f.dentistId;
        assertThat(api("GET",filter,hq,null,200).values()).anyMatch(v -> v.get("id").asLong()==f.visitId);
        assertThat(api("GET",filter,other,null,200).values()).noneMatch(v -> v.get("id").asLong()==f.visitId);
        var slipPreview = api("POST",path+"/settlement-preview",front,null,200);
        api("POST",path+"/settlement",front,Map.of("checkToken",slipPreview.get("checkToken").asString()),201);
        input.put("version",2);
        var settled = api("POST",path+"/confirm",doctor,input,409);
        assertThat(settled.get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        assertThat(api("GET",f.medical(),doctor,null,200).get("visits").get(0).get("status").asString()).isEqualTo("SETTLED");
    }

    @Test void endingWithoutTreatmentIsFinalAndVisibleInHistory() throws Exception {
        String front=token("front1"), doctor=token("dentist1");
        Fixture f=fixture(front,token("hq1"));
        var reason=Map.of("version",0,"reason","需改期","problem","牙痛");
        api("POST",f.path()+"/end-without-treatment",doctor,Map.of("version",0,"reason"," "),400);
        var ended=api("POST",f.path()+"/end-without-treatment",doctor,reason,200);
        assertThat(ended.get("status").asString()).isEqualTo("NO_TREATMENT");
        assertThat(ended.get("total").isNull()).isTrue();
        assertThat(ended.get("treatments").size()).isZero();
        assertThat(api("GET",f.medical(),doctor,null,200).get("visits").get(0).get("status").asString()).isEqualTo("NO_TREATMENT");
        for (String operation : List.of("confirm","end-without-treatment")) {
            var denied=api("POST",f.path()+"/"+operation,doctor,Map.of("version",1),409);
            assertThat(denied.get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        }
    }
}
