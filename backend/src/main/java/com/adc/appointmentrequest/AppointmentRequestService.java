package com.adc.appointmentrequest;

import com.adc.auth.CurrentUser;
import com.adc.auth.PatientPrincipal;
import com.adc.common.*;
import com.adc.common.ApiError.FieldIssue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.adc.appointmentrequest.AppointmentRequestDtos.*;

@Service
@Transactional
public class AppointmentRequestService {
    private static final Set<String> SLOTS = Set.of("MORNING","AFTERNOON","EVENING");
    private final JdbcClient jdbc;
    private final EntityManager em;
    private final AppointmentRequestQueries queries;
    private final OperationLogService logs;
    private final Clock clock;

    AppointmentRequestService(JdbcClient jdbc, EntityManager em, AppointmentRequestQueries queries,
                              OperationLogService logs, Clock clock) {
        this.jdbc = jdbc;
        this.em = em;
        this.queries = queries;
        this.logs = logs;
        this.clock = clock;
    }

    private record Contact(String name, String phone) {}

    View create(Create body, PatientPrincipal patient) {
        // Every creator for this account acquires the same row before its first consistent read.
        var contact = jdbc.sql("select name,phone from patient_account where id=? for update").param(patient.id())
            .query((r,n) -> new Contact(r.getString("name"),r.getString("phone"))).optional()
            .orElseThrow(BusinessException::notFound);
        var now = LocalDateTime.now(clock).withNano(0);
        var items = validate(body,now.toLocalDate());
        long pending = jdbc.sql("select count(*) from appointment_request where patient_account_id=? and status='PENDING'")
            .param(patient.id()).query(Long.class).single();
        if (pending >= 3) throw BusinessException.stateNotAllowed("您已有 3 条待确认的预约，请等待前台联系或撤回后再提交");
        var row = new AppointmentRequest();
        row.patientAccountId = patient.id();
        row.contactName = contact.name();
        row.contactPhone = contact.phone();
        row.clinicId = body.clinicId();
        row.preferredDate = body.preferredDate();
        row.timeSlot = body.timeSlot();
        row.note = body.note();
        row.status = "PENDING";
        row.createdAt = now;
        em.persist(row);
        em.flush();
        for (long item : items) {
            jdbc.sql("insert into appointment_request_item(request_id,item_id) values (?,?)").params(row.id,item).update();
        }
        return queries.get(row.id);
    }

    private Set<Long> validate(Create body, LocalDate today) {
        var issues = new ArrayList<FieldIssue>();
        if (body.clinicId() == null || !jdbc.sql("select active from clinic where id=? for share")
                .param(body.clinicId()).query(Boolean.class).optional().orElse(false))
            issues.add(new FieldIssue("clinicId","请选择已启用的门店"));
        if (body.preferredDate() == null || body.preferredDate().isBefore(today) || body.preferredDate().isAfter(today.plusDays(30)))
            issues.add(new FieldIssue("preferredDate","期望日期须在今天至今天后30天之间"));
        if (body.timeSlot() == null || !SLOTS.contains(body.timeSlot())) issues.add(new FieldIssue("timeSlot","请选择有效的期望时段"));
        var items = new LinkedHashSet<Long>();
        if (body.itemIds() != null) items.addAll(body.itemIds());
        if (!items.isEmpty() && (items.contains(null) || jdbc.sql("""
                select id from treatment_item where active and id in (:ids) for share
                """).param("ids",items).query(Long.class).list().size() != items.size()))
            issues.add(new FieldIssue("itemIds","请选择已启用的治疗项目"));
        if (body.note() != null && length(body.note()) > 200) issues.add(new FieldIssue("note","补充说明最多200字"));
        if (!issues.isEmpty()) throw BusinessException.invalid(issues);
        return items;
    }

    private static int length(String s) { return s.codePointCount(0,s.length()); }

    View withdraw(long id, long accountId) {
        var row = VersionedWrites.lock(em,AppointmentRequest.class,id);
        if (row.patientAccountId != accountId) throw BusinessException.notFound();
        if ("WITHDRAWN".equals(row.status)) return queries.get(id);
        if (!"PENDING".equals(row.status)) throw BusinessException.stateNotAllowed("前台已处理，如需取消请致电门店");
        int version = row.version;
        row.status = "WITHDRAWN";
        row.withdrawnAt = LocalDateTime.now(clock).withNano(0);
        VersionedWrites.advance(em,row,version,() -> row.version);
        return queries.get(id);
    }

    View invalidate(long id, Invalidate body, CurrentUser user) {
        var row = VersionedWrites.lock(em,AppointmentRequest.class,id);
        requirePending(row);
        var before = VersionedWrites.check(body.version(),row.version,() -> queries.get(id));
        if (body.reason() == null || body.reason().isBlank() || length(body.reason()) > 200)
            throw BusinessException.invalid("reason","请填写1至200字的无效原因");
        row.status = "INVALID";
        row.invalidReason = body.reason().strip();
        return finish(row,before,user,"INVALIDATE_APPOINTMENT_REQUEST");
    }

    /** Handle to an intention locked by the current booking transaction. */
    public static final class LockedRequest {
        private final AppointmentRequest row;
        private final View before;
        private LockedRequest(AppointmentRequest row, View before) {
            this.row = row;
            this.before = before;
        }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public LockedRequest lockForConversion(long id) {
        var row = em.find(AppointmentRequest.class,id,LockModeType.PESSIMISTIC_WRITE);
        requirePending(row);
        return new LockedRequest(row,queries.get(id));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void confirmConversion(LockedRequest handle, long appointmentId, CurrentUser user) {
        requirePending(handle.row);
        handle.row.status = "CONFIRMED";
        handle.row.appointmentId = appointmentId;
        finish(handle.row,handle.before,user,"CONFIRM_APPOINTMENT_REQUEST");
    }

    private void requirePending(AppointmentRequest row) {
        if (row == null || !"PENDING".equals(row.status)) throw BusinessException.stateNotAllowed("该预约申请已被处理");
    }

    private View finish(AppointmentRequest row, View before, CurrentUser user, String action) {
        row.handledAt = LocalDateTime.now(clock).withNano(0);
        row.handledBy = user.displayName();
        row.handledByUserId = user.id();
        VersionedWrites.advance(em,row,before.version(),() -> row.version);
        var after = queries.get(row.id);
        logs.record(action,"APPOINTMENT_REQUEST",row.id,Map.of("before",before,"after",after));
        return after;
    }
}
