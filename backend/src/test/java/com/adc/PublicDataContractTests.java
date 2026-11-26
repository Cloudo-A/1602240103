package com.adc;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class PublicDataContractTests extends ContractHttp {
    @Autowired Clock clock;

    @Test void anonymousCatalogueProjectsOnlyPublicFieldsAndCurrentPrices() throws Exception {
        String key = UUID.randomUUID().toString().substring(0,12);
        String hq = token("hq1");
        LocalDate today = LocalDate.now(clock);
        var clinic = new HashMap<String,Object>(Map.of("name","停用门店"+key,"postalAddress","测试地址",
            "managerName","王老师","phone","020-33330000","email","public@adc.example","officeArea",100));
        var savedClinic = api("POST","/api/clinics",hq,clinic,201);
        clinic.put("active",false);
        clinic.put("version",0);
        api("PUT","/api/clinics/"+savedClinic.get("id").asLong(),hq,clinic,200);
        var clinics = api("GET","/api/public/clinics",null,null,200);
        assertThat(clinics.values()).isNotEmpty().allSatisfy(c -> assertThat(c.propertyNames())
            .containsExactlyInAnyOrder("id","name","postalAddress","phone"));
        assertThat(clinics.values()).extracting(c -> c.get("id").asLong()).isSorted()
            .doesNotContain(savedClinic.get("id").asLong());

        var dentist = new HashMap<String,Object>(Map.of("employeeNo","X"+key,"name","测试牙医","birthDate","1982-02-02",
            "homeAddress","保密地址","phone","13911112222","email","private@adc.example","qualifications",List.of()));
        var savedDentist = api("POST","/api/dentists",hq,dentist,201);
        long dentistId = savedDentist.get("id").asLong();
        var dentists = api("GET","/api/public/dentists",null,null,200);
        assertThat(dentists.values()).allSatisfy(d -> assertThat(d.propertyNames())
            .containsExactlyInAnyOrder("id","name","employeeNo","qualifications"));
        assertThat(dentists.values()).anySatisfy(d -> {
            assertThat(d.get("id").asLong()).isEqualTo(dentistId);
            assertThat(d.get("qualifications").size()).isZero();
        });
        var holdings = new ArrayList<Map<String,Object>>();
        for (String label : List.of("Z","B","A")) {
            var qualification = api("POST","/api/qualifications",hq,Map.of("name",label+key),201);
            Map<String,Object> holding = new HashMap<>(Map.of("qualificationId",qualification.get("id").asLong()));
            if (!label.equals("Z")) holding.put("verifiedAt","2025-01-01T12:00:00");
            holdings.add(holding);
        }
        dentist.put("qualifications",holdings);
        dentist.put("version",0);
        dentist.put("active",true);
        api("PUT","/api/dentists/"+dentistId,hq,dentist,200);
        assertThat(api("GET","/api/public/dentists",null,null,200).values()).anySatisfy(d -> {
            assertThat(d.get("id").asLong()).isEqualTo(dentistId);
            assertThat(d.get("qualifications").values()).extracting(q -> q.asString()).containsExactly("A"+key,"B"+key);
        });
        dentist.put("version",1);
        dentist.put("active",false);
        api("PUT","/api/dentists/"+dentistId,hq,dentist,200);
        assertThat(api("GET","/api/public/dentists",null,null,200).values())
            .noneMatch(d -> d.get("id").asLong()==dentistId);

        var item = api("POST","/api/treatment-items",hq,Map.of("name","价格"+key,"standardUnit","次","quantityScale",0,
            "firstPrice",Map.of("effectiveFrom",today.plusDays(1).toString(),"unitPrice",99)),201);
        long itemId = item.get("id").asLong();
        var path = "/api/treatment-items/"+itemId;
        assertThat(api("GET","/api/public/treatment-items",null,null,200).values()).noneMatch(t -> t.get("id").asLong()==itemId);
        api("POST",path+"/prices",hq,Map.of("effectiveFrom",today.minusDays(3).toString(),"unitPrice",2),200);
        var priced = api("POST",path+"/prices",hq,Map.of("effectiveFrom",today.toString(),"unitPrice",13.25),200);
        var treatments = api("GET","/api/public/treatment-items",null,null,200);
        assertThat(treatments.values()).allSatisfy(t -> assertThat(t.propertyNames())
            .containsExactlyInAnyOrder("id","name","standardUnit","unitPrice"));
        assertThat(treatments.values()).extracting(t -> t.get("id").asLong()).isSorted();
        assertThat(treatments.values()).anySatisfy(t -> {
            assertThat(t.get("id").asLong()).isEqualTo(itemId);
            assertThat(t.get("unitPrice").decimalValue()).isEqualByComparingTo("13.25");
        });
        api("PUT",path,hq,Map.of("name","价格"+key,"standardUnit","次","quantityScale",0,
            "active",false,"version",priced.get("version").asInt()),200);
        assertThat(api("GET","/api/public/treatment-items",null,null,200).values()).noneMatch(t -> t.get("id").asLong()==itemId);
    }
}
