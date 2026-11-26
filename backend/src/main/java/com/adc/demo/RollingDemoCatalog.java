
package com.adc.demo;

import com.adc.appointment.AppointmentView;
import com.adc.auth.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
class RollingDemoCatalog {
    private static final Map<String,String> QUALIFICATION = Map.of(
        "口腔检查","口腔全科", "洁牙","口腔全科", "树脂补牙","口腔全科", "拔牙","口腔全科",
        "局部麻醉","口腔全科", "根管治疗","牙体牙髓", "正畸复诊调整","口腔正畸");

    record Item(long id, String name, String standardUnit, int quantityScale) {
        AppointmentView.Named view() { return new AppointmentView.Named(id, name); }
    }
    record Holding(long dentistId, String name, LocalDateTime verifiedAt) {}
    record Price(long itemId, LocalDate effectiveFrom, BigDecimal unitPrice) {}
    record Dentist(ActiveDentist profile, CurrentUser actor) {}
    record ActiveDentist(long id, String employeeNo, String name) {
        AppointmentView.Dentist view() { return new AppointmentView.Dentist(id, employeeNo, name); }
    }
    record Patient(long id, String accountNo, String name) {
        AppointmentView.Patient view() { return new AppointmentView.Patient(id, accountNo, name); }
    }
    record Account(long id, long patientId, String name, String phone) {}

    record Roster(List<Patient> patients, List<Account> accounts, List<AppointmentView.Named> clinics,
                  List<Dentist> dentists, Map<Long,List<Holding>> holdings, List<Item> items,
                  Map<Long,List<Price>> prices, CurrentUser front) {
        List<Item> eligible(long dentist, LocalDateTime at) {
            var qualifications = holdings.getOrDefault(dentist, List.of()).stream()
                .filter(h -> !h.verifiedAt().isAfter(at)).map(Holding::name).toList();
            return items.stream().filter(i -> qualifications.contains(QUALIFICATION.get(i.name())))
                .filter(i -> price(i.id(), at.toLocalDate()) != null).toList();
        }

        BigDecimal price(long item, LocalDate day) {
            return prices.getOrDefault(item, List.of()).stream().filter(p -> !p.effectiveFrom().isAfter(day))
                .findFirst().map(Price::unitPrice).orElse(null);
        }

        Patient patient(long id) {
            return patients.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
        }
    }

    private final JdbcClient jdbc;
    private final DemoProgress progress;

    RollingDemoCatalog(JdbcClient jdbc, DemoProgress progress) {
        this.jdbc = jdbc;
        this.progress = progress;
    }

    Roster load() {
        var ownership = progress.get("roster").orElseThrow().data();
        var patients = DemoProgress.ids(ownership.get(0));
        var accounts = DemoProgress.ids(ownership.get(1));
        var people = jdbc.sql("SELECT id,account_no,name FROM patient WHERE id IN (:ids) ORDER BY id")
            .param("ids", patients).query(Patient.class).list();
        var contacts = jdbc.sql("SELECT id,name,phone FROM patient_account WHERE id IN (:ids) ORDER BY id")
            .param("ids", accounts).query((r,n) -> new Account(r.getLong(1), patients.get(accounts.indexOf(r.getLong(1))),
                r.getString(2), r.getString(3))).list();
        // Resolve the three original clinics from immutable V7 fixtures, even if headquarters renamed them.
        var clinics = jdbc.sql("""
            SELECT DISTINCT c.id,c.name
            FROM appointment a JOIN patient p ON p.id=a.patient_id JOIN clinic c ON c.id=a.clinic_id
            WHERE c.active AND (p.account_no,a.scheduled_at) IN (
                ('P000001','2026-07-03 09:00:00'),('P000005','2026-07-10 09:30:00'),('P000007','2026-07-15 11:00:00')
            ) ORDER BY c.id
            """).query(AppointmentView.Named.class).list();
        var dentists = jdbc.sql("""
            SELECT d.id,d.employee_no,d.name,u.id user_id,u.username,u.display_name
            FROM dentist d JOIN app_user u ON u.dentist_id=d.id AND u.role='DENTIST'
            WHERE d.active AND d.employee_no IN ('D001','D002','D003','D004') ORDER BY d.employee_no
            """).query((r,n) -> new Dentist(new ActiveDentist(r.getLong("id"), r.getString("employee_no"), r.getString("name")),
                new CurrentUser(r.getLong("user_id"), r.getString("username"), r.getString("display_name"), "DENTIST", r.getLong("id")))).list();
        var holdings = new HashMap<Long,List<Holding>>();
        jdbc.sql("""
            SELECT h.dentist_id,q.name,h.verified_at FROM qualification_holding h
            JOIN qualification q ON q.id=h.qualification_id WHERE h.verified_at IS NOT NULL
            """).query(Holding.class).list().forEach(h -> holdings.computeIfAbsent(h.dentistId(), k -> new ArrayList<>()).add(h));
        var items = jdbc.sql("SELECT id,name,standard_unit,quantity_scale FROM treatment_item WHERE active ORDER BY id")
            .query(Item.class).list().stream().filter(i -> QUALIFICATION.containsKey(i.name())).toList();
        var prices = new HashMap<Long,List<Price>>();
        jdbc.sql("SELECT item_id,effective_from,unit_price FROM standard_price ORDER BY item_id,effective_from DESC")
            .query(Price.class).list().forEach(p -> prices.computeIfAbsent(p.itemId(), k -> new ArrayList<>()).add(p));
        var front = jdbc.sql("SELECT id,username,display_name,role,dentist_id FROM app_user WHERE username='front1' AND role='FRONT_DESK'")
            .query(CurrentUser.class).single();
        return new Roster(people, contacts, clinics, dentists, holdings, items, prices, front);
    }
}
