package com.adc.appointment;

import com.adc.auth.CurrentUser;
import com.adc.common.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import static com.adc.appointment.AppointmentView.*;

@Repository
public class AppointmentQueries {
    private static final String SELECT = """
        select a.id, a.scheduled_at, a.arrived, a.cancelled_at, a.cancelled_by, a.version,
               p.id patient_id, p.account_no, p.name patient_name,
               d.id dentist_id, d.employee_no, d.name dentist_name, c.id clinic_id, c.name clinic_name,
               t.id item_id, t.name item_name, v.id visit_id
        from appointment a join patient p on p.id=a.patient_id join dentist d on d.id=a.dentist_id
        join clinic c on c.id=a.clinic_id left join visit v on v.appointment_id=a.id
        left join appointment_item ai on ai.appointment_id=a.id left join treatment_item t on t.id=ai.item_id
        where 1=1
        """;
    private final JdbcClient jdbc;
    private final Clock clock;

    AppointmentQueries(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<AppointmentView> list(LocalDate from, LocalDate to, Long clinicId, Long dentistId, Long patientId,
                    List<String> statuses, CurrentUser user) {
        if (from == null && to == null && patientId == null) from = to = LocalDate.now(clock);
        if (from != null && to != null && from.isAfter(to))
            throw BusinessException.invalid("to", "结束日期不能早于开始日期");
        var params = new ArrayList<Object>();
        var sql = new StringBuilder(SELECT);
        condition(sql, params, "a.scheduled_at >= ?", from == null ? null : from.atStartOfDay());
        condition(sql, params, "a.scheduled_at <= ?", to == null ? null : to.atTime(23, 59, 59));
        condition(sql, params, "a.clinic_id = ?", clinicId);
        condition(sql, params, "a.patient_id = ?", patientId);
        if ("DENTIST".equals(user.role())) {
            if (user.dentistId() == null) return List.of();
            dentistId = user.dentistId();
        }
        condition(sql, params, "a.dentist_id = ?", dentistId);
        var rows = read(sql.toString(), params);
        return statuses == null || statuses.isEmpty() ? rows : rows.stream().filter(v -> statuses.contains(v.status())).toList();
    }

    private void condition(StringBuilder sql, List<Object> params, String predicate, Object value) {
        if (value != null) {
            sql.append(" and ").append(predicate);
            params.add(value);
        }
    }

    AppointmentView getForUser(long id, CurrentUser user) {
        var view = get(id);
        if ("DENTIST".equals(user.role()) && !Objects.equals(user.dentistId(), view.dentist().id()))
            throw new AccessDeniedException("只能查看自己的预约");
        return view;
    }

    AppointmentView get(long id) {
        return read(SELECT + " and a.id=?", List.of(id)).stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    private List<AppointmentView> read(String sql, List<?> params) {
        var now = LocalDateTime.now(clock).withNano(0);
        return jdbc.sql(sql + " order by a.scheduled_at,a.id,t.id").params(params).query(rs -> {
            var rows = new LinkedHashMap<Long, AppointmentView>();
            while (rs.next()) {
                var id = rs.getLong("id");
                var view = rows.get(id);
                if (view == null) {
                    view = view(rs, now);
                    rows.put(id, view);
                }
                if (rs.getObject("item_id") != null)
                    view.items().add(new Named(rs.getLong("item_id"), rs.getString("item_name")));
            }
            return List.copyOf(rows.values());
        });
    }

    private AppointmentView view(ResultSet row, LocalDateTime now) throws SQLException {
        var at = row.getObject("scheduled_at", LocalDateTime.class);
        var cancelled = row.getObject("cancelled_at", LocalDateTime.class);
        boolean arrived = row.getBoolean("arrived");
        String status = statusOf(at, arrived, cancelled, now);
        return new AppointmentView(row.getLong("id"), status, at,
            new Patient(row.getLong("patient_id"), row.getString("account_no"), row.getString("patient_name")),
            new Dentist(row.getLong("dentist_id"), row.getString("employee_no"), row.getString("dentist_name")),
            new Named(row.getLong("clinic_id"), row.getString("clinic_name")), new ArrayList<>(), arrived,
            cancelled, row.getString("cancelled_by"), row.getObject("visit_id", Long.class), row.getInt("version"));
    }
}
