package com.adc.appointmentrequest;

import com.adc.appointment.AppointmentView;
import com.adc.common.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static com.adc.appointmentrequest.AppointmentRequestDtos.*;

@Repository
class AppointmentRequestQueries {
    private static final String SELECT = """
        select r.*, c.name clinic_name,c.phone clinic_phone,
            t.id item_id,t.name item_name,a.scheduled_at,a.cancelled_at,a.arrived,
            a.dentist_id,d.name dentist_name,a.clinic_id booked_clinic_id,b.name booked_clinic_name
        from appointment_request r join clinic c on c.id=r.clinic_id
        left join appointment_request_item ri on ri.request_id=r.id left join treatment_item t on t.id=ri.item_id
        left join appointment a on a.id=r.appointment_id left join dentist d on d.id=a.dentist_id
        left join clinic b on b.id=a.clinic_id
        """;
    private final JdbcClient jdbc;
    private final Clock clock;

    AppointmentRequestQueries(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<View> mine(long accountId) {
        return read("where r.patient_account_id=:account", Map.of("account",accountId));
    }

    View get(long id) {
        return read("where r.id=:id",Map.of("id",id)).stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    List<View> list(List<String> statuses, Long clinicId, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            to = LocalDate.now(clock);
            from = to.minusDays(29);
        }
        if (from != null && to != null && from.isAfter(to)) throw BusinessException.invalid("from","开始日期不能晚于结束日期");
        var sql = new StringBuilder("where 1=1");
        Map<String,Object> params = new HashMap<>();
        if (from != null) { sql.append(" and r.created_at >= :start"); params.put("start",from.atStartOfDay()); }
        if (to != null) { sql.append(" and r.created_at <= :end"); params.put("end",to.atTime(23,59,59)); }
        if (clinicId != null) { sql.append(" and r.clinic_id=:clinic"); params.put("clinic",clinicId); }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" and r.status in (:statuses)");
            params.put("statuses",statuses);
        }
        return read(sql.toString(),params);
    }

    private List<View> read(String predicate, Map<String,?> params) {
        var now = LocalDateTime.now(clock).withNano(0);
        return jdbc.sql(SELECT + predicate + " order by r.created_at desc,r.id desc,t.id").params(params).query(rs -> {
            var result = new LinkedHashMap<Long,View>();
            while (rs.next()) {
                long id = rs.getLong("id");
                var current = result.get(id);
                if (current == null) {
                    current = view(rs,now);
                    result.put(id,current);
                }
                if (rs.getObject("item_id") != null) current.items().add(new Named(rs.getLong("item_id"),rs.getString("item_name")));
            }
            return List.copyOf(result.values());
        });
    }

    private View view(ResultSet r, LocalDateTime now) throws SQLException {
        Booked booked = null;
        if (r.getObject("appointment_id") != null) {
            var scheduled = r.getObject("scheduled_at",LocalDateTime.class);
            var status = AppointmentView.statusOf(scheduled,r.getBoolean("arrived"),r.getObject("cancelled_at",LocalDateTime.class),now);
            booked = new Booked(r.getLong("appointment_id"),status,scheduled,
                new Named(r.getLong("dentist_id"),r.getString("dentist_name")),
                new Named(r.getLong("booked_clinic_id"),r.getString("booked_clinic_name")));
        }
        return new View(r.getLong("id"),r.getString("status"),r.getObject("created_at",LocalDateTime.class),
            r.getString("contact_name"),r.getString("contact_phone"),
            new Clinic(r.getLong("clinic_id"),r.getString("clinic_name"),r.getString("clinic_phone")),
            r.getObject("preferred_date",LocalDate.class),r.getString("time_slot"),new ArrayList<>(),r.getString("note"),
            r.getObject("handled_at",LocalDateTime.class),r.getString("handled_by"),r.getString("invalid_reason"),
            r.getObject("withdrawn_at",LocalDateTime.class),booked,r.getInt("version"));
    }
}
