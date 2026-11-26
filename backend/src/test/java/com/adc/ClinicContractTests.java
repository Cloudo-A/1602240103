package com.adc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClinicContractTests extends ContractHttp {
    @Test void headquartersCanCreateEditAndDeactivateClinic() throws Exception {
        var hq = token("hq1");
        var data = new HashMap<String,Object>(Map.of(
            "name", "诊所-" + UUID.randomUUID(), "postalAddress", "天河路21号",
            "managerName", "李华", "phone", "020-12345678", "email", "office@adc.example", "officeArea", 120.50));
        var created = api("POST", "/api/clinics", hq, data, 201);
        var path = "/api/clinics/" + created.get("id").asLong();
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(api("GET", path, hq, null, 200)).isEqualTo(created);
        assertThat(api("POST", "/api/clinics", hq, data, 409).get("code").asString()).isEqualTo("CONFLICT");

        data.put("active", false);
        data.put("version", 0);
        var updated = api("PUT", path, hq, data, 200);
        assertThat(updated.get("active").asBoolean()).isFalse();
        assertThat(updated.get("version").asInt()).isEqualTo(1);
        assertThat(api("GET", "/api/clinics", hq, null, 200).values()).contains(updated);
        var stale = api("PUT", path, hq, data, 409);
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current")).isEqualTo(api("GET", path, hq, null, 200));

        data.put("version", 1);
        assertThat(api("PUT", path, hq, data, 200).get("version").asInt()).isEqualTo(2);
        data.put("officeArea", 0);
        var invalid = api("POST", "/api/clinics", hq, data, 400);
        assertThat(invalid.get("fieldErrors").get(0).get("field").asString()).isEqualTo("officeArea");
        assertThat(api("GET", path, token("front1"), null, 403).get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(api("GET", "/api/clinics/9223372036854775807", hq, null, 404).get("code").asString()).isEqualTo("NOT_FOUND");
    }
}
