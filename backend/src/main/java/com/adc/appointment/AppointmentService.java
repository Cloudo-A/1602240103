package com.adc.appointment;

import com.adc.appointmentrequest.AppointmentRequestService;
import com.adc.auth.CurrentUser;
import com.adc.common.*;
import com.adc.common.ApiError.FieldIssue;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.adc.appointment.AppointmentController.*;

@Service
@Transactional
class AppointmentService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT);
    private final JdbcClient jdbc;
    private final EntityManager em;
    private final OperationLogService logs;
    private final AppointmentQueries queries;
    private final AppointmentRequestService requests;
    private final ObjectMapper json;
    private final Clock clock;

    AppointmentService(JdbcClient jdbc, EntityManager em, OperationLogService logs, AppointmentQueries queries,
                       AppointmentRequestService requests, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.em = em;
        this.logs = logs;
        this.queries = queries;
        this.requests = requests;
        this.json = json;
        this.clock = clock;
    }

    private record SlotOwners(String patientName, String dentistName) {
        String patientConflict() { return "患者" + patientName + "在该时间已有预约"; }
        String dentistConflict() { return dentistName + "医生在该时间已有预约"; }
    }
    private record Inspection(LocalDateTime at, Set<Long> items, List<FieldIssue> issues, List<String> conflicts,
                              SlotOwners owners) {}

    @Transactional(readOnly=true)
    Check check(Booking body) {
        var inspected = inspect(body, false);
        var problems = new ArrayList<String>();
        inspected.issues.forEach(issue -> problems.add(issue.message()));
        problems.addAll(inspected.conflicts);
        return new Check(problems.isEmpty(), problems);
    }

    AppointmentView create(Booking body, CurrentUser user) {
        var requestId = bookingRequestId(body);
        var intent = requestId == null ? null : requests.lockForConversion(requestId);
        var inspected = inspect(body, true);
        if (!inspected.issues.isEmpty()) throw BusinessException.invalid(inspected.issues);
        if (!inspected.conflicts.isEmpty()) throw BusinessException.conflict(String.join("；", inspected.conflicts));
        var appointment = new Appointment();
        appointment.patientId = body.patientId();
        appointment.dentistId = body.dentistId();
        appointment.clinicId = body.clinicId();
        appointment.scheduledAt = inspected.at;
        try {
            em.persist(appointment);
            em.flush();
        } catch (RuntimeException ex) {
            // A competing writer can occupy the slot after inspection.
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                    if (sql.getMessage().contains("uk_appt_patient_slot")) throw BusinessException.conflict(inspected.owners.patientConflict());
                    if (sql.getMessage().contains("uk_appt_dentist_slot")) throw BusinessException.conflict(inspected.owners.dentistConflict());
                }
            }
            throw ex;
        }
        for (long item : inspected.items) {
            jdbc.sql("insert into appointment_item (appointment_id,item_id) values (?,?)").params(appointment.id, item).update();
        }
        var result = queries.get(appointment.id);
        if (intent == null) {
            logs.record("CREATE_APPOINTMENT", "APPOINTMENT", appointment.id, result);
        } else {
            ObjectNode detail = json.valueToTree(result);
            detail.put("requestId", requestId.longValue());
            logs.record("CREATE_APPOINTMENT", "APPOINTMENT", appointment.id, detail);
            requests.confirmConversion(intent, appointment.id, user);
        }
        return result;
    }

    private static Long bookingRequestId(Booking body) {
        var value = body.requestId();
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw BusinessException.invalid("requestId", "预约申请编号格式不正确");
        return value.longValue();
    }

    private Inspection inspect(Booking body, boolean lockReferences) {
        var issues = new ArrayList<FieldIssue>();
        var conflicts = new ArrayList<String>();
        var suffix = lockReferences ? " for share" : "";
        String patientName = body.patientId() == null ? null : jdbc.sql("select name from patient where id=?" + suffix)
            .param(body.patientId()).query(String.class).optional().orElse(null);
        if (patientName == null) {
            issues.add(new FieldIssue("patientId", "请选择存在的患者"));
        }
        String dentistName = active("dentist", body.dentistId(), "dentistId", "牙医", lockReferences, issues);
        var owners = new SlotOwners(patientName, dentistName);
        active("clinic", body.clinicId(), "clinicId", "诊所", lockReferences, issues);
        var at = parseTime(body.scheduledAt(), "scheduledAt", "预约时间", issues);
        if (at != null && at.isBefore(now().withSecond(0))) issues.add(new FieldIssue("scheduledAt", "预约时间不能早于当前分钟"));
        Set<Long> items = new LinkedHashSet<>();
        if (body.itemIds() != null) items.addAll(body.itemIds());
        if (items.isEmpty()) issues.add(new FieldIssue("itemIds", "请至少选择一项意向治疗"));
        for (var item : items) active("treatment_item", item, "itemIds", "治疗项目", lockReferences, issues);
        if (at != null) {
            if (patientName != null && occupied("patient_id", body.patientId(), at)) conflicts.add(owners.patientConflict());
            if (dentistName != null && occupied("dentist_id", body.dentistId(), at)) conflicts.add(owners.dentistConflict());
        }
        return new Inspection(at, items, issues, conflicts, owners);
    }

    private boolean occupied(String column, Long id, LocalDateTime at) {
        return id != null && !jdbc.sql("select id from appointment where " + column + "=? and active_slot=?")
            .params(id, at).query(Long.class).list().isEmpty();
    }

    private record Resource(String name, boolean active) {}

    private String active(String table, Long id, String field, String label, boolean lock, List<FieldIssue> issues) {
        var resource = id == null ? null : jdbc.sql("select name,active from " + table + " where id=?" + (lock ? " for share" : ""))
            .param(id).query((rs,n) -> new Resource(rs.getString("name"),rs.getBoolean("active"))).optional().orElse(null);
        if (resource == null) {
            issues.add(new FieldIssue(field, label + "不存在"));
            return null;
        }
        if (!resource.active()) issues.add(new FieldIssue(field, label + "已停用"));
        return resource.name();
    }

    private LocalDateTime parseTime(String value, String field, String label, List<FieldIssue> issues) {
        if (value != null) {
            try {
                var parsed = LocalDateTime.parse(value, TIME);
                if (parsed.getYear() >= 1000 && parsed.getYear() <= 9999) return parsed;
            } catch (DateTimeParseException ignored) {
                // Return this field issue alongside the other input problems.
            }
        }
        issues.add(new FieldIssue(field, "请填写有效的" + label + "（精确到秒）"));
        return null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    AppointmentView cancel(long id, Integer version, CurrentUser user) {
        var row = VersionedWrites.lock(em, Appointment.class, id);
        if (row.cancelledAt != null) return queries.get(id);
        if (row.arrived) throw BusinessException.stateNotAllowed("该预约已登记到诊，不能取消");
        var before = VersionedWrites.check(version, row.version, () -> queries.get(id));
        row.cancelledAt = now();
        row.cancelledBy = user.displayName();
        VersionedWrites.advance(em, row, before.version(), () -> row.version);
        var result = queries.get(id);
        logs.record("CANCEL_APPOINTMENT", "APPOINTMENT", id, result);
        return result;
    }

    ArrivalResult arrive(long id, Arrival body) {
        var row = VersionedWrites.lock(em, Appointment.class, id);
        if (row.arrived) {
            var original = queries.get(id);
            return new ArrivalResult(original, original.visitId());
        }
        if (row.cancelledAt != null) throw BusinessException.stateNotAllowed("该预约已取消，不能登记到诊");
        var before = VersionedWrites.check(body.version(), row.version, () -> queries.get(id));
        var issues = new ArrayList<FieldIssue>();
        var at = parseTime(body.arrivedAt(), "arrivedAt", "到诊时间", issues);
        var registered = now();
        if (at != null && at.isAfter(registered)) issues.add(new FieldIssue("arrivedAt", "到诊时间不能晚于现在"));
        long dentist = body.dentistId() == null ? row.dentistId : body.dentistId();
        long clinic = body.clinicId() == null ? row.clinicId : body.clinicId();
        active("dentist", dentist, "dentistId", "牙医", true, issues);
        active("clinic", clinic, "clinicId", "诊所", true, issues);
        if (!issues.isEmpty()) throw BusinessException.invalid(issues);
        var keys = new GeneratedKeyHolder();
        jdbc.sql("insert into visit (appointment_id,dentist_id,clinic_id,arrived_at,registered_at) values (?,?,?,?,?)")
            .params(id, dentist, clinic, at, registered).update(keys, "id");
        long visitId = Objects.requireNonNull(keys.getKey()).longValue();
        row.arrived = true;
        VersionedWrites.advance(em, row, before.version(), () -> row.version);
        var result = new ArrivalResult(queries.get(id), visitId);
        logs.record("REGISTER_ARRIVAL", "APPOINTMENT", id, Map.of("visitId", visitId, "arrivedAt", at,
            "registeredAt", registered, "dentistId", dentist, "clinicId", clinic));
        return result;
    }
}
