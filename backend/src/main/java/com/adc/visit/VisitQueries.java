package com.adc.visit;

import com.adc.auth.CurrentUser;
import com.adc.common.BusinessException;
import com.adc.visit.VisitDtos.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VisitQueries {
    private static final String STATUS = """
        CASE WHEN s.id IS NOT NULL THEN 'SETTLED'
             WHEN v.confirmed_at IS NULL THEN 'UNCONFIRMED'
             WHEN v.no_treatment_reason IS NOT NULL THEN 'NO_TREATMENT'
             WHEN EXISTS(SELECT 1 FROM treatment t WHERE t.visit_id=v.id) THEN 'CONFIRMED'
             ELSE 'UNCONFIRMED' END
        """;
    private static final String BASE = """
        SELECT v.id,v.appointment_id,v.arrived_at,v.confirmed_at,v.problem,v.special_needs,v.related_history,
               v.no_treatment_reason,v.version,s.settlement_no,p.id patient_id,p.account_no,p.name patient_name,
               p.allergy_verified_at,c.id clinic_id,c.name clinic_name,d.id dentist_id,d.name dentist_name,
        """ + STATUS + """
         AS status FROM visit v JOIN appointment a ON a.id=v.appointment_id JOIN patient p ON p.id=a.patient_id
        JOIN clinic c ON c.id=v.clinic_id JOIN dentist d ON d.id=v.dentist_id LEFT JOIN settlement s ON s.visit_id=v.id
        """;
    private final JdbcClient jdbc;
    private final Clock clock;

    public VisitQueries(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private record Header(long id, long appointmentId, LocalDateTime arrivedAt, LocalDateTime confirmedAt,
                          String problem, String specialNeeds, String relatedHistory, String noTreatmentReason,
                          int version, String settlementNo, String status, PatientRef patient,
                          LocalDateTime allergyVerifiedAt, Named clinic, Named dentist) {}

    private static Header header(ResultSet r, int n) throws SQLException {
        return new Header(r.getLong("id"), r.getLong("appointment_id"), r.getObject("arrived_at",LocalDateTime.class),
            r.getObject("confirmed_at", LocalDateTime.class),r.getString("problem"),r.getString("special_needs"),
            r.getString("related_history"),r.getString("no_treatment_reason"),r.getInt("version"),r.getString("settlement_no"),
            r.getString("status"),new PatientRef(r.getLong("patient_id"),r.getString("account_no"),r.getString("patient_name")),
            r.getObject("allergy_verified_at",LocalDateTime.class),new Named(r.getLong("clinic_id"),r.getString("clinic_name")),
            new Named(r.getLong("dentist_id"),r.getString("dentist_name")));
    }

    private Header one(long id) {
        return jdbc.sql(BASE + " WHERE v.id=?")
            .param(id).query(VisitQueries::header).optional().orElseThrow(BusinessException::notFound);
    }

    static void authorize(CurrentUser user, long dentistId) {
        if ("DENTIST".equals(user.role()) && !Objects.equals(user.dentistId(), dentistId)) {
            throw new AccessDeniedException("只能查看自己接诊的就诊");
        }
    }

    public Map<Long,List<PriceLine>> savedLines(Collection<Long> visitIds) {
        if (visitIds.isEmpty()) return Map.of();
        return jdbc.sql("""
            SELECT visit_id,item_id,item_name,unit_name,quantity,unit_price,ROUND(quantity*unit_price,2) subtotal
            FROM treatment WHERE visit_id IN (:ids) ORDER BY visit_id,id
            """).param("ids",visitIds).query(rs -> {
                Map<Long,List<PriceLine>> result = new HashMap<>();
                while (rs.next()) {
                    var line = new PriceLine(rs.getLong(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5),
                        rs.getBigDecimal(6),rs.getBigDecimal(7),null);
                    result.computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>()).add(line);
                }
                return result;
            });
    }

    private static BigDecimal total(Header h, List<PriceLine> lines) {
        if (!Set.of("CONFIRMED","SETTLED").contains(h.status)) return null;
        return lines.stream().map(PriceLine::subtotal).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    public record Billing(long id, int version, String status, String settlementNo, String accountNo,
                          String patientName, String clinicName, String dentistName, List<PriceLine> lines, BigDecimal total) {}

    @Transactional
    public Billing billing(long id, boolean lock) {
        if (lock) {
            jdbc.sql("SELECT id FROM visit WHERE id=? FOR UPDATE").param(id)
                .query(Long.class).optional().orElseThrow(BusinessException::notFound);
        }
        Header h = one(id);
        var lines = savedLines(List.of(id)).getOrDefault(id,List.of());
        return new Billing(id,h.version,h.status,h.settlementNo,h.patient.accountNo(),h.patient.name(),
            h.clinic.name(),h.dentist.name(),lines,total(h,lines));
    }

    List<Summary> list(LocalDate from, LocalDate to, String status, Long dentistId, CurrentUser user) {
        if (from == null && to == null) from = to = LocalDate.now(clock);
        var sql = new StringBuilder(BASE + " WHERE 1=1");
        Map<String,Object> params = new HashMap<>();
        if (from != null) { sql.append(" AND v.arrived_at >= :from"); params.put("from",from.atStartOfDay()); }
        if (to != null) { sql.append(" AND DATE(v.arrived_at) <= :to"); params.put("to",to); }
        if ("DENTIST".equals(user.role())) {
            if (user.dentistId() == null) return List.of();
            dentistId = user.dentistId();
        }
        if (dentistId != null) { sql.append(" AND v.dentist_id = :dentist"); params.put("dentist",dentistId); }
        if (status != null && !status.isBlank()) {
            sql.append(" AND ").append(STATUS).append(" IN (:statuses)");
            params.put("statuses", Arrays.stream(status.split(",", -1)).map(String::strip).toList());
        }
        return summaries(jdbc.sql(sql + " ORDER BY v.arrived_at,v.id").params(params).query(VisitQueries::header).list());
    }

    public List<Summary> confirmedUnsettled(LocalDate from, LocalDate through) {
        var visits = jdbc.sql(BASE + " WHERE v.arrived_at >= ? AND v.arrived_at <= ? AND " + STATUS
                + " = 'CONFIRMED' ORDER BY v.arrived_at,v.id")
            .params(from.atStartOfDay(), through.atTime(23,59,59)).query(VisitQueries::header).list();
        return summaries(visits);
    }

    private List<Summary> summaries(List<Header> visits) {
        var lines = savedLines(visits.stream().map(Header::id).toList());
        return visits.stream().map(h -> new Summary(h.id,h.status,h.arrivedAt,h.patient,h.clinic,h.dentist,
            total(h,lines.getOrDefault(h.id,List.of())))).toList();
    }

    public Detail detail(long id, CurrentUser user) {
        Header h = one(id);
        authorize(user,h.dentist.id());
        var allergy = jdbc.sql("""
            SELECT a.id,a.name FROM patient_allergy pa JOIN allergy a ON a.id=pa.allergy_id
            WHERE pa.patient_id=? ORDER BY a.id
            """).param(h.patient.id()).query(Named.class).list();
        var patient = new PatientInfo(h.patient.id(),h.patient.accountNo(),h.patient.name(),allergy,h.allergyVerifiedAt);
        var items = jdbc.sql("""
            SELECT i.id,i.name FROM appointment_item ai JOIN treatment_item i ON i.id=ai.item_id
            WHERE ai.appointment_id=? ORDER BY i.id
            """).param(h.appointmentId).query(Named.class).list();
        var lines = savedLines(List.of(id)).getOrDefault(id,List.of());
        return new Detail(id,h.status,h.arrivedAt,h.confirmedAt,patient,h.clinic,h.dentist,items,h.problem,
            h.specialNeeds,h.relatedHistory,h.noTreatmentReason,lines,total(h,lines),h.settlementNo,h.version);
    }

    public List<History> history(long patientId) {
        var visits = jdbc.sql(BASE + " WHERE p.id=? AND " + STATUS + " <> 'UNCONFIRMED' ORDER BY v.arrived_at DESC,v.id DESC")
            .param(patientId).query(VisitQueries::header).list();
        var lines = savedLines(visits.stream().map(Header::id).toList());
        return visits.stream().map(h -> new History(h.id,h.arrivedAt,h.status,h.clinic,h.dentist,h.problem,
            h.specialNeeds,h.relatedHistory,h.noTreatmentReason,lines.getOrDefault(h.id,List.of()).stream()
                .map(t -> new HistoryLine(t.itemName(),t.unitName(),t.quantity(),t.unitPrice(),t.subtotal())).toList())).toList();
    }
}
