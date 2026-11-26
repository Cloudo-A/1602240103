package com.adc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountContractTests extends ContractHttp {
    private static final String USERS = "/api/users";
    private static final String CHANGE = "/api/auth/change-password";
    private static final String INITIAL = "Test-pass_41";
    private static final String RESET = "Reset-pass_42";
    private static final String REPLACEMENT = "Personal-pass_43";
    private static final String BYTE_ERROR = "密码太长（最多 72 个字节，约 24 个汉字）";

    @Autowired JdbcClient jdbc;

    @Test void listsCreatesUpdatesAndRejectsInvalidBindingsOrVersions() throws Exception {
        String hq = token("hq1");
        var form = form("FRONT_DESK");
        var created = api("POST", USERS, hq, form, 201);
        long id = created.get("id").asLong();
        String path = USERS + "/" + id;
        assertAccount(created);
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("dentist").isNull()).isTrue();

        var duplicate = api("POST", USERS, hq, form, 409);
        assertThat(duplicate.get("code").asString()).isEqualTo("CONFLICT");
        assertThat(duplicate.get("current").isNull()).isTrue();

        long bound = dentistId("D001");
        var alreadyBound = form("DENTIST");
        alreadyBound.put("dentistId", bound);
        assertThat(api("POST", USERS, hq, alreadyBound, 409).get("code").asString()).isEqualTo("CONFLICT");
        var invalidFront = form("FRONT_DESK");
        invalidFront.put("dentistId", bound);
        assertField(api("POST", USERS, hq, invalidFront, 400), "dentistId");

        String employee = "QT-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.sql("""
            INSERT INTO dentist (employee_no,name,birth_date,home_address,phone,email,active)
            VALUES (?, '账号测试牙医','1990-01-01','测试地址','123456','test@example.test',true)
            """).param(employee).update();
        long dentist = dentistId(employee);
        var dentistForm = form("DENTIST");
        dentistForm.put("dentistId", dentist);
        var boundAccount = api("POST", USERS, hq, dentistForm, 201);
        assertThat(boundAccount.get("dentist").get("id").asLong()).isEqualTo(dentist);
        assertThat(boundAccount.get("dentist").get("employeeNo").asString()).isEqualTo(employee);

        var edit = new HashMap<String,Object>(Map.of("displayName", "账户更新", "version", 0, "active", true,
            "username", "ignore_other_name", "role", "HQ"));
        var updated = api("PUT", path, hq, edit, 200);
        assertThat(updated.get("role")).isEqualTo(created.get("role"));
        assertThat(updated.get("username")).isEqualTo(created.get("username"));
        assertThat(updated.get("version").asInt()).isEqualTo(1);
        var stale = api("PUT", path, hq, edit, 409);
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current")).isEqualTo(updated);
        var listed = api("GET", USERS, hq, null, 200).values();
        assertThat(listed).contains(updated, boundAccount);
        assertThat(listed).extracting(row -> row.get("id").asLong()).isSorted();

        long self = api("GET", "/api/auth/me", hq, null, 200).get("id").asLong();
        var admin = listed.stream().filter(row -> row.get("id").asLong() == self).findFirst().orElseThrow();
        var denied = api("PUT", USERS + "/" + self, hq, Map.of(
            "displayName", admin.get("displayName").asString(), "active", false, "version", admin.get("version").asInt()), 409);
        assertThat(denied.get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");

        for (String login : List.of("front1","dentist1")) {
            String restricted = token(login);
            assertThat(api("GET", USERS, restricted, null, 403).get("code").asString()).isEqualTo("FORBIDDEN");
            api("POST", USERS, restricted, form, 403);
            api("PUT", path, restricted, edit, 403);
            api("POST", path + "/reset-password", restricted, Map.of("newPassword", RESET), 403);
        }
    }

    @Test void deactivationAndResetRevokeEveryTokenAndCanBeAuditedWithoutSecrets() throws Exception {
        String hq = token("hq1");
        var account = api("POST", USERS, hq, form("FRONT_DESK"), 201);
        String user = account.get("username").asString();
        long id = account.get("id").asLong();
        String path = USERS + "/" + id;
        String old = login(user, INITIAL);
        String resetKey = UUID.randomUUID().toString();
        var command = Map.of("newPassword", RESET);
        var reset = api("POST", path + "/reset-password", hq, command, 200, resetKey);
        assertAccount(reset);
        assertThat(api("POST", path + "/reset-password", hq, command, 200, resetKey)).isEqualTo(reset);
        assertThat(api("POST", path + "/reset-password", hq, Map.of("newPassword", INITIAL), 409, resetKey)
            .get("code").asString()).isEqualTo("DUPLICATE_REQUEST");
        api("GET", "/api/auth/me", old, null, 401);
        api("POST", "/api/auth/login", null, Map.of("username", user, "password", INITIAL), 401);
        String newer = login(user, RESET);
        String another = login(user, RESET);
        assertThat(requestFingerprint(resetKey)).startsWith("$2").hasSize(60);

        var stopped = api("PUT", path, hq, Map.of("displayName", "停用测试", "active", false,
            "version", reset.get("version").asInt()), 200);
        assertThat(stopped.get("active").asBoolean()).isFalse();
        assertThat(api("GET", USERS, hq, null, 200).values()).contains(stopped);
        api("GET", "/api/auth/me", newer, null, 401);
        api("GET", "/api/auth/me", another, null, 401);
        var disabledLogin = api("POST", "/api/auth/login", null, Map.of("username", user, "password", RESET), 401);
        assertThat(disabledLogin.get("message").asString()).isEqualTo("账号已停用，请联系总部");

        var enabled = api("PUT", path, hq, Map.of("displayName", "已启用", "active", true,
            "version", stopped.get("version").asInt()), 200);
        assertThat(enabled.get("active").asBoolean()).isTrue();
        login(user, RESET);
        var audit = jdbc.sql("""
            SELECT action,detail FROM operation_log
            WHERE target_type='USER' AND target_id=? ORDER BY id
            """).param(id).query((r,n) -> Map.entry(r.getString("action"),r.getString("detail"))).list();
        assertThat(audit).extracting(Map.Entry::getKey).containsExactly("CREATE_USER","RESET_PASSWORD","UPDATE_USER","UPDATE_USER");
        assertThat(audit).allSatisfy(entry -> assertThat(entry.getValue())
            .doesNotContain(INITIAL, RESET, "passwordHash", "password_hash", "$2a$", "$2b$", "$2y$"));
    }

    @Test void keepsAnExistingInactiveDentistBindingWhenUpdatingTheAccount() throws Exception {
        String hq = token("hq1");
        var profile = new HashMap<String,Object>(Map.of(
            "employeeNo", "QT-" + UUID.randomUUID().toString().substring(0, 12),
            "name", "停用账号测试牙医", "birthDate", "1990-01-01", "homeAddress", "测试地址",
            "phone", "123456", "email", "test@example.test", "qualifications", List.of()));
        var dentist = api("POST", "/api/dentists", hq, profile, 201);
        long dentistId = dentist.get("id").asLong();
        var form = form("DENTIST");
        form.put("dentistId", dentistId);
        var account = api("POST", USERS, hq, form, 201);
        String session = login(account.get("username").asString(), INITIAL);

        var otherProfile = new HashMap<>(profile);
        otherProfile.put("employeeNo", "QT-" + UUID.randomUUID().toString().substring(0, 12));
        var otherDentist = api("POST", "/api/dentists", hq, otherProfile, 201);
        var otherForm = form("DENTIST");
        otherForm.put("dentistId", otherDentist.get("id").asLong());
        var otherAccount = api("POST", USERS, hq, otherForm, 201);

        profile.put("active", false);
        profile.put("version", dentist.get("version").asInt());
        api("PUT", "/api/dentists/" + dentistId, hq, profile, 200);

        String accountPath = USERS + "/" + account.get("id").asLong();
        var deactivated = api("PUT", accountPath, hq, Map.of("displayName", "已停用账号", "dentistId", dentistId,
            "active", false, "version", account.get("version").asInt()), 200);
        assertThat(deactivated.get("active").asBoolean()).isFalse();
        assertThat(deactivated.get("dentist").get("id").asLong()).isEqualTo(dentistId);
        api("GET", "/api/auth/me", session, null, 401);

        var rebind = api("PUT", USERS + "/" + otherAccount.get("id").asLong(), hq,
            Map.of("displayName", "其他账号", "dentistId", dentistId, "active", true,
                "version", otherAccount.get("version").asInt()), 400);
        assertField(rebind, "dentistId");
        assertThat(rebind.get("message").asString()).isEqualTo("该牙医已停用");

        var enabled = api("PUT", accountPath, hq, Map.of("displayName", "重新启用账号", "dentistId", dentistId,
            "active", true, "version", deactivated.get("version").asInt()), 200);
        assertThat(enabled.get("active").asBoolean()).isTrue();
        assertThat(enabled.get("displayName").asString()).isEqualTo("重新启用账号");
        api("GET", "/api/auth/me", session, null, 401);
    }

    @Test void personalChangeKeepsCurrentTokenAndReplaysOnlyTheIdenticalRequest() throws Exception {
        String hq = token("hq1");
        var form = form("FRONT_DESK");
        String initialRequestKey = UUID.randomUUID().toString();
        var account = api("POST", USERS, hq, form, 201, initialRequestKey);
        assertThat(api("POST", USERS, hq, form, 201, initialRequestKey)).isEqualTo(account);
        assertThat(requestFingerprint(initialRequestKey)).startsWith("$2").hasSize(60);
        String login = account.get("username").asString();
        String current = login(login, INITIAL);
        String other = login(login, INITIAL);

        assertField(api("POST", CHANGE, current, Map.of("oldPassword", "Incorrect_15", "newPassword", REPLACEMENT), 400),
            "oldPassword");
        assertField(api("POST", CHANGE, current, Map.of("oldPassword", INITIAL, "newPassword", INITIAL), 400), "newPassword");
        var formChange = Map.of("oldPassword", INITIAL, "newPassword", REPLACEMENT);
        String key = UUID.randomUUID().toString();
        var changed = api("POST", CHANGE, current, formChange, 200, key);
        assertThat(changed.propertyNames()).containsExactly("ok");
        assertThat(changed.get("ok").asBoolean()).isTrue();
        assertThat(api("POST", CHANGE, current, formChange, 200, key)).isEqualTo(changed);
        assertThat(requestFingerprint(key)).startsWith("$2").hasSize(60);
        assertThat(api("POST", CHANGE, current, Map.of("oldPassword", INITIAL, "newPassword", RESET), 409, key)
            .get("code").asString()).isEqualTo("DUPLICATE_REQUEST");
        api("GET", "/api/auth/me", current, null, 200);
        api("GET", "/api/auth/me", other, null, 401);
        api("POST", "/api/auth/login", null, Map.of("username", login, "password", INITIAL), 401);
        login(login, REPLACEMENT);
        String detail = jdbc.sql("""
            SELECT detail FROM operation_log WHERE target_type='USER' AND target_id=? AND action='CHANGE_PASSWORD'
            """).param(account.get("id").asLong()).query(String.class).single();
        assertThat(detail).doesNotContain(INITIAL, REPLACEMENT, "passwordHash", "$2a$", "$2b$", "$2y$");
    }

    @Test void appliesUtf8ByteLimitOnAllPasswordWritesAndAcceptsExactly72Bytes() throws Exception {
        String hq = token("hq1");
        var form = form("FRONT_DESK");
        String atLimit = "汉".repeat(24);
        String tooLong = atLimit + "a";
        form.put("password", tooLong);
        assertByteError(api("POST", USERS, hq, form, 400), "password");
        form.put("password", "abcd1");
        assertField(api("POST", USERS, hq, form, 400), "password");
        form.put("password", "a".repeat(65));
        assertField(api("POST", USERS, hq, form, 400), "password");
        form.put("password", atLimit);
        var account = api("POST", USERS, hq, form, 201);
        String current = login(account.get("username").asString(), atLimit);
        assertByteError(api("POST", CHANGE, current, Map.of("oldPassword", atLimit, "newPassword", tooLong), 400), "newPassword");
        assertByteError(api("POST", USERS + "/" + account.get("id").asLong() + "/reset-password", hq,
            Map.of("newPassword", tooLong), 400), "newPassword");
        api("GET", "/api/auth/me", current, null, 200);
    }

    private long dentistId(String employeeNo) {
        return jdbc.sql("SELECT id FROM dentist WHERE employee_no=?").param(employeeNo).query(Long.class).single();
    }

    private String requestFingerprint(String key) {
        return jdbc.sql("SELECT content_hash FROM request_record WHERE request_id=?").param(key).query(String.class).single();
    }

    private String login(String username, String password) throws Exception {
        return api("POST", "/api/auth/login", null, Map.of("username", username, "password", password), 200)
            .get("token").asString();
    }

    private static Map<String,Object> form(String role) {
        return new HashMap<>(Map.of("username", "test_" + UUID.randomUUID().toString().replace("-", ""),
            "displayName", "账号测试", "role", role, "password", INITIAL));
    }

    private static void assertAccount(JsonNode row) {
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id","username","displayName","role","dentist","active","version");
    }

    private static void assertField(JsonNode error, String field) {
        assertThat(error.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.get("fieldErrors").values()).extracting(value -> value.get("field").asString()).contains(field);
    }

    private static void assertByteError(JsonNode error, String field) {
        assertField(error, field);
        assertThat(error.get("fieldErrors").values()).anySatisfy(issue -> {
            assertThat(issue.get("field").asString()).isEqualTo(field);
            assertThat(issue.get("message").asString()).isEqualTo(BYTE_ERROR);
        });
    }
}
