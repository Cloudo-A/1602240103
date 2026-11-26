package db.demo;

import com.adc.demo.DailyDemoAppointments;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public class V14__demo_patient_accounts_and_requests extends BaseJavaMigration {
    // V6's demo BCrypt hash. The patient accounts remain independent of later staff password changes.
    private static final String DEMO_HASH = "$2a$12$nY2u75Q0zOAZ8GlmKHzYXuZ13xVgB3MCBhJrjRvJSqRuO8aJJ0VMu";

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean ownTransaction = connection.getAutoCommit();
        if (ownTransaction) connection.setAutoCommit(false);
        try {
            JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            String previousZone = jdbc.sql("SELECT @@session.time_zone").query(String.class).single();
            LocalDate day;
            try {
                jdbc.sql("SET SESSION time_zone = '+08:00'").update();
                day = jdbc.sql("SELECT CURDATE()").query(LocalDate.class).single();
            } finally {
                jdbc.sql("SET SESSION time_zone = ?").param(previousZone).update();
            }
            new DailyDemoAppointments(jdbc, Clock.system(ZoneId.of("Asia/Shanghai"))).seedIfEmpty(day);
            seed(jdbc, day);
            if (ownTransaction) connection.commit();
        } catch (RuntimeException | SQLException ex) {
            if (ownTransaction) connection.rollback();
            throw ex;
        } finally {
            if (ownTransaction) connection.setAutoCommit(true);
        }
    }

    private record Anchor(long id, long clinicId) {}

    private void seed(JdbcClient jdbc, LocalDate day) {
        Anchor tianhe = anchor(jdbc, "P000001", "2026-07-03T09:00");
        Anchor yuexiu = anchor(jdbc, "P000005", "2026-07-10T09:30");
        long zhang = account(jdbc, "13900000001", "张伟", day.minusDays(4).atTime(10, 0));
        long li = account(jdbc, "13900000002", "李娜", day.minusDays(4).atTime(11, 0));
        long pending = request(jdbc, zhang, tianhe.clinicId(), day.plusDays(1), "MORNING", day.atStartOfDay());
        copyItems(jdbc, tianhe.id(), pending);
        request(jdbc, li, yuexiu.clinicId(), day.plusDays(2), "AFTERNOON", day.atStartOfDay());

        var confirmedAppointment = jdbc.sql("""
            SELECT a.id FROM appointment a JOIN appointment source ON source.id = ?
            JOIN visit v ON v.appointment_id = a.id
            WHERE a.patient_id = source.patient_id AND a.dentist_id = source.dentist_id AND a.clinic_id = source.clinic_id
              AND a.scheduled_at = ? AND a.arrived AND a.cancelled_at IS NULL
              AND v.dentist_id = a.dentist_id AND v.clinic_id = a.clinic_id
              AND v.arrived_at = a.scheduled_at + INTERVAL 5 MINUTE AND v.registered_at = v.arrived_at
              AND NOT EXISTS (SELECT 1 FROM appointment_item i WHERE i.appointment_id = source.id
                  AND NOT EXISTS (SELECT 1 FROM appointment_item j WHERE j.appointment_id = a.id AND j.item_id = i.item_id))
              AND NOT EXISTS (SELECT 1 FROM appointment_item i WHERE i.appointment_id = a.id
                  AND NOT EXISTS (SELECT 1 FROM appointment_item j WHERE j.appointment_id = source.id AND j.item_id = i.item_id))
            """).params(tianhe.id(), day.atTime(9, 0)).query(Long.class).optional();
        if (confirmedAppointment.isPresent()) {
            long confirmed = request(jdbc, zhang, tianhe.clinicId(), day, "MORNING", day.minusDays(1).atTime(9, 0));
            copyItems(jdbc, tianhe.id(), confirmed);
            jdbc.sql("""
                UPDATE appointment_request r JOIN app_user u ON u.username = 'front1'
                SET r.status='CONFIRMED', r.appointment_id=?, r.handled_at=?, r.handled_by_user_id=u.id,
                    r.handled_by=u.display_name, r.version=1 WHERE r.id=?
                """).params(confirmedAppointment.get(), day.minusDays(1).atTime(15, 0), confirmed).update();
        }
        long invalid = request(jdbc, li, yuexiu.clinicId(), day, "AFTERNOON", day.minusDays(2).atTime(9, 0));
        jdbc.sql("""
            UPDATE appointment_request r JOIN app_user u ON u.username = 'front1'
            SET r.status='INVALID', r.invalid_reason='多次致电无人接听', r.handled_at=?, r.handled_by_user_id=u.id,
                r.handled_by=u.display_name, r.version=1 WHERE r.id=?
            """).params(day.minusDays(1).atTime(10, 0), invalid).update();
        long withdrawn = request(jdbc, li, yuexiu.clinicId(), day.plusDays(1), "MORNING", day.minusDays(1).atTime(12, 0));
        jdbc.sql("UPDATE appointment_request SET status='WITHDRAWN', withdrawn_at=?, version=1 WHERE id=?")
            .params(day.minusDays(1).atTime(14, 0), withdrawn).update();
    }

    private Anchor anchor(JdbcClient jdbc, String accountNo, String scheduled) {
        return jdbc.sql("""
            SELECT a.id, a.clinic_id FROM appointment a JOIN patient p ON p.id = a.patient_id
            WHERE p.account_no = ? AND a.scheduled_at = ? AND a.arrived
            """).params(accountNo, LocalDateTime.parse(scheduled))
            .query((rs, row) -> new Anchor(rs.getLong("id"), rs.getLong("clinic_id"))).single();
    }

    private long account(JdbcClient jdbc, String phone, String name, LocalDateTime created) {
        jdbc.sql("INSERT INTO patient_account(phone,name,password_hash,created_at) VALUES (?,?,?,?)")
            .params(phone, name, DEMO_HASH, created).update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private long request(JdbcClient jdbc, long account, long clinic, LocalDate preferred, String slot, LocalDateTime created) {
        jdbc.sql("""
            INSERT INTO appointment_request(patient_account_id,contact_name,contact_phone,clinic_id,
                                            preferred_date,time_slot,status,created_at,version)
            SELECT id,name,phone,?,?,?,'PENDING',?,0 FROM patient_account WHERE id=?
            """).params(clinic, preferred, slot, created, account).update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void copyItems(JdbcClient jdbc, long appointment, long request) {
        jdbc.sql("""
            INSERT INTO appointment_request_item(request_id,item_id)
            SELECT ?,item_id FROM appointment_item WHERE appointment_id=?
            """).params(request, appointment).update();
    }
}
