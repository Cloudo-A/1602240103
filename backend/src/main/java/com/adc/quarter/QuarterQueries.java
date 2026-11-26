package com.adc.quarter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static com.adc.quarter.QuarterCalculation.Period;
import static com.adc.quarter.QuarterDtos.*;

@Repository
class QuarterQueries {
    private static final String HEADERS = """
        SELECT id,year,quarter,confirmed_at,first_confirmed_at,company_net_profit,incentive_fund,fund_transfer_at,fund_note
        FROM quarter_close
        """;
    private final JdbcClient jdbc;

    QuarterQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    private record Header(long id, int year, int quarter, LocalDateTime confirmedAt, LocalDateTime firstConfirmedAt,
                          BigDecimal companyNetProfit, BigDecimal incentiveFund, LocalDateTime fundTransferAt, String fundNote) {}

    List<Closed> list() {
        return closed(jdbc.sql(HEADERS + " ORDER BY year DESC, quarter DESC").query(Header.class).list());
    }

    Closed find(Period period) {
        var headers = jdbc.sql(HEADERS + " WHERE year=? AND quarter=?")
            .params(period.year(),period.quarter()).query(Header.class).list();
        return closed(headers).stream().findFirst().orElse(null);
    }

    private List<Closed> closed(List<Header> headers) {
        if (headers.isEmpty()) return List.of();
        var rows = jdbc.sql("""
            SELECT p.quarter_close_id,p.dentist_id,d.employee_no,d.name,p.revenue,p.supply_cost,p.operating_cost,p.basis
            FROM dentist_quarter_performance p JOIN dentist d ON d.id=p.dentist_id
            WHERE p.quarter_close_id IN (:ids) ORDER BY p.quarter_close_id,p.dentist_id
            """).param("ids", headers.stream().map(Header::id).toList()).query(rs -> {
                Map<Long,List<Row>> result = new HashMap<>();
                while (rs.next()) {
                    var revenue = rs.getBigDecimal("revenue");
                    var supply = rs.getBigDecimal("supply_cost");
                    var operating = rs.getBigDecimal("operating_cost");
                    var row = new Row(rs.getLong("dentist_id"), rs.getString("employee_no"), rs.getString("name"),
                        revenue, supply, operating, rs.getString("basis"), revenue.subtract(supply).subtract(operating));
                    result.computeIfAbsent(rs.getLong("quarter_close_id"), ignored -> new ArrayList<>()).add(row);
                }
                return result;
            });
        return headers.stream().map(h -> new Closed(h.id,h.year,h.quarter,h.confirmedAt,h.firstConfirmedAt,
            QuarterCalculation.workdays(Period.of(h.year,h.quarter).end(), h.firstConfirmedAt.toLocalDate()),
            h.companyNetProfit,h.incentiveFund,h.companyNetProfit.signum()>0 ? "ACCRUED" : "TRANSFERRED",
            h.fundTransferAt,h.fundNote,rows.getOrDefault(h.id,List.of()))).toList();
    }

    List<PreparedRow> roster(Period period) {
        return jdbc.sql("""
            SELECT d.id dentist_id,d.employee_no,d.name,COALESCE(income.revenue,0.00) revenue,
                   p.supply_cost,p.operating_cost,p.basis
            FROM dentist d
            LEFT JOIN (
                SELECT v.dentist_id,SUM(s.total) revenue FROM visit v JOIN settlement s ON s.visit_id=v.id
                WHERE v.arrived_at>=? AND v.arrived_at<=? GROUP BY v.dentist_id
            ) income ON income.dentist_id=d.id
            LEFT JOIN quarter_close q ON q.year=? AND q.quarter=?
            LEFT JOIN dentist_quarter_performance p ON p.quarter_close_id=q.id AND p.dentist_id=d.id
            WHERE d.active OR income.revenue>0 ORDER BY d.id
            """).params(period.start().atStartOfDay(), period.end().atTime(23,59,59), period.year(), period.quarter())
            .query(PreparedRow.class).list();
    }

    void lockInputs(Period period) {
        // A visit lock is also the first lock taken by treatment correction and settlement issuance.
        // Use the arrival-date index so new arrivals into the closing quarter cannot appear mid-confirmation.
        jdbc.sql("""
            SELECT id FROM visit FORCE INDEX (idx_visit_arrived_at)
            WHERE arrived_at >= ? AND arrived_at <= ? ORDER BY arrived_at,id FOR UPDATE
            """).params(period.start().atStartOfDay(), period.end().atTime(23,59,59)).query(Long.class).list();
        // Protect the active roster and prevent inserts while validating its exact membership.
        jdbc.sql("SELECT id FROM dentist ORDER BY id FOR SHARE").query(Long.class).list();
    }

    Closed replace(Period period, Preview result, LocalDateTime at, String note) {
        LocalDateTime transferAt = result.fundNoteRequired() ? at : null;
        jdbc.sql("""
            INSERT INTO quarter_close (year,quarter,confirmed_at,first_confirmed_at,company_net_profit,
                                       incentive_fund,fund_transfer_at,fund_note)
            VALUES (?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE confirmed_at=?,company_net_profit=?,incentive_fund=?,
                                    fund_transfer_at=?,fund_note=?,version=version+1
            """).params(period.year(),period.quarter(),at,at,result.companyNetProfit(),result.incentiveFund(),transferAt,note,
                at,result.companyNetProfit(),result.incentiveFund(),transferAt,note).update();
        // A locking read also sees a concurrent first confirmation after waiting on the unique quarter key.
        long id = jdbc.sql("SELECT id FROM quarter_close WHERE year=? AND quarter=? FOR UPDATE")
            .params(period.year(),period.quarter()).query(Long.class).single();
        jdbc.sql("DELETE FROM dentist_quarter_performance WHERE quarter_close_id=?").param(id).update();
        for (var row : result.rows()) {
            jdbc.sql("""
                INSERT INTO dentist_quarter_performance (quarter_close_id,dentist_id,revenue,supply_cost,operating_cost,basis)
                VALUES (?,?,?,?,?,?)
                """).params(id,row.dentistId(),row.revenue(),row.supplyCost(),row.operatingCost(),row.basis()).update();
        }
        return find(period);
    }
}
