package com.adc;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@TestPropertySource("classpath:auth-test.properties")
@Import(AuthContractTests.RoleProbe.class)
class AuthContractTests {
    @Autowired JdbcClient jdbc;
    @Value("${local.server.port}") int port;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newHttpClient();

    @RestController
    static class RoleProbe {
        @GetMapping("/api/test-role")
        @PreAuthorize("hasRole('HQ')")
        public String headquartersOnly() { return "ok"; }
    }

    @Test void enforcesRoleAndAllowsDemoPreflight() throws Exception {
        String token = login("front1").get("token").asString();
        var denied = call("GET", "test-role", token, null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(denied.body()).get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(call("POST","auth/logout",token,null).statusCode()).isEqualTo(200);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/logout"))
            .header("Origin","https://demo.example")
            .header("Access-Control-Request-Method","POST")
            .header("Access-Control-Request-Headers","Authorization,Content-Type,X-Request-Id")
            .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        var preflight = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
    }

    HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/" + path))
            .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        return response;
    }

    JsonNode login(String username) throws Exception {
        var response = call("POST", "auth/login", null,
            "{\"username\":\"" + username + "\",\"password\":\"123456\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    void unauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        var error = json.readTree(response.body());
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("code","message","fieldErrors","current");
        assertThat(error.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(error.get("current").isNull() && error.get("fieldErrors").isNull()).isTrue();
    }

    @Test void eachClientCanLogOutIndependently() throws Exception {
        var first = login("front1");
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("token","expiresAt","user");
        var expected = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(12);
        assertThat(LocalDateTime.parse(first.get("expiresAt").asString())).isBetween(expected.minusSeconds(10),expected.plusSeconds(1));
        var user = first.get("user");
        assertThat(user.propertyNames()).containsExactlyInAnyOrder("id","username","displayName","role","dentistId");
        assertThat(user.get("dentistId").isNull()).isTrue();
        assertThat(user.get("displayName").asString()).isEqualTo("王丽");
        var web = first.get("token").asString();
        var mini = login("front1").get("token").asString();
        assertThat(web).isNotEqualTo(mini);
        assertThat(call("POST","auth/logout",web,null).statusCode()).isEqualTo(200);
        unauthorized(call("GET","auth/me",web,null));
        unauthorized(call("POST","auth/logout",web,null));
        assertThat(json.readTree(call("GET","auth/me",mini,null).body())).isEqualTo(user);
        assertThat(call("POST","auth/logout",mini,null).statusCode()).isEqualTo(200);
    }

    @Test void demoRolesAndBindings() throws Exception {
        for (String username : Set.of("dentist1","dentist2","hq1")) {
            var login = login(username);
            var user = login.get("user");
            boolean dentist = username.startsWith("dentist");
            assertThat(user.get("role").asString()).isEqualTo(dentist ? "DENTIST" : "HQ");
            assertThat(user.get("dentistId").isIntegralNumber()).isEqualTo(dentist);
            assertThat(call("POST","auth/logout",login.get("token").asString(),null).statusCode()).isEqualTo(200);
        }
    }

    @Test void rejectsMissingInvalidAndIncorrectCredentials() throws Exception {
        unauthorized(call("GET","auth/me",null,null));
        unauthorized(call("GET","future-module","invalid",null));
        var wrong = call("POST","auth/login",null,"{\"username\":\"front1\",\"password\":\"wrong\"}");
        unauthorized(wrong);
        assertThat(json.readTree(wrong.body()).get("message").asString()).isEqualTo("用户名或密码错误");
        var missing = call("POST","auth/login",null,"{}");
        var malformed = call("POST","auth/login",null,"{");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json.readTree(malformed.body()).get("fieldErrors").isNull()).isTrue();
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json.readTree(missing.body()).get("fieldErrors").size()).isEqualTo(2);
    }

    @Test void rejectsExpiredTokenAndDisabledAccount() throws Exception {
        String name = "test-" + java.util.UUID.randomUUID().toString().substring(0, 20);
        jdbc.sql("""
            INSERT INTO app_user (username,password_hash,display_name,role,active)
            SELECT ?,password_hash,'测试账号','HQ',true FROM app_user WHERE username='front1'
            """).param(name).update();
        try {
            String token = login(name).get("token").asString();
            jdbc.sql("""
                UPDATE auth_token SET expires_at='2000-01-01 00:00:00'
                WHERE user_id=(SELECT id FROM app_user WHERE username=?)
                """).param(name).update();
            unauthorized(call("GET","auth/me",token,null));
            String next = login(name).get("token").asString();
            jdbc.sql("UPDATE app_user SET active=false WHERE username=?").param(name).update();
            unauthorized(call("GET","auth/me",next,null));
            var disabled = call("POST","auth/login",null,
                "{\"username\":\"" + name + "\",\"password\":\"123456\"}");
            unauthorized(disabled);
            assertThat(json.readTree(disabled.body()).get("message").asString()).isEqualTo("账号已停用，请联系总部");
        } finally {
            jdbc.sql("DELETE FROM auth_token WHERE user_id=(SELECT id FROM app_user WHERE username=?)").param(name).update();
            jdbc.sql("DELETE FROM app_user WHERE username=?").param(name).update();
        }
    }
}
