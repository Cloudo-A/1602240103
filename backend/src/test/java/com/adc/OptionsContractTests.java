package com.adc;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OptionsContractTests extends ContractHttp {
    @Test void allRolesReadOptionsAndCanIncludeInactiveItems() throws Exception {
        String hq = token("hq1");
        String name = "选项-" + UUID.randomUUID();
        var create = Map.of("name", name, "standardUnit", "次", "quantityScale", 1,
            "firstPrice", Map.of("effectiveFrom", "2000-01-01", "unitPrice", 17.15));
        var item = api("POST", "/api/treatment-items", hq, create, 201);
        var edit = Map.of("name", name, "standardUnit", "次", "quantityScale", 1,
            "active", false, "version", item.get("version").asInt());
        api("PUT", "/api/treatment-items/" + item.get("id").asLong(), hq, edit, 200);
        for (String user : new String[]{"front1", "dentist1", "hq1"}) {
            String bearer = token(user);
            var clinics = api("GET", "/api/options/clinics", bearer, null, 200);
            assertThat(clinics.get(0).propertyNames()).containsExactlyInAnyOrder("id","name","active");
            var dentists = api("GET", "/api/options/dentists", bearer, null, 200);
            assertThat(dentists.get(0).propertyNames()).containsExactlyInAnyOrder("id","name","employeeNo",
                "active","verifiedQualifications");
            assertThat(dentists.values()).anySatisfy(dentist -> {
                assertThat(dentist.get("employeeNo").asString()).isEqualTo("D003");
                assertThat(dentist.get("verifiedQualifications").values()).extracting(q -> q.asString())
                    .contains("口腔全科").doesNotContain("口腔正畸");
            });
            for (String reference : new String[]{"allergies", "qualifications"}) {
                var rows = api("GET", "/api/options/" + reference, bearer, null, 200);
                assertThat(rows.get(0).propertyNames()).containsExactlyInAnyOrder("id","name");
            }
            var defaults = api("GET", "/api/options/treatment-items", bearer, null, 200);
            assertThat(defaults.values()).noneMatch(row -> row.get("name").asString().equals(name));
            var all = api("GET", "/api/options/treatment-items?activeOnly=false", bearer, null, 200);
            assertThat(all.values()).anySatisfy(row -> {
                assertThat(row.get("name").asString()).isEqualTo(name);
                assertThat(row.propertyNames()).containsExactlyInAnyOrder("id","name","standardUnit",
                    "quantityScale","currentPrice","active");
                assertThat(row.get("currentPrice").decimalValue()).isEqualByComparingTo("17.15");
            });
        }
    }
}
