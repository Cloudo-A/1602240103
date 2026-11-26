package com.adc.publicdata;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public")
class PublicDataController {
    record Clinic(long id, String name, String postalAddress, String phone) {}
    record Dentist(long id, String name, String employeeNo, List<String> qualifications) {}
    record Treatment(long id, String name, String standardUnit, BigDecimal unitPrice) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    PublicDataController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/clinics")
    List<Clinic> clinics() {
        return jdbc.sql("SELECT id,name,postal_address,phone FROM clinic WHERE active ORDER BY id")
            .query(Clinic.class).list();
    }

    @GetMapping("/dentists")
    List<Dentist> dentists() {
        return jdbc.sql("""
            SELECT d.id,d.name,d.employee_no,q.name qualification FROM dentist d
            LEFT JOIN qualification_holding h ON h.dentist_id=d.id AND h.verified_at IS NOT NULL
            LEFT JOIN qualification q ON q.id=h.qualification_id
            WHERE d.active ORDER BY d.employee_no,q.name
            """).query(rs -> {
                Map<Long,Dentist> dentists = new LinkedHashMap<>();
                while (rs.next()) {
                    long id = rs.getLong("id");
                    var dentist = dentists.get(id);
                    if (dentist == null) {
                        dentist = new Dentist(id,rs.getString("name"),rs.getString("employee_no"),new ArrayList<>());
                        dentists.put(id,dentist);
                    }
                    String qualification = rs.getString("qualification");
                    if (qualification != null) dentist.qualifications().add(qualification);
                }
                return List.copyOf(dentists.values());
            });
    }

    @GetMapping("/treatment-items")
    List<Treatment> treatments() {
        return jdbc.sql("""
            SELECT i.id,i.name,i.standard_unit,p.unit_price FROM treatment_item i
            JOIN standard_price p ON p.id = (
                SELECT sp.id FROM standard_price sp WHERE sp.item_id=i.id AND sp.effective_from<=?
                ORDER BY sp.effective_from DESC LIMIT 1
            ) WHERE i.active ORDER BY i.id
            """).param(LocalDate.now(clock)).query(Treatment.class).list();
    }
}
