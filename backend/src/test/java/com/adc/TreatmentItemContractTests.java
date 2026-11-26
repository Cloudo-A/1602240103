package com.adc;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TreatmentItemContractTests extends ContractHttp {
    private static final String ITEMS = "/api/treatment-items";

    @Test void managesItemAndAppendOnlyPrices() throws Exception {
        String hq = token("hq1");
        String name = "项目-" + UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var first = Map.of("effectiveFrom", today.plusDays(1).toString(), "unitPrice", 80);
        var create = Map.of("name", name, "standardUnit", "次", "quantityScale", 0, "firstPrice", first);
        var item = api("POST", ITEMS, hq, create, 201);
        String path = ITEMS + "/" + item.get("id").asLong();
        assertThat(item.get("currentPrice").isNull()).isTrue();
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id","name","standardUnit",
            "quantityScale","active","version","currentPrice","prices");
        assertThat(api("POST", ITEMS, hq, create, 409).get("code").asString()).isEqualTo("CONFLICT");
        var todayPrice = Map.of("effectiveFrom", today.toString(), "unitPrice", 0);
        var priced = api("POST", path + "/prices", hq, todayPrice, 200);
        assertThat(priced.get("version").asInt()).isEqualTo(item.get("version").asInt() + 1);
        assertThat(priced.get("currentPrice").decimalValue()).isZero();
        assertThat(priced.get("prices").get(0).get("effectiveFrom").asString()).isEqualTo(today.toString());
        assertThat(priced.get("prices").get(1).get("effectiveFrom").asString()).isEqualTo(today.plusDays(1).toString());
        assertThat(api("POST", path + "/prices", hq, todayPrice, 409).get("code").asString()).isEqualTo("CONFLICT");
        var invalidPrice = Map.of("effectiveFrom", today.minusDays(1).toString(), "unitPrice", -1);
        var invalid = api("POST", path + "/prices", hq, invalidPrice, 400);
        assertThat(invalid.get("fieldErrors").get(0).get("field").asString()).isEqualTo("unitPrice");
        var stale = Map.of("name", name, "standardUnit", "颗", "quantityScale", 1,
            "active", false, "version", item.get("version").asInt());
        var changed = api("PUT", path, hq, stale, 409);
        assertThat(changed.get("code").asString()).isEqualTo("CHANGED");
        assertThat(changed.get("current")).isEqualTo(api("GET", path, hq, null, 200));
        var edit = Map.of("name", name, "standardUnit", "颗", "quantityScale", 1,
            "active", false, "version", priced.get("version").asInt());
        var updated = api("PUT", path, hq, edit, 200);
        assertThat(updated.get("active").asBoolean()).isFalse();
        assertThat(updated.get("quantityScale").asInt()).isEqualTo(1);
        assertThat(api("GET", ITEMS, hq, null, 200).values()).contains(updated);
        api("GET", path, token("dentist1"), null, 403);
        api("GET", ITEMS + "/9223372036854775807", hq, null, 404);
    }
}
