package com.adc;

import java.time.*;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class PatientAuthContractTests extends ContractHttp {
    private static final String AUTH = "/api/patient-auth";
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private String phone() {
        return Long.toString(ThreadLocalRandom.current().nextLong(10_000_000_000L,20_000_000_000L));
    }

    private void field(JsonNode error, String expected) {
        assertThat(error.get("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.get("fieldErrors").values()).anyMatch(f -> expected.equals(f.get("field").asString()));
    }

    @Test void validatesRegistrationAndChecksPhonePresenceForSms() throws Exception {
        String phone = phone();
        var credentials = Map.of("phone",phone,"password","demo-secret");
        var register = Map.of("phone",phone,"name","测试患者","code","123456","password","demo-secret");
        var missing = api("POST",AUTH+"/sms-code",null,Map.of("phone",phone,"purpose","RESET_PASSWORD"),404);
        assertThat(missing.get("message").asString()).isEqualTo("该手机号尚未注册");
        api("POST",AUTH+"/reset-password",null,Map.of("phone",phone,"code","123456","newPassword","abc123"),404);
        var loginError = api("POST",AUTH+"/login",null,credentials,401);
        assertThat(loginError.get("message").asString()).isEqualTo("手机号或密码不正确");
        field(api("POST",AUTH+"/sms-code",null,Map.of("phone","23","purpose","REGISTER"),400),"phone");
        field(api("POST",AUTH+"/sms-code",null,Map.of("phone",phone,"purpose","OTHER"),400),"purpose");
        field(api("POST",AUTH+"/register",null,Map.of("phone",phone,"name","名","code","000000","password","abc123"),400),"code");
        field(api("POST",AUTH+"/register",null,Map.of("phone",phone,"name"," ","code","123456","password","abc123"),400),"name");
        field(api("POST",AUTH+"/register",null,Map.of("phone",phone,"name","名","code","123456","password","汉".repeat(25)),400),"password");
        var hint = api("POST",AUTH+"/sms-code",null,Map.of("phone",phone,"purpose","REGISTER"),200);
        assertThat(hint.get("sent").asBoolean()).isTrue();
        assertThat(hint.get("message").asString()).isEqualTo("课程演示：验证码统一为 123456");
        var registration = api("POST",AUTH+"/register",null,register,201);
        assertThat(registration.propertyNames()).containsExactlyInAnyOrder("token","expiresAt","patient");
        assertThat(registration.get("patient").propertyNames()).containsExactlyInAnyOrder("id","phone","name","createdAt");
        var created = LocalDateTime.parse(registration.get("patient").get("createdAt").asString());
        assertThat(LocalDateTime.parse(registration.get("expiresAt").asString())).isBetween(created.plusDays(7),created.plusDays(7).plusSeconds(3));
        assertThat(registration.get("token").asString()).startsWith("pt_");
        assertThat(api("POST",AUTH+"/register",null,register,409).get("message").asString()).isEqualTo("该手机号已注册，请直接登录");
        assertThat(api("POST",AUTH+"/sms-code",null,Map.of("phone",phone,"purpose","REGISTER"),409)
            .get("message").asString()).isEqualTo("该手机号已注册，请直接登录");
        api("POST",AUTH+"/sms-code",null,Map.of("phone",phone,"purpose","RESET_PASSWORD"),200);
        assertThat(api("POST",AUTH+"/login",null,Map.of("phone",phone,"password","incorrect"),401)
            .get("message")).isEqualTo(loginError.get("message"));
    }

    @Test void isolatesPatientTokensLogsOutOneSessionAndRevokesAllOnReset() throws Exception {
        String phone = phone();
        var creds = Map.of("phone",phone,"password","old-abc123");
        var registration = api("POST",AUTH+"/register",null,
            Map.of("phone",phone,"name","李梅","code","123456","password","old-abc123"),201);
        long patientId = registration.get("patient").get("id").asLong();
        String first = registration.get("token").asString();
        String second = api("POST",AUTH+"/login",null,creds,200).get("token").asString();
        assertThat(first).isNotEqualTo(second);
        assertThat(api("GET",AUTH+"/me",second,null,200)).isEqualTo(registration.get("patient"));
        String employee = token("front1");
        for (String path : new String[]{"/api/auth/me","/api/options/clinics","/api/patients","/api/visits"}) {
            api("GET",path,first,null,403);
        }
        api("GET",AUTH+"/me",employee,null,403);
        api("POST",AUTH+"/logout",employee,null,403);
        api("GET","/api/patient/appointment-requests",employee,null,403);
        assertThat(send("POST",AUTH+"/logout",first,null,null).statusCode()).isEqualTo(200);
        api("GET",AUTH+"/me",first,null,401);
        api("GET",AUTH+"/me",second,null,200);
        String third = api("POST",AUTH+"/login",null,creds,200).get("token").asString();
        field(api("POST",AUTH+"/reset-password",null,Map.of("phone",phone,"code","999999","newPassword","abc123"),400),"code");
        field(api("POST",AUTH+"/reset-password",null,Map.of("phone",phone,"code","123456","newPassword","短"),400),"newPassword");
        var reset = api("POST",AUTH+"/reset-password",null,Map.of("phone",phone,"code","123456","newPassword","new-123456"),200);
        assertThat(reset.get("ok").asBoolean()).isTrue();
        api("GET",AUTH+"/me",second,null,401);
        api("GET",AUTH+"/me",third,null,401);
        api("POST",AUTH+"/login",null,creds,401);
        String last = api("POST",AUTH+"/login",null,Map.of("phone",phone,"password","new-123456"),200).get("token").asString();
        jdbc.sql("UPDATE patient_auth_token SET expires_at=? WHERE patient_account_id=?")
            .params(LocalDateTime.now(clock).minusSeconds(1),patientId).update();
        api("GET",AUTH+"/me",last,null,401);
    }
}
