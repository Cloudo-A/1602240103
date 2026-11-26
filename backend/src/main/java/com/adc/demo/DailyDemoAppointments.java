package com.adc.demo;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
@Transactional
public class DailyDemoAppointments implements ApplicationRunner {
    // Use each historical appointment's dentist, clinic and intended items. These foreign keys
    // remain valid after the user renames a clinic, an employee number or a treatment item.
    private record Slot(String time, String patient, String sourcePatient, String sourceTime, String outcome) {}
    private static final List<Slot> TODAY = List.of(
        new Slot("09:00", "P000001", "P000001", "2026-07-03T09:00", "A"),
        new Slot("09:30", "P000005", "P000005", "2026-07-10T09:30", "K"),
        new Slot("10:30", "P000003", "P000003", "2026-07-03T10:00", "V"),
        new Slot("14:00", "P000007", "P000002", "2026-10-08T09:30", "V"),
        new Slot("15:30", "P000002", "P000001", "2026-09-12T10:00", "V"),
        new Slot("16:00", "P000004", "P000007", "2026-07-15T11:00", "V")
    );

    private final JdbcClient jdbc;
    private final Clock clock;

    public DailyDemoAppointments(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty(LocalDate.now(clock));
    }

    public void seedIfEmpty(LocalDate day) {
        // All demo instances acquire this existing singleton before testing the same day.
        jdbc.sql("SELECT singleton FROM patient_number WHERE singleton = 1 FOR UPDATE").query(Integer.class).single();
        var existing = jdbc.sql("""
            SELECT id FROM appointment WHERE scheduled_at >= ? AND scheduled_at < ? LIMIT 1 FOR SHARE
            """).params(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).query(Long.class).optional();
        if (existing.isPresent()) return;
        for (Slot slot : TODAY) createAppointment(day.atTime(LocalTime.parse(slot.time())), slot);
    }

    private void createAppointment(LocalDateTime scheduled, Slot slot) {
        long source = jdbc.sql("""
            SELECT a.id FROM appointment a JOIN patient p ON p.id = a.patient_id
            WHERE p.account_no = ? AND a.scheduled_at = ? AND a.arrived
            """).params(slot.sourcePatient(), LocalDateTime.parse(slot.sourceTime())).query(Long.class).single();
        boolean arrived = !slot.outcome().equals("V");
        jdbc.sql("""
            INSERT INTO appointment (patient_id, dentist_id, clinic_id, scheduled_at, arrived)
            SELECT (SELECT id FROM patient WHERE account_no = ?), dentist_id, clinic_id, ?, ?
            FROM appointment WHERE id = ?
            """).params(slot.patient(), scheduled, arrived, source).update();
        long id = lastId();
        jdbc.sql("""
            INSERT INTO appointment_item (appointment_id, item_id)
            SELECT ?, item_id FROM appointment_item WHERE appointment_id = ?
            """).params(id, source).update();
        if (arrived) createVisit(id, slot.outcome().equals("K"));
    }

    private void createVisit(long appointment, boolean confirmed) {
        jdbc.sql("""
            INSERT INTO visit (appointment_id, dentist_id, clinic_id, arrived_at, registered_at,
                               confirmed_at, problem, special_needs)
            SELECT id, dentist_id, clinic_id, scheduled_at + INTERVAL 5 MINUTE, scheduled_at + INTERVAL 5 MINUTE,
                   IF(?, scheduled_at + INTERVAL 45 MINUTE, NULL), IF(?, '常规检查与治疗', NULL), IF(?, '无', NULL)
            FROM appointment WHERE id = ?
            """).params(confirmed, confirmed, confirmed, appointment).update();
        if (!confirmed) return;
        long visit = lastId();
        jdbc.sql("""
            INSERT INTO treatment (visit_id, item_id, quantity, unit_price, item_name, unit_name)
            SELECT v.id, i.id, 1, (SELECT p.unit_price FROM standard_price p
                WHERE p.item_id = i.id AND p.effective_from <= DATE(v.arrived_at)
                ORDER BY p.effective_from DESC LIMIT 1), i.name, i.standard_unit
            FROM visit v JOIN appointment_item ai ON ai.appointment_id = v.appointment_id
            JOIN treatment_item i ON i.id = ai.item_id WHERE v.id = ?
            """).param(visit).update();
    }

    private long lastId() {
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }
}
