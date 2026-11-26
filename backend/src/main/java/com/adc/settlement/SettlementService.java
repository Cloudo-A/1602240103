package com.adc.settlement;

import com.adc.auth.CurrentUser;
import com.adc.common.BusinessException;
import com.adc.common.OperationLogService;
import com.adc.settlement.SettlementDtos.*;
import com.adc.visit.VisitDtos.PriceLine;
import com.adc.visit.VisitQueries;
import com.adc.visit.VisitQueries.Billing;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
class SettlementService {
    private static final String SELECT = """
        SELECT settlement_no,visit_id,issued_at,issued_by,patient_account_no,patient_name,clinic_name,dentist_name,total
        FROM settlement
        """;
    private final JdbcClient jdbc;
    private final VisitQueries visits;
    private final Clock clock;
    private final OperationLogService logs;
    private final ObjectMapper json;

    SettlementService(JdbcClient jdbc, VisitQueries visits, Clock clock, OperationLogService logs, ObjectMapper json) {
        this.jdbc = jdbc;
        this.visits = visits;
        this.clock = clock;
        this.logs = logs;
        this.json = json;
    }

    Preview preview(long id) {
        var billing = visits.billing(id, false);
        if (billing.settlementNo() != null) {
            return new Preview(true, detail(billing.settlementNo()), null, null, null);
        }
        requireConfirmed(billing);
        return new Preview(false, null, billing.lines(), billing.total(), token(billing));
    }

    @Transactional
    Issued issue(long id, String checkToken, CurrentUser user) {
        var billing = visits.billing(id, true);
        if (billing.settlementNo() != null) {
            return new Issued(detail(billing.settlementNo()), false);
        }
        requireConfirmed(billing);
        if (checkToken == null || checkToken.isBlank()) {
            throw BusinessException.invalid("checkToken", "缺少核对标识，请重新预览");
        }
        if (!token(billing).equals(checkToken)) throw BusinessException.changed(null);

        var now = LocalDateTime.now(clock).withNano(0);
        String number = nextNumber(now.toLocalDate());
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO settlement
                (settlement_no,visit_id,total,issued_at,issued_by,patient_account_no,patient_name,clinic_name,dentist_name)
            VALUES (?,?,?,?,?,?,?,?,?)
            """).params(number, id, billing.total(), now, user.displayName(), billing.accountNo(),
                billing.patientName(), billing.clinicName(), billing.dentistName()).update(keys, "id");
        var result = new Slip(number, id, now, user.displayName(), new Patient(billing.accountNo(), billing.patientName()),
            new Name(billing.clinicName()), new Name(billing.dentistName()), billing.lines(), billing.total());
        logs.record("ISSUE_SETTLEMENT", "SETTLEMENT", Objects.requireNonNull(keys.getKey()).longValue(), result);
        return new Issued(result, true);
    }

    private static void requireConfirmed(Billing billing) {
        if ("NO_TREATMENT".equals(billing.status())) {
            throw BusinessException.stateNotAllowed("这次就诊未实施治疗，无需开具结算单");
        }
        if (!"CONFIRMED".equals(billing.status()) || billing.lines().isEmpty()) {
            throw BusinessException.stateNotAllowed("牙医尚未确认这次诊疗，暂时不能开具结算单");
        }
    }

    private String nextNumber(LocalDate day) {
        // The upsert locks this day's counter until the slip and its audit record commit.
        jdbc.sql("""
            INSERT INTO settlement_sequence (issue_date,last_number) VALUES (?,0)
            ON DUPLICATE KEY UPDATE last_number=last_number
            """).param(day).update();
        int number = jdbc.sql("SELECT last_number FROM settlement_sequence WHERE issue_date=? FOR UPDATE")
            .param(day).query(Integer.class).single() + 1;
        if (number > 9999) throw BusinessException.stateNotAllowed("今天的结算单号已用完");
        jdbc.sql("UPDATE settlement_sequence SET last_number=? WHERE issue_date=?").params(number, day).update();
        return "JS" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format(Locale.ROOT, "%04d", number);
    }

    private String token(Billing billing) {
        // Cover the visit revision, frozen amounts and the names that would appear on the slip.
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(billing));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    Slip detail(String number) {
        var saved = jdbc.sql(SELECT + " WHERE settlement_no=?").param(number).query(Saved.class).optional()
            .orElseThrow(BusinessException::notFound);
        return saved.withLines(visits.savedLines(List.of(saved.visitId())).getOrDefault(saved.visitId(), List.of()));
    }

    List<Slip> list(LocalDate from, LocalDate to, String keyword) {
        if (from == null && to == null) from = to = LocalDate.now(clock);
        var sql = new StringBuilder(SELECT + " WHERE 1=1");
        Map<String,Object> parameters = new HashMap<>();
        if (from != null) {
            sql.append(" AND issued_at >= :from");
            parameters.put("from", from.atStartOfDay());
        }
        if (to != null) {
            sql.append(" AND DATE(issued_at) <= :to");
            parameters.put("to", to);
        }
        if (!keyword.isEmpty()) {
            sql.append("""
                 AND (LOCATE(LOWER(:keyword), LOWER(settlement_no)) > 0
                      OR LOCATE(:keyword, patient_name) > 0
                      OR LOCATE(LOWER(:keyword), LOWER(patient_account_no)) > 0)
                """);
            parameters.put("keyword", keyword);
        }
        var saved = jdbc.sql(sql + " ORDER BY issued_at DESC,id DESC").params(parameters).query(Saved.class).list();
        var lines = visits.savedLines(saved.stream().map(Saved::visitId).toList());
        return saved.stream().map(slip -> slip.withLines(lines.getOrDefault(slip.visitId(), List.of()))).toList();
    }

    private record Saved(String settlementNo, long visitId, LocalDateTime issuedAt, String issuedBy,
                         String patientAccountNo, String patientName, String clinicName, String dentistName,
                         BigDecimal total) {
        Slip withLines(List<PriceLine> lines) {
            return new Slip(settlementNo, visitId, issuedAt, issuedBy, new Patient(patientAccountNo, patientName),
                new Name(clinicName), new Name(dentistName), lines, total);
        }
    }
}
