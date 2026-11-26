package com.adc.stats;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
class StatsService {
    record CancellationRow(long clinicId, String clinicName, long total, long cancelled, long pendingVerify, BigDecimal rate) {}
    record CancellationReport(LocalDate from, LocalDate to, @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss") LocalDateTime generatedAt,
                              boolean periodEnded, List<CancellationRow> rows) {}
    record DemandRow(long clinicId, String clinicName, long itemId, String itemName, String unitName,
                     long intendedCount, BigDecimal actualQuantity) {}
    record Employee(String employeeNo, String name) {}
    record Qualification(long qualificationId, String name, int verifiedCount, List<Employee> dentists) {}
    record Incomplete(String employeeNo, String name, String issue) {}
    record DemandReport(List<DemandRow> demand, List<Qualification> qualifications, List<Incomplete> incomplete) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    StatsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private Map<String,Object> parameters(LocalDate from, LocalDate to, List<Long> clinics) {
        var params = new HashMap<String,Object>();
        params.put("start", from.atStartOfDay());
        params.put("end", to.atTime(23,59,59)); // V1 stores appointment and arrival times to the second.
        if (clinics != null && !clinics.isEmpty()) params.put("clinics", clinics);
        return params;
    }

    CancellationReport cancellations(LocalDate from, LocalDate to, List<Long> clinics) {
        var now = LocalDateTime.now(clock).withNano(0);
        var params = parameters(from, to, clinics);
        params.put("now", now);
        var sql = """
            select c.id,c.name,count(a.id) total,count(a.cancelled_at) cancelled,
                   count(case when a.cancelled_at is null and not a.arrived and a.scheduled_at < :now then 1 end) pending
            from clinic c left join appointment a on a.clinic_id=c.id and a.scheduled_at between :start and :end
            """ + (params.containsKey("clinics") ? " where c.id in (:clinics)" : "") + " group by c.id,c.name order by c.id";
        var rows = jdbc.sql(sql).params(params).query((r,n) -> {
            long total = r.getLong("total");
            long cancelled = r.getLong("cancelled");
            var rate = total == 0 ? null : BigDecimal.valueOf(cancelled).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            return new CancellationRow(r.getLong("id"), r.getString("name"), total, cancelled, r.getLong("pending"), rate);
        }).list();
        return new CancellationReport(from, to, now, to.isBefore(now.toLocalDate()), rows);
    }

    DemandReport demand(LocalDate from, LocalDate to, List<Long> clinics) {
        var params = parameters(from, to, clinics);
        var sql = """
            with counts as (
                select a.clinic_id, ai.item_id, count(*) intended, 0 actual
                from appointment a join appointment_item ai on ai.appointment_id=a.id
                where a.cancelled_at is null and a.scheduled_at between :start and :end
                group by a.clinic_id, ai.item_id
                union all
                select v.clinic_id, t.item_id, 0 intended, sum(t.quantity) actual
                from visit v join treatment t on t.visit_id=v.id left join settlement s on s.visit_id=v.id
                where v.arrived_at between :start and :end
                  and (s.id is not null or (v.confirmed_at is not null and v.no_treatment_reason is null))
                group by v.clinic_id, t.item_id
            )
            select c.id,c.name,i.id item_id,i.name item_name,i.standard_unit,
                   sum(x.intended) intended,sum(x.actual) actual
            from counts x join clinic c on c.id=x.clinic_id join treatment_item i on i.id=x.item_id
            """ + (params.containsKey("clinics") ? " where c.id in (:clinics)" : "") + """
             group by c.id,c.name,i.id,i.name,i.standard_unit order by c.id,i.id
            """;
        var rows = jdbc.sql(sql).params(params).query((r,n) -> new DemandRow(r.getLong("id"),r.getString("name"),
            r.getLong("item_id"),r.getString("item_name"),r.getString("standard_unit"),r.getLong("intended"),r.getBigDecimal("actual"))).list();
        return new DemandReport(rows, qualifications(), incomplete());
    }

    private List<Qualification> qualifications() {
        // Each (dentist,qualification) holding is unique by the V1 primary key.
        return jdbc.sql("""
            select q.id,q.name,d.employee_no,d.name dentist_name
            from qualification q left join (qualification_holding h join dentist d on d.id=h.dentist_id and d.active)
                on h.qualification_id=q.id and h.verified_at is not null
            order by q.id,d.id
            """).query(rs -> {
                var rows = new ArrayList<Qualification>();
                long id = -1;
                String name = null;
                var dentists = new ArrayList<Employee>();
                while (rs.next()) {
                    long nextId = rs.getLong("id");
                    if (nextId != id) {
                        if (name != null) rows.add(new Qualification(id, name, dentists.size(), dentists));
                        id = nextId;
                        name = rs.getString("name");
                        dentists = new ArrayList<>();
                    }
                    if (rs.getString("employee_no") != null)
                        dentists.add(new Employee(rs.getString("employee_no"), rs.getString("dentist_name")));
                }
                if (name != null) rows.add(new Qualification(id, name, dentists.size(), dentists));
                return rows;
            });
    }

    private List<Incomplete> incomplete() {
        return jdbc.sql("""
            select issues.employee_no,issues.name,issues.issue from (
                select d.id,d.employee_no,d.name,0 category,q.id qualification_id,concat(q.name,'资质待核实') issue
                from dentist d join qualification_holding h on h.dentist_id=d.id
                join qualification q on q.id=h.qualification_id where d.active and h.verified_at is null
                union all
                select d.id,d.employee_no,d.name,1,0,'暂无已核实资质' from dentist d
                where d.active and not exists
                    (select 1 from qualification_holding h where h.dentist_id=d.id and h.verified_at is not null)
            ) issues order by issues.id,issues.category,issues.qualification_id
            """).query((r,n) -> new Incomplete(r.getString("employee_no"),r.getString("name"),r.getString("issue"))).list();
    }
}
