package com.adc;

import com.adc.demo.DailyDemoAppointments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class BusinessDemoDataTests extends ContractHttp {
    @Autowired JdbcClient jdbc;
    @Autowired DailyDemoAppointments demo;

    @Test void historicalOutcomesAndSettlementSnapshotsMatchTheMock() {
        var outcomes = jdbc.sql("""
            SELECT CASE WHEN a.cancelled_at IS NOT NULL THEN 'C' WHEN NOT a.arrived THEN 'P'
                WHEN v.no_treatment_reason IS NOT NULL THEN 'N' WHEN s.id IS NOT NULL THEN 'S'
                WHEN v.confirmed_at IS NOT NULL THEN 'K' ELSE 'A' END
            FROM appointment a JOIN patient p ON p.id = a.patient_id
            LEFT JOIN visit v ON v.appointment_id = a.id LEFT JOIN settlement s ON s.visit_id = v.id
            WHERE a.scheduled_at >= '2026-07-01' AND a.scheduled_at < '2026-11-19'
              AND p.account_no BETWEEN 'P000001' AND 'P000008' ORDER BY a.scheduled_at
            """).query(String.class).list();
        assertThat(outcomes).containsExactly(
            "S","S","C","S","S","P","S", // July
            "C","S","S","N",             // August
            "C","S","S","P","C","K",     // September
            "S","S","C","S",             // October
            "P","S","C","N","S");        // November
        var totals = jdbc.sql("""
            SELECT total FROM settlement WHERE settlement_no >= 'JS20260701-' AND settlement_no < 'JS20261119-'
            ORDER BY settlement_no
            """).query(BigDecimal.class).list();
        assertThat(totals).usingElementComparator(BigDecimal::compareTo)
            .containsExactlyElementsOf(List.of("170","1302.50","560","50","270","280","400","315","50",
                "150","280","252.50","600","150").stream().map(BigDecimal::new).toList());
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM settlement s JOIN visit v ON v.id = s.visit_id
            JOIN appointment a ON a.id = v.appointment_id
            WHERE s.settlement_no IN ('JS20260703-0001','JS20260703-0002')
              AND v.arrived_at = a.scheduled_at + INTERVAL 5 MINUTE
              AND v.confirmed_at = a.scheduled_at + INTERVAL 45 MINUTE
              AND s.issued_at = a.scheduled_at + INTERVAL 70 MINUTE AND s.issued_by = '王丽'
            """).query(Integer.class).single()).isEqualTo(2);
    }

    @Test void nextDemoDayIsCreatedOnceWithBothVisitsAndItsConfirmedLine() {
        LocalDate day = LocalDate.of(2101, 2, 3);
        demo.seedIfEmpty(day);
        var appointments = daySlots(day);
        assertThat(appointments).containsExactly("09:00 P000001 D001", "09:30 P000005 D002",
            "10:30 P000003 D001", "14:00 P000007 D002", "15:30 P000002 D001", "16:00 P000004 D003");
        demo.seedIfEmpty(day);
        assertThat(daySlots(day)).isEqualTo(appointments);
        var visits = jdbc.sql("""
            SELECT CONCAT(DATE_FORMAT(v.arrived_at,'%H:%i'), ' ', COALESCE(DATE_FORMAT(v.confirmed_at,'%H:%i'),'-'),
                       ' ', COALESCE(t.item_name,'-'), ' ', COALESCE(s.settlement_no,'-'))
            FROM visit v LEFT JOIN treatment t ON t.visit_id = v.id LEFT JOIN settlement s ON s.visit_id = v.id
            WHERE v.arrived_at >= ? AND v.arrived_at < ? ORDER BY v.arrived_at
            """).params(day, day.plusDays(1)).query(String.class).list();
        assertThat(visits).containsExactly("09:05 - - -", "09:35 10:15 树脂补牙 -");
    }

    @Test void aDayWithOneExistingAppointmentIsPreserved() {
        var day = LocalDate.of(2101, 2, 4);
        jdbc.sql("""
            INSERT INTO appointment (patient_id, dentist_id, clinic_id, scheduled_at)
            SELECT patient_id, dentist_id, clinic_id, ? FROM appointment
            WHERE scheduled_at = '2026-07-03 09:00' LIMIT 1
            """).param(day.atTime(8, 15)).update();
        var before = daySlots(day);
        assertThat(before).hasSize(1);
        demo.seedIfEmpty(day);
        assertThat(daySlots(day)).isEqualTo(before);
    }

    private List<String> daySlots(LocalDate day) {
        return jdbc.sql("""
            SELECT CONCAT(DATE_FORMAT(a.scheduled_at,'%H:%i'), ' ', p.account_no, ' ', d.employee_no)
            FROM appointment a JOIN patient p ON p.id = a.patient_id JOIN dentist d ON d.id = a.dentist_id
            WHERE a.scheduled_at >= ? AND a.scheduled_at < ? ORDER BY a.scheduled_at
            """).params(day, day.plusDays(1)).query(String.class).list();
    }
}
