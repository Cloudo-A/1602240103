package com.adc.options;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/options")
class OptionsController {
    record Named(long id, String name) {}
    record Clinic(long id, String name, boolean active) {}
    record Dentist(long id, String employeeNo, String name, boolean active, List<String> verifiedQualifications) {}
    record Treatment(long id, String name, String standardUnit, int quantityScale, BigDecimal currentPrice, boolean active) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    OptionsController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/clinics")
    List<Clinic> clinics(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return jdbc.sql("select id, name, active from clinic where (? = false or active = true) order by id")
            .param(activeOnly).query((rs, row) -> new Clinic(rs.getLong("id"),
                rs.getString("name"), rs.getBoolean("active"))).list();
    }

    @GetMapping("/dentists")
    List<Dentist> dentists(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return jdbc.sql("""
            select d.id, d.employee_no, d.name, d.active, q.name as qualification
            from dentist d
            left join qualification_holding h on h.dentist_id = d.id and h.verified_at is not null
            left join qualification q on q.id = h.qualification_id
            where (? = false or d.active = true)
            order by d.id, q.id
            """).param(activeOnly).query(rs -> {
                var dentists = new LinkedHashMap<Long, Dentist>();
                while (rs.next()) {
                    long id = rs.getLong("id");
                    Dentist dentist = dentists.get(id);
                    if (dentist == null) {
                        dentist = new Dentist(id, rs.getString("employee_no"), rs.getString("name"),
                            rs.getBoolean("active"), new ArrayList<>());
                        dentists.put(id, dentist);
                    }
                    String name = rs.getString("qualification");
                    if (name != null) dentist.verifiedQualifications().add(name);
                }
                return List.copyOf(dentists.values());
            });
    }

    @GetMapping("/treatment-items")
    List<Treatment> treatments(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return jdbc.sql("""
            select i.id, i.name, i.standard_unit, i.quantity_scale, i.active,
              (select p.unit_price from standard_price p
               where p.item_id = i.id and p.effective_from <= ?
               order by p.effective_from desc limit 1) as current_price
            from treatment_item i where (? = false or i.active = true) order by i.id
            """).params(LocalDate.now(clock), activeOnly).query((rs, row) -> new Treatment(rs.getLong("id"),
                rs.getString("name"), rs.getString("standard_unit"), rs.getInt("quantity_scale"),
                rs.getBigDecimal("current_price"), rs.getBoolean("active"))).list();
    }

    @GetMapping("/allergies")
    List<Named> allergies() {
        return names("select id, name from allergy order by id");
    }

    @GetMapping("/qualifications")
    List<Named> qualifications() {
        return names("select id, name from qualification order by id");
    }

    private List<Named> names(String query) {
        return jdbc.sql(query).query((rs, row) -> new Named(rs.getLong("id"), rs.getString("name"))).list();
    }
}
