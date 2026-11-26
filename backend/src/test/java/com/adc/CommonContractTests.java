package com.adc;

import com.adc.common.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;

@Import(CommonContractTests.ErrorProbe.class)
class CommonContractTests extends ContractHttp {
    @Autowired JdbcClient jdbc;

    @RestController
    static class ErrorProbe {
        @GetMapping("/api/test-internal")
        public void fail() { throw new IllegalStateException("仅日志可见"); }

        @GetMapping("/api/test-state")
        public void state() { throw BusinessException.stateNotAllowed("当前状态不能办理"); }
    }

    @Test void errorsUseContractEvenForUnknownRoutes() throws Exception {
        var front = token("front1");
        var unknown = api("GET", "/api/does-not-exist", front, null, 404);
        assertThat(unknown.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(unknown.propertyNames()).containsExactlyInAnyOrder("code","message","current","fieldErrors");
        var browser = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/missing-page"))
            .header("Accept","text/html").GET().build();
        var missingPage = client.send(browser,HttpResponse.BodyHandlers.ofString());
        assertThat(missingPage.statusCode()).isEqualTo(404);
        assertThat(json.readTree(missingPage.body()).get("code").asString()).isEqualTo("NOT_FOUND");
        var internal = api("GET","/api/test-internal", front, null, 500);
        assertThat(internal.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(internal.get("message").asString()).isEqualTo("系统错误，请稍后重试");
        assertThat(internal.toString()).doesNotContain("仅日志可见","stackTrace");
        assertThat(api("GET","/api/test-state",front,null,409).get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        assertThat(api("GET","/api/patients?page=x",front,null,400).get("fieldErrors").get(0).get("field").asString())
            .isEqualTo("page");
    }

    @Test void simultaneousRequestsCommitOneWriteAndReplayOriginalBytes() throws Exception {
        String hq = token("hq1");
        String path = "/api/clinics";
        String key = UUID.randomUUID().toString();
        var form = new LinkedHashMap<String,Object>(Map.of("name","幂等-"+key,"officeArea",12.50,
            "postalAddress","地址","managerName","主管","phone","020-12345678","email","test@example.com","active",true));
        String body = json.writeValueAsString(form);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<HttpResponse<String>> submit = () -> {
            start.await();
            return send("POST",path,hq,body,key);
        };
        HttpResponse<String> first;
        try {
            var a = pool.submit(submit);
            var b = pool.submit(submit);
            start.countDown();
            first = a.get(30,TimeUnit.SECONDS);
            var second = b.get(30,TimeUnit.SECONDS);
            assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
            assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
            assertThat(second.body()).isEqualTo(first.body());
        } finally { pool.shutdownNow(); }
        var created = json.readTree(first.body());
        long id = created.get("id").asLong();
        assertThat(jdbc.sql("select count(*) from operation_log where target_type='CLINIC' and target_id=?")
            .param(id).query(Long.class).single()).isEqualTo(1L);
        form.put("version",created.get("version").asInt());
        form.put("active",false);
        api("PUT",path+"/"+id,hq,form,200);
        var replay = send("POST",path,hq,"  " + body,key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        var different = send("POST",path,hq,json.writeValueAsString(form),key);
        assertThat(different.statusCode()).isEqualTo(409);
        assertThat(json.readTree(different.body()).get("code").asString()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(send("POST",path,token("front1"),body,key).statusCode()).isEqualTo(403);
        assertThat(send("POST",path,null,body,key).statusCode()).isEqualTo(401);
    }

    @Test void firstFailureIsReplayedWithoutBusinessWrites() throws Exception {
        String key = UUID.randomUUID().toString();
        String path = "/api/qualifications";
        String hq = token("hq1");
        var bad = send("POST",path,hq,"{",key);
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(400);
        assertThat(json.readTree(bad.body()).get("fieldErrors").isNull()).isTrue();
        var replay = send("POST",path,hq,"{",key);
        assertThat(replay.body()).isEqualTo(bad.body());
        assertThat(replay.statusCode()).isEqualTo(400);
        var name = json.writeValueAsString(Map.of("name","口腔全科"));
        var conflictKey = UUID.randomUUID().toString();
        var conflict = send("POST",path,hq,name,conflictKey);
        assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).get("current").isNull()).isTrue();
        assertThat(send("POST",path,hq,name,conflictKey).body()).isEqualTo(conflict.body());
        var existing = jdbc.sql("select count(*) from request_record where request_id in (?,?)")
            .params(key,conflictKey).query(Long.class).single();
        assertThat(existing).isEqualTo(2L);
    }
}
