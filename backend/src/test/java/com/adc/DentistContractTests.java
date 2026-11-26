package com.adc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DentistContractTests extends ContractHttp {
    @Test void replacesEntireQualificationListAndReturnsCurrentOnStaleWrites() throws Exception {
        var hq = token("hq1");
        var name = Map.of("name", "资质-" + UUID.randomUUID());
        var q = api("POST", "/api/qualifications", hq, name, 201);
        var qId = q.get("id").asLong();
        assertThat(q.get("name").asString()).isEqualTo(name.get("name"));
        assertThat(api("POST", "/api/qualifications", hq, name, 409).get("code").asString()).isEqualTo("CONFLICT");
        var other = api("POST", "/api/qualifications", hq, Map.of("name", "资质-" + UUID.randomUUID()), 201);
        var data = new HashMap<String,Object>(Map.of(
            "employeeNo", "D" + UUID.randomUUID().toString().substring(0, 18), "name", "陈舟",
            "birthDate", "1985-03-12", "homeAddress", "广州", "phone", "13800001234", "email", "chen@adc.example",
            "qualifications", List.of(Map.of("qualificationId", qId), Map.of("qualificationId", other.get("id").asLong()))));
        var created = api("POST", "/api/dentists", hq, data, 201);
        var path = "/api/dentists/" + created.get("id").asLong();
        assertThat(created.get("qualifications").size()).isEqualTo(2);
        assertThat(created.get("qualifications").get(0).get("verifiedAt").isNull()).isTrue();
        assertThat(api("GET", path, hq, null, 200)).isEqualTo(created);
        assertThat(api("POST", "/api/dentists", hq, data, 409).get("code").asString()).isEqualTo("CONFLICT");

        data.put("active", true);
        data.put("version", 0);
        data.put("qualifications", List.of(Map.of("qualificationId", qId, "verifiedAt", "2026-01-05T10:00:00")));
        var updated = api("PUT", path, hq, data, 200);
        assertThat(updated.get("qualifications").size()).isOne();
        var holding = updated.get("qualifications").get(0);
        assertThat(holding.get("name")).isEqualTo(q.get("name"));
        assertThat(holding.get("verifiedAt").asString()).isEqualTo("2026-01-05T10:00:00");
        assertThat(updated.get("version").asInt()).isOne();
        var stale = api("PUT", path, hq, data, 409);
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current")).isEqualTo(api("GET", path, hq, null, 200));

        data.put("version", 1);
        data.put("active", false);
        data.put("qualifications", List.of(Map.of("qualificationId", Long.MAX_VALUE)));
        assertThat(api("PUT", path, hq, data, 400).get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(api("GET", path, hq, null, 200)).isEqualTo(updated);
        data.put("qualifications", List.of());
        var disabled = api("PUT", path, hq, data, 200);
        assertThat(disabled.get("version").asInt()).isEqualTo(2);
        assertThat(disabled.get("qualifications").size()).isZero();
        assertThat(api("GET", "/api/dentists", hq, null, 200).values()).contains(disabled);
        assertThat(api("GET", path, token("dentist1"), null, 403).get("code").asString()).isEqualTo("FORBIDDEN");
    }
}
