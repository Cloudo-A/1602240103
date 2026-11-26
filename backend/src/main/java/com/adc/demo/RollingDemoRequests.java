
package com.adc.demo;

import com.adc.appointmentrequest.AppointmentRequestQueries;
import com.adc.demo.RollingDemoCatalog.Account;
import com.adc.demo.RollingDemoCatalog.Roster;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
class RollingDemoRequests {
    record Due(String key, long id, Account contact, long clinic, String timeSlot, LocalDateTime handledAt) {}
    private record Pending(String status, int version, long clinicId, String timeSlot, LocalDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final DemoProgress progress;
    private final AppointmentRequestQueries queries;
    private final RollingDemoWriter writer;

    RollingDemoRequests(JdbcClient jdbc, DemoProgress progress, AppointmentRequestQueries queries, RollingDemoWriter writer) {
        this.jdbc = jdbc;
        this.progress = progress;
        this.queries = queries;
        this.writer = writer;
    }

    void submit(LocalDate day, Roster roster) {
        if ((day.toEpochDay() - RollingDemoData.FIRST_DAY.toEpochDay()) % 2 != 0 || roster.accounts().isEmpty()) return;
        String submitted = "submitted:" + day;
        if (progress.get(submitted).isPresent()) return;
        var random = new Random(day.toEpochDay() * 397);
        var created = day.atStartOfDay();
        int start = random.nextInt(roster.accounts().size());
        for (int i = 0; i < roster.accounts().size(); i++) {
            var account = roster.accounts().get((start + i) % roster.accounts().size());
            jdbc.sql("SELECT id FROM patient_account WHERE id=? FOR UPDATE").param(account.id()).query(Long.class).single();
            if (jdbc.sql("SELECT id FROM appointment_request WHERE patient_account_id=? AND status='PENDING' FOR UPDATE")
                    .param(account.id()).query(Long.class).list().size() >= 3) continue;
            var preferred = day.plusDays(1 + random.nextInt(5));
            String slot = List.of("MORNING", "AFTERNOON", "EVENING").get(random.nextInt(3));
            var clinic = roster.clinics().get(random.nextInt(roster.clinics().size()));
            long request = RollingDemoWriter.insert(jdbc.sql("""
                INSERT INTO appointment_request(patient_account_id,contact_name,contact_phone,clinic_id,
                    preferred_date,time_slot,note,status,created_at)
                VALUES (?,?,?,?,?,?,?,'PENDING',?)
                """).params(account.id(), account.name(), account.phone(), clinic.id(), preferred, slot, "方便时请电话联系。", created));
            var available = roster.eligible(roster.dentists().get(random.nextInt(roster.dentists().size())).profile().id(), preferred.atTime(9, 0));
            if (!available.isEmpty()) {
                var item = available.get(random.nextInt(available.size()));
                jdbc.sql("INSERT INTO appointment_request_item(request_id,item_id) VALUES (?,?)").params(request, item.id()).update();
            }
            LocalDate due = preferred.isBefore(day.plusDays(2)) ? day.plusDays(2) : preferred;
            progress.put("request:" + request, due.atStartOfDay(), List.of(request, account.id()));
            progress.put(submitted, created, request);
            return;
        }
        // The live limit takes priority when all four accounts already have three pending requests.
        progress.put(submitted, created, 0);
    }

    List<Due> due(LocalDate day, Roster roster) {
        var result = new ArrayList<Due>();
        for (var entry : progress.due("request:", day.plusDays(1).atStartOfDay())) {
            long id = entry.data().get(0).asLong();
            long accountId = entry.data().get(1).asLong();
            var pending = jdbc.sql("""
                SELECT status,version,clinic_id,time_slot,created_at FROM appointment_request
                WHERE id=? AND patient_account_id=? FOR UPDATE
                """).params(id, accountId).query(Pending.class).optional().orElse(null);
            var account = roster.accounts().stream().filter(a -> a.id() == accountId).findFirst().orElse(null);
            if (account == null || pending == null || pending.version() != 0 || !"PENDING".equals(pending.status())) {
                progress.remove(entry.key());
                continue;
            }
            var handled = pending.createdAt().toLocalDate().plusDays(1).atTime(8, 30);
            int fate = new Random(pending.createdAt().toLocalDate().toEpochDay() * 7907).nextInt(100);
            if (fate < 75) {
                result.add(new Due(entry.key(), id, account, pending.clinicId(), pending.timeSlot(), handled));
            } else if (fate < 90) {
                var before = queries.getAt(id, handled);
                jdbc.sql("""
                    UPDATE appointment_request SET status='INVALID',invalid_reason=?,handled_at=?,
                        handled_by_user_id=?,handled_by=?,version=1 WHERE id=?
                    """).params(id % 2 == 0 ? "多次致电无人接听" : "患者暂时无法安排到诊",
                        handled, roster.front().id(), roster.front().displayName(), id).update();
                writer.audit(roster.front(), handled, "INVALIDATE_APPOINTMENT_REQUEST", "APPOINTMENT_REQUEST", id,
                    Map.of("before", before, "after", queries.getAt(id, handled)));
                progress.remove(entry.key());
            } else {
                jdbc.sql("UPDATE appointment_request SET status='WITHDRAWN',withdrawn_at=?,version=1 WHERE id=?")
                    .params(handled, id).update();
                progress.remove(entry.key());
            }
        }
        return result;
    }

    void confirm(Due request, long appointment, Roster roster) {
        var when = request.handledAt();
        var before = queries.getAt(request.id(), when);
        jdbc.sql("""
            UPDATE appointment_request SET status='CONFIRMED',appointment_id=?,handled_at=?,
                handled_by_user_id=?,handled_by=?,version=1 WHERE id=?
            """).params(appointment, when, roster.front().id(), roster.front().displayName(), request.id()).update();
        writer.audit(roster.front(), when, "CONFIRM_APPOINTMENT_REQUEST", "APPOINTMENT_REQUEST", request.id(),
            Map.of("before", before, "after", queries.getAt(request.id(), when)));
        progress.remove(request.key());
    }

    List<Long> preferredItems(long request) {
        return jdbc.sql("SELECT item_id FROM appointment_request_item WHERE request_id=? ORDER BY item_id")
            .param(request).query(Long.class).list();
    }
}
