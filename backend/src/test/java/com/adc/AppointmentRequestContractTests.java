package com.adc;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class AppointmentRequestContractTests extends ContractHttp {
    private static final String MINE = "/api/patient/appointment-requests";
    private static final String DESK = "/api/appointment-requests";
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    private JsonNode patient() throws Exception {
        String phone = "1" + ThreadLocalRandom.current().nextLong(1_000_000_000L,10_000_000_000L);
        return api("POST","/api/patient-auth/register",null,
            Map.of("phone",phone,"name","意向测试者","code","123456","password","demo12345"),201);
    }

    private Map<String,Object> form() {
        var clinic = jdbc.sql("select id from clinic where name='天河诊所'").query(Long.class).single();
        var item = jdbc.sql("select id from treatment_item where name='洁牙'").query(Long.class).single();
        return new HashMap<>(Map.of("clinicId",clinic,"preferredDate",LocalDate.now(clock).toString(),"timeSlot","MORNING",
            "itemIds",List.of(item),"note","希望电话联系"));
    }

    private long audit(long id) {
        return jdbc.sql("select count(*) from operation_log where target_type='APPOINTMENT_REQUEST' and target_id=?")
            .param(id).query(Long.class).single();
    }

    private static String rid() { return UUID.randomUUID().toString(); }

    @Test void listsOwnSnapshotsAndDeskFiltersAndWithdrawsOnlyPendingRows() throws Exception {
        var account = patient();
        var token = account.get("token").asString();
        var other = patient().get("token").asString();
        var front = token("front1");
        var body = form();
        var requestId = rid();
        var created = api("POST",MINE,token,body,201,requestId);
        assertThat(api("POST",MINE,token,body,201,requestId)).isEqualTo(created);
        long id = created.get("id").asLong();
        assertThat(created.propertyNames()).containsExactlyInAnyOrder("id","status","createdAt","contactName","contactPhone",
            "clinic","preferredDate","timeSlot","items","note","handledAt","handledBy","invalidReason","withdrawnAt","appointment","version");
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("contactPhone")).isEqualTo(account.get("patient").get("phone"));
        assertThat(created.get("contactName")).isEqualTo(account.get("patient").get("name"));
        assertThat(created.get("items").get(0).get("name").asString()).isEqualTo("洁牙");
        assertThat(created.get("clinic").propertyNames()).containsExactlyInAnyOrder("id","name","phone");
        assertThat(created.get("appointment").isNull()).isTrue();
        assertThat(api("GET",DESK+"/"+id,front,null,200)).isEqualTo(created);
        var day = created.get("createdAt").asString().substring(0,10);
        assertThat(api("GET",DESK+"?status=PENDING,INVALID&clinicId="+body.get("clinicId")+"&from="+day+"&to="+day,
            front,null,200).values()).contains(created);
        assertThat(api("GET",MINE,other,null,200).size()).isZero();
        assertThat(api("POST",MINE+"/"+id+"/withdraw",other,Map.of(),404,rid()).get("code").asString()).isEqualTo("NOT_FOUND");
        api("GET",DESK,token,null,403);
        api("GET",MINE,front,null,403);
        api("GET",DESK,token("hq1"),null,403);
        api("GET",DESK,token("dentist1"),null,403);
        var withdrew = api("POST",MINE+"/"+id+"/withdraw",token,Map.of(),200,rid());
        assertThat(withdrew.get("withdrawnAt").asString()).hasSize(19);
        assertThat(withdrew.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(withdrew.get("version").asInt()).isOne();
        assertThat(api("POST",MINE+"/"+id+"/withdraw",token,Map.of(),200,rid())).isEqualTo(withdrew);
        assertThat(audit(id)).isZero();
        body.put("preferredDate",LocalDate.now(clock).plusDays(30).toString());
        body.put("timeSlot","EVENING");
        body.put("itemIds",List.of());
        body.put("note","字".repeat(200));
        var second = api("POST",MINE,token,body,201,rid());
        assertThat(second.get("items").size()).isZero();
        assertThat(api("GET",MINE,token,null,200).values()).containsExactly(second,withdrew);

        // Outside the default 30-day window, the owner can still see the complete history.
        jdbc.sql("update appointment_request set created_at=? where id=?")
            .params(LocalDate.now(clock).minusDays(30).atStartOfDay(),id).update();
        var old = api("GET",DESK+"/"+id,front,null,200);
        assertThat(api("GET",DESK,front,null,200).values()).doesNotContain(old);
        assertThat(api("GET",MINE,token,null,200).values()).contains(old);
        assertThat(api("GET",DESK+"?to="+LocalDate.now(clock).minusDays(30),front,null,200).values()).contains(old);
    }

    @Test void validatesFieldsAndWritesOnlyOneBeforeAfterAuditForInvalidation() throws Exception {
        var patient = patient().get("token").asString();
        var front = token("front1");
        var body = form();
        var invalid = new HashMap<>(body);
        invalid.put("clinicId",-1);
        invalid.put("preferredDate",LocalDate.now(clock).minusDays(1).toString());
        invalid.put("timeSlot","NIGHT");
        invalid.put("itemIds",List.of(-1));
        invalid.put("note","字".repeat(201));
        var bad = api("POST",MINE,patient,invalid,400,rid());
        assertThat(bad.get("fieldErrors").values()).extracting(x -> x.get("field").asString())
            .containsExactlyInAnyOrder("clinicId","preferredDate","timeSlot","itemIds","note");
        invalid = new HashMap<>(body);
        invalid.put("preferredDate",LocalDate.now(clock).plusDays(31).toString());
        assertThat(api("POST",MINE,patient,invalid,400,rid()).get("fieldErrors").get(0).get("field").asString()).isEqualTo("preferredDate");
        var created = api("POST",MINE,patient,body,201,rid());
        long id = created.get("id").asLong();
        var path = DESK+"/"+id+"/invalidate";
        var stale = api("POST",path,front,Map.of("reason","无法联系","version",1),409,rid());
        assertThat(stale.get("code").asString()).isEqualTo("CHANGED");
        assertThat(stale.get("current")).isEqualTo(created);
        for (String reason : List.of("  ","字".repeat(201))) {
            assertThat(api("POST",path,front,Map.of("reason",reason,"version",0),400,rid())
                .get("fieldErrors").get(0).get("field").asString()).isEqualTo("reason");
        }
        var staff = api("GET","/api/auth/me",front,null,200);
        var input = Map.of("reason","多次致电无人接听","version",0);
        String requestId = rid();
        var marked = api("POST",path,front,input,200,requestId);
        assertThat(marked.get("status").asString()).isEqualTo("INVALID");
        assertThat(marked.get("invalidReason").asString()).isEqualTo(input.get("reason"));
        assertThat(marked.get("handledBy")).isEqualTo(staff.get("displayName"));
        assertThat(marked.get("handledAt").asString()).hasSize(19);
        assertThat(marked.get("version").asInt()).isOne();
        assertThat(api("POST",path,front,input,200,requestId)).isEqualTo(marked);
        assertThat(api("POST",path,front,input,409,rid()).get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
        assertThat(api("POST",MINE+"/"+id+"/withdraw",patient,Map.of(),409,rid()).get("message").asString())
            .isEqualTo("前台已处理，如需取消请致电门店");
        assertThat(audit(id)).isOne();
        var detail = json.readTree(jdbc.sql("""
            select detail from operation_log where target_type='APPOINTMENT_REQUEST' and target_id=?
            and action='INVALIDATE_APPOINTMENT_REQUEST' and user_id=?
            """).params(id,staff.get("id").asLong()).query(String.class).single());
        assertThat(detail.get("before")).isEqualTo(created);
        assertThat(detail.get("after")).isEqualTo(marked);
    }

    @Test void permitsOnlyThreePendingEvenWithSimultaneousSubmissionsAndReleasesCapacityOnWithdrawal() throws Exception {
        var patient = patient().get("token").asString();
        var body = form();
        var payload = json.writeValueAsString(body);
        var start = new CountDownLatch(4);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Future<java.net.http.HttpResponse<String>>>();
            for (int i=0;i<4;i++) {
                calls.add(pool.submit(() -> {
                    start.countDown();
                    if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("并发请求未同时启动");
                    return send("POST",MINE,patient,payload,rid());
                }));
            }
            var statuses = new ArrayList<Integer>();
            for (var call : calls) {
                var response = call.get(30,TimeUnit.SECONDS);
                statuses.add(response.statusCode());
                if (response.statusCode()==409) {
                    var error = json.readTree(response.body());
                    assertThat(error.get("code").asString()).isEqualTo("STATE_NOT_ALLOWED");
                    assertThat(error.get("message").asString()).isEqualTo("您已有 3 条待确认的预约，请等待前台联系或撤回后再提交");
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(201,201,201,409);
        } finally {
            pool.shutdownNow();
        }
        var rows = api("GET",MINE,patient,null,200);
        assertThat(rows.size()).isEqualTo(3);
        api("POST",MINE+"/"+rows.get(0).get("id").asLong()+"/withdraw",patient,Map.of(),200,rid());
        api("POST",MINE,patient,body,201,rid());
    }
}
