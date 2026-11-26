
package com.adc.demo;

import com.adc.appointment.AppointmentView;
import com.adc.auth.CurrentUser;
import com.adc.demo.RollingDemoCatalog.*;
import com.adc.settlement.SettlementDtos;
import com.adc.settlement.SettlementNumbers;
import com.adc.visit.VisitDtos;
import com.adc.visit.VisitQueries;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("demo")
class RollingDemoWriter {
    enum Outcome { SETTLED, NEXT_MORNING, CANCELLED, NO_TREATMENT, MISSED }
    record Slot(Patient patient, Dentist dentist, AppointmentView.Named clinic, LocalDateTime at, List<Item> items) {}
    record VisitState(long id, LocalDateTime arrivedAt, LocalDateTime registeredAt, LocalDateTime confirmedAt, int version) {}
    private record Template(long id, long dentistId, LocalDateTime scheduledAt, boolean arrived, int version,
                            AppointmentView.Patient patient, AppointmentView.Dentist dentist, AppointmentView.Named clinic) {}

    private final JdbcClient jdbc;
    private final DemoProgress progress;
    private final VisitQueries visits;
    private final SettlementNumbers numbers;
    private final ObjectMapper json;

    RollingDemoWriter(JdbcClient jdbc, DemoProgress progress, VisitQueries visits, SettlementNumbers numbers, ObjectMapper json) {
        this.jdbc = jdbc;
        this.progress = progress;
        this.visits = visits;
        this.numbers = numbers;
        this.json = json;
    }

    Long book(Slot slot, CurrentUser front, LocalDateTime createdAt, Long requestId) {
        final long id;
        try {
            id = insert(jdbc.sql("""
                INSERT INTO appointment(patient_id,dentist_id,clinic_id,scheduled_at) VALUES (?,?,?,?)
                """).params(slot.patient().id(), slot.dentist().profile().id(), slot.clinic().id(), slot.at()));
        } catch (DuplicateKeyException occupied) {
            return null;
        }
        for (var item : slot.items()) {
            jdbc.sql("INSERT INTO appointment_item(appointment_id,item_id) VALUES (?,?)").params(id, item.id()).update();
        }
        var booked = new AppointmentView(id, "VALID", slot.at(), slot.patient().view(), slot.dentist().profile().view(),
            slot.clinic(), slot.items().stream().map(Item::view).toList(), false, null, null, null, 0);
        recordBooking(booked, front, createdAt, requestId);
        return id;
    }

    private void recordBooking(AppointmentView view, CurrentUser front, LocalDateTime createdAt, Long requestId) {
        var payload = json.valueToTree(view).asObject();
        if (requestId != null) payload.put("requestId", requestId.longValue());
        audit(front, createdAt, "CREATE_APPOINTMENT", "APPOINTMENT", view.id(), payload);
    }

    boolean finish(long id, Slot slot, Outcome outcome, boolean holdBill, LocalDateTime createdAt,
                   LocalDateTime now, Random random, Roster roster) {
        if (outcome == Outcome.MISSED) return false;
        if (outcome == Outcome.CANCELLED) {
            var cancelled = slot.at().toLocalDate().minusDays(1 + random.nextInt(3)).atTime(18, 0);
            if (!cancelled.isAfter(createdAt)) cancelled = slot.at().toLocalDate().atTime(8, 15);
            if (!cancelled.isAfter(createdAt)) cancelled = createdAt.plusSeconds(1);
            jdbc.sql("UPDATE appointment SET cancelled_at=?,cancelled_by=?,version=version+1 WHERE id=?")
                .params(cancelled, roster.front().displayName(), id).update();
            audit(roster.front(), cancelled, "CANCEL_APPOINTMENT", "APPOINTMENT", id,
                new AppointmentView(id, "CANCELLED", slot.at(), slot.patient().view(), slot.dentist().profile().view(),
                    slot.clinic(), bookedItems(id), false, cancelled, roster.front().displayName(), null, 1));
            return false;
        }
        var arrived = slot.at().plusMinutes(5);
        jdbc.sql("UPDATE appointment SET arrived=TRUE,version=version+1 WHERE id=?").param(id).update();
        long visit = insert(jdbc.sql("""
            INSERT INTO visit(appointment_id,dentist_id,clinic_id,arrived_at,registered_at) VALUES (?,?,?,?,?)
            """).params(id, slot.dentist().profile().id(), slot.clinic().id(), arrived, arrived));
        logArrival(id, new VisitState(visit, arrived, arrived, null, 0), slot.dentist().profile().id(), slot.clinic().id(), roster.front());
        confirm(visit, arrived.plusMinutes(30), outcome, slot.items(), slot.dentist(), random, roster);
        return billAfterVisit(visit, 1, arrived, outcome, holdBill, now, roster.front());
    }

    private void confirm(long visit, LocalDateTime when, Outcome outcome, List<Item> items, Dentist dentist,
                         Random random, Roster roster) {
        var before = visits.detail(visit, dentist.actor());
        if (outcome == Outcome.NO_TREATMENT) {
            jdbc.sql("""
                UPDATE visit SET problem='到诊评估',no_treatment_reason='患者今日身体不适，建议改日复诊',
                                 confirmed_at=?,version=version+1 WHERE id=?
                """).params(when, visit).update();
            audit(dentist.actor(), when, "END_WITHOUT_TREATMENT", "VISIT", visit,
                Map.of("before", before, "after", visits.detail(visit, dentist.actor())));
            return;
        }
        for (var item : items) {
            var quantity = item.quantityScale() == 0 ? BigDecimal.valueOf(1 + random.nextInt(2))
                : BigDecimal.valueOf(10 + random.nextInt(16), 1);
            jdbc.sql("""
                INSERT INTO treatment(visit_id,item_id,quantity,unit_price,item_name,unit_name) VALUES (?,?,?,?,?,?)
                """).params(visit, item.id(), quantity, roster.price(item.id(), before.arrivedAt().toLocalDate()),
                    item.name(), item.standardUnit()).update();
        }
        jdbc.sql("UPDATE visit SET problem='常规检查与治疗',special_needs='无',confirmed_at=?,version=version+1 WHERE id=?")
            .params(when, visit).update();
        audit(dentist.actor(), when, "CONFIRM_VISIT", "VISIT", visit,
            Map.of("before", before, "after", visits.detail(visit, dentist.actor())));
    }

    private boolean billAfterVisit(long visit, int version, LocalDateTime arrived, Outcome outcome, boolean holdBill,
                                   LocalDateTime now, CurrentUser front) {
        if (outcome == Outcome.NO_TREATMENT) return false;
        var day = arrived.toLocalDate();
        var due = outcome == Outcome.NEXT_MORNING ? day.plusDays(1).atTime(9, 10) : arrived.plusMinutes(65);
        if (holdBill && sameQuarter(day, now.toLocalDate())) {
            var next = day.plusDays(3);
            while (!sameQuarter(day, next.minusDays(1))) next = next.minusDays(1);
            progress.put("bill:" + visit, next.atStartOfDay(), List.of(visit, version));
            return true;
        }
        if (!due.isBefore(now)) {
            if (sameQuarter(day, now.toLocalDate())) {
                progress.put("bill:" + visit, due, List.of(visit, version));
                return false;
            }
            // Never introduce another blocker in a finished quarter when the next morning has not started.
            due = arrived.plusMinutes(65);
        }
        issue(visit, version, due, now, front);
        return false;
    }

    void issue(long visit, int expectedVersion, LocalDateTime issued, LocalDateTime now, CurrentUser front) {
        if (!issued.isBefore(now)) return;
        var billing = visits.billing(visit, true);
        if (billing.version() != expectedVersion || !"CONFIRMED".equals(billing.status())) return;
        String number = numbers.next(issued.toLocalDate());
        long id = insert(jdbc.sql("""
            INSERT INTO settlement(settlement_no,visit_id,total,issued_at,issued_by,
                                   patient_account_no,patient_name,clinic_name,dentist_name)
            VALUES (?,?,?,?,?,?,?,?,?)
            """).params(number, visit, billing.total(), issued, front.displayName(), billing.accountNo(),
                billing.patientName(), billing.clinicName(), billing.dentistName()));
        audit(front, issued, "ISSUE_SETTLEMENT", "SETTLEMENT", id,
            new SettlementDtos.Slip(number, visit, issued, front.displayName(),
                new SettlementDtos.Patient(billing.accountNo(), billing.patientName()), new SettlementDtos.Name(billing.clinicName()),
                new SettlementDtos.Name(billing.dentistName()), billing.lines(), billing.total()));
    }

    void recordToday(long id, LocalDateTime now, CurrentUser front) {
        var t = template(id);
        var created = now.isBefore(t.scheduledAt()) ? now : t.scheduledAt().toLocalDate().atTime(8, 0);
        recordBooking(new AppointmentView(id, "VALID", t.scheduledAt(), t.patient(), t.dentist(), t.clinic(),
            bookedItems(id), false, null, null, null, 0), front, created, null);
    }

    boolean finishTodayTemplate(long id, LocalDateTime now, boolean holdBill, Random random, Roster roster,
                                Outcome outcome) {
        var t = template(id);
        if (t.version() != 0) return false; // An employee has taken over this booking.
        var dentist = roster.dentists().stream().filter(d -> d.profile().id() == t.dentistId()).findFirst().orElse(null);
        if (dentist == null) return false;
        var eligible = roster.eligible(t.dentistId(), t.scheduledAt());
        if (eligible.isEmpty()) return false;
        var intended = bookedItems(id);
        var items = eligible.stream().filter(item -> intended.stream().anyMatch(ref -> ref.id() == item.id())).limit(2).toList();
        if (items.isEmpty()) items = List.of(eligible.get(0));
        if (!t.arrived()) {
            var slot = new Slot(new Patient(t.patient().id(), t.patient().accountNo(), t.patient().name()),
                dentist, t.clinic(), t.scheduledAt(), items);
            // The day-of template was created that morning. Its retroactive cancellation is never backdated.
            return finish(id, slot, outcome, holdBill, t.scheduledAt().toLocalDate().atTime(8, 0), now, random, roster);
        }
        var visit = jdbc.sql("""
            SELECT id,arrived_at,registered_at,confirmed_at,version FROM visit WHERE appointment_id=? FOR UPDATE
            """).param(id).query(VisitState.class).single();
        logArrival(id, visit, t.dentistId(), t.clinic().id(), roster.front());
        if (visit.version() != 0 || visits.billing(visit.id(), false).settlementNo() != null) return false;
        jdbc.sql("UPDATE appointment SET version=version+1 WHERE id=?").param(id).update();
        if (visit.confirmedAt() == null) {
            confirm(visit.id(), visit.arrivedAt().plusMinutes(30), Outcome.SETTLED, items, dentist, random, roster);
        } else {
            var seeded = visits.detail(visit.id(), dentist.actor());
            var before = new VisitDtos.Detail(visit.id(), "UNCONFIRMED", visit.arrivedAt(), null,
                seeded.patient(), seeded.clinic(), seeded.dentist(), seeded.intendedItems(),
                null, null, null, null, List.of(), null, null, 0);
            jdbc.sql("UPDATE visit SET version=version+1 WHERE id=?").param(visit.id()).update();
            audit(dentist.actor(), visit.confirmedAt(), "CONFIRM_VISIT", "VISIT", visit.id(),
                Map.of("before", before, "after", visits.detail(visit.id(), dentist.actor())));
        }
        return billAfterVisit(visit.id(), 1, visit.arrivedAt(), Outcome.SETTLED, holdBill, now, roster.front());
    }

    private Template template(long id) {
        return jdbc.sql("""
            SELECT a.id,a.dentist_id,a.scheduled_at,a.arrived,a.version,p.id patient_id,p.account_no,p.name patient_name,
                   d.employee_no,d.name dentist_name,c.id clinic_id,c.name clinic_name
            FROM appointment a JOIN patient p ON p.id=a.patient_id
            JOIN dentist d ON d.id=a.dentist_id JOIN clinic c ON c.id=a.clinic_id
            WHERE a.id=? FOR UPDATE OF a
            """).param(id).query((r,n) -> new Template(id, r.getLong("dentist_id"),
                r.getObject("scheduled_at", LocalDateTime.class), r.getBoolean("arrived"), r.getInt("version"),
                new AppointmentView.Patient(r.getLong("patient_id"), r.getString("account_no"), r.getString("patient_name")),
                new AppointmentView.Dentist(r.getLong("dentist_id"), r.getString("employee_no"), r.getString("dentist_name")),
                new AppointmentView.Named(r.getLong("clinic_id"), r.getString("clinic_name")))).single();
    }

    private List<AppointmentView.Named> bookedItems(long appointment) {
        return jdbc.sql("""
            SELECT i.id,i.name FROM appointment_item ai JOIN treatment_item i ON i.id=ai.item_id
            WHERE ai.appointment_id=? ORDER BY i.id
            """).param(appointment).query(AppointmentView.Named.class).list();
    }

    private void logArrival(long appointment, VisitState visit, long dentist, long clinic, CurrentUser front) {
        audit(front, visit.registeredAt(), "REGISTER_ARRIVAL", "APPOINTMENT", appointment,
            Map.of("visitId", visit.id(), "arrivedAt", visit.arrivedAt(), "registeredAt", visit.registeredAt(),
                "dentistId", dentist, "clinicId", clinic));
    }

    void audit(CurrentUser actor, LocalDateTime at, String action, String type, long id, Object detail) {
        jdbc.sql("""
            INSERT INTO operation_log(user_id,username,occurred_at,action,target_type,target_id,detail) VALUES (?,?,?,?,?,?,?)
            """).params(actor.id(), actor.username(), at, action, type, id, json.writeValueAsString(detail)).update();
    }

    static long insert(JdbcClient.StatementSpec statement) {
        var key = new GeneratedKeyHolder();
        statement.update(key, "id");
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    static boolean sameQuarter(LocalDate a, LocalDate b) {
        return a.getYear() == b.getYear() && (a.getMonthValue()-1)/3 == (b.getMonthValue()-1)/3;
    }
}
