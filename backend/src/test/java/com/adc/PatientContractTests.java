package com.adc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

class PatientContractTests extends ContractHttp {
    private static final String PATIENTS = "/api/patients";
    @Autowired JdbcClient jdbc;

    private Map<String,Object> form() {
        return new HashMap<>(Map.of("name", UUID.randomUUID().toString(), "birthDate", "1995-04-15",
                "phone", "13911112222", "billingAddress", "广州天河", "emergencyName", "张芳",
                "emergencyPhone", "13911113333", "allergyIds", List.of()));
    }

    @Test void detectsEitherDuplicateKeyAndAllowsFrontDeskConfirmation() throws Exception {
        String front = token("front1");
        var form = form();
        form.put("accountNo", "P000000");
        form.put("allergyVerifiedAt", "2026-10-10T09:30:00");
        var first = api("POST", PATIENTS, front, form, 201);
        String account = first.get("accountNo").asString();
        assertThat(account).matches("P[0-9]{6}").isNotEqualTo("P000000");
        assertThat(first.get("allergyVerifiedAt").isNull()).isTrue();
        form.put("phone", "13911114444");
        var duplicate = api("POST", PATIENTS, front, form, 409);
        assertThat(duplicate.get("code").asString()).isEqualTo("CONFLICT");
        var candidate = duplicate.get("current").get(0);
        assertThat(candidate.propertyNames()).containsExactlyInAnyOrder("id","accountNo","name","birthDate","phone");
        assertThat(candidate.get("id")).isEqualTo(first.get("id"));
        form.put("phone", first.get("phone").asString());
        form.put("birthDate", "1996-05-16");
        api("POST", PATIENTS, front, form, 409);
        form.put("confirmNotDuplicate", true);
        var second = api("POST", PATIENTS, front, form, 201);
        assertThat(second.get("accountNo").asString()).isNotEqualTo(account);
        String dentist = token("dentist1");
        var page = api("GET", PATIENTS + "?size=1&page=1&keyword=" + form.get("name"), dentist, null, 200);
        assertThat(page.get("total").asInt()).isEqualTo(2);
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("id")).isEqualTo(second.get("id"));
        assertThat(api("GET", PATIENTS + "?keyword=" + account, dentist, null, 200)
                .get("items").get(0)).isEqualTo(candidate);
        api("POST", PATIENTS, dentist, form, 403);
        api("GET", PATIENTS, token("hq1"), null, 403);
        form.put("birthDate","2000-01-16");
        String searchPhone = "139" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000000));
        form.put("phone",searchPhone);
        form.remove("confirmNotDuplicate");
        var sameName = api("POST", PATIENTS, front, form, 201);
        assertThat(api("GET", PATIENTS + "?keyword=" + searchPhone.substring(1), dentist, null, 200)
            .get("items").values()).anyMatch(row -> row.get("id").equals(sameName.get("id")));
    }

    @Test void requiresNonnegativePageAndPositivePageSize() throws Exception {
        String front = token("front1");
        var pageError = api("GET", PATIENTS + "?page=-1", front, null, 400);
        assertThat(pageError.get("fieldErrors").get(0).get("field").asString()).isEqualTo("page");
        var sizeError = api("GET", PATIENTS + "?size=0", front, null, 400);
        assertThat(sizeError.get("fieldErrors").get(0).get("field").asString()).isEqualTo("size");
        api("GET", PATIENTS + "?size=101", front, null, 200);
    }

    @Test void allergyOnlyUpdateIncrementsVersionAndRejectsStaleOrInvalidChanges() throws Exception {
        String front = token("front1");
        var form = form();
        var created = api("POST", PATIENTS, front, form, 201);
        String path = PATIENTS + "/" + created.get("id").asLong();
        // 只在本测试自己的档案上模拟一次已完成的牙医核实。
        jdbc.sql("UPDATE patient SET allergy_verified_at = '2026-07-15 11:10:00' WHERE id = ?")
                .param(created.get("id").asLong()).update();
        var options = api("GET", "/api/options/allergies", front, null, 200);
        long allergy = options.get(0).get("id").asLong();
        form.put("allergyIds", List.of(allergy, allergy));
        form.put("allergyVerifiedAt", "2026-09-15T11:10:00");
        form.put("version", created.get("version").asInt());
        var updated = api("PUT", path, front, form, 200);
        assertThat(updated.get("version").asInt()).isEqualTo(created.get("version").asInt() + 1);
        assertThat(updated.get("allergies").size()).isEqualTo(1);
        assertThat(updated.get("allergies").get(0)).isEqualTo(options.get(0));
        assertThat(updated.get("allergyVerifiedAt").asString()).isEqualTo("2026-07-15T11:10:00");
        var stale = api("PUT", path, front, form, 409);
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current")).isEqualTo(updated);
        form.put("allergyIds", List.of(Long.MAX_VALUE));
        form.put("version", updated.get("version").asInt());
        var invalid = api("PUT", path, front, form, 400);
        assertThat(invalid.get("fieldErrors").get(0).get("field").asString()).isEqualTo("allergyIds");
        String dentist = token("dentist1");
        assertThat(api("GET", path, dentist, null, 200)).isEqualTo(updated);
        api("PUT", path, dentist, form, 403);
        api("GET", PATIENTS + "/" + Long.MAX_VALUE, dentist, null, 404);
    }

    private List<HttpResponse<String>> concurrentRegistrations(String front, Map<String,Object> form) throws Exception {
        String body = json.writeValueAsString(form);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpResponse<String>> register = () -> {
                start.await();
                return send("POST", PATIENTS, front, body, UUID.randomUUID().toString());
            };
            var first = executor.submit(register);
            var second = executor.submit(register);
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void concurrentRegistrationsSerializeDuplicateChecksAndNumberAllocation() throws Exception {
        String front = token("front1");
        var form = form();
        var responses = concurrentRegistrations(front, form);
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        var conflict = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
        assertThat(json.readTree(conflict.body()).get("current").size()).isEqualTo(1);
        assertThat(api("GET", PATIENTS + "?keyword=" + form.get("name"), front, null, 200)
                .get("total").asInt()).isEqualTo(1);

        form.put("confirmNotDuplicate", true);
        var confirmed = concurrentRegistrations(front, form);
        assertThat(confirmed).extracting(HttpResponse::statusCode).containsOnly(201);
        String accountA = json.readTree(confirmed.get(0).body()).get("accountNo").asString();
        String accountB = json.readTree(confirmed.get(1).body()).get("accountNo").asString();
        assertThat(accountA).matches("P[0-9]{6}").isNotEqualTo(accountB);
        assertThat(accountB).matches("P[0-9]{6}");
        assertThat(api("GET", PATIENTS + "?keyword=" + form.get("name"), front, null, 200)
                .get("total").asInt()).isEqualTo(3);
    }
}
