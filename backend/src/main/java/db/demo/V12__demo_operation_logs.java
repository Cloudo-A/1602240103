package db.demo;

import com.adc.appointment.AppointmentView;
import com.adc.visit.VisitDtos;
import com.adc.visit.VisitQueries;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

public class V12__demo_operation_logs extends BaseJavaMigration {
    // Match the original V7 appointments by business keys, never by assumed generated IDs.
    private static final String HISTORY = """
        2026-07-03T09:00 P000001
        2026-07-03T10:00 P000003
        2026-07-08T14:00 P000002
        2026-07-10T09:30 P000005
        2026-07-15T11:00 P000007
        2026-07-22T15:00 P000004
        2026-07-29T10:00 P000006
        2026-08-05T09:00 P000008
        2026-08-12T14:30 P000001
        2026-08-20T15:00 P000005
        2026-08-26T10:00 P000003
        2026-09-02T09:00 P000002
        2026-09-09T11:30 P000004
        2026-09-12T10:00 P000001
        2026-09-18T16:00 P000006
        2026-09-25T14:00 P000007
        2026-09-29T10:30 P000008
        2026-10-08T09:30 P000002
        2026-10-14T14:00 P000005
        2026-10-20T10:00 P000003
        2026-10-27T15:30 P000006
        2026-11-03T09:00 P000004
        2026-11-06T11:00 P000007
        2026-11-12T14:30 P000001
        2026-11-17T10:00 P000008
        2026-11-18T16:00 P000003
        """;

    private record DailySlot(LocalTime time, String account, LocalDateTime reference) {
        DailySlot(String time, String account, String reference) {
            this(LocalTime.parse(time), account, LocalDateTime.parse(reference));
        }
    }

    private static final List<DailySlot> DAILY = List.of(
        new DailySlot("09:00", "P000001", "2026-07-03T09:00"),
        new DailySlot("09:30", "P000005", "2026-07-10T09:30"),
        new DailySlot("10:30", "P000003", "2026-07-03T10:00"),
        new DailySlot("14:00", "P000007", "2026-10-08T09:30"),
        new DailySlot("15:30", "P000002", "2026-09-12T10:00"),
        new DailySlot("16:00", "P000004", "2026-07-15T11:00")
    );

    private record Actor(long id, String username, Long dentistId) {}
    private record Appointment(long id, LocalDateTime scheduledAt, AppointmentView.Patient patient,
                               AppointmentView.Dentist dentist, AppointmentView.Named clinic,
                               LocalDateTime cancelledAt, String cancelledBy, LocalDateTime allergyVerifiedAt) {}
    private record Visit(long id, LocalDateTime arrivedAt, LocalDateTime registeredAt, LocalDateTime confirmedAt,
                         VisitDtos.Named dentist, VisitDtos.Named clinic, String problem, String specialNeeds,
                         String relatedHistory, String noTreatmentReason) {}
    private record Settlement(long id, String settlementNo, long visitId, LocalDateTime issuedAt, String issuedBy,
                              String patientAccountNo, String patientName, String clinicName, String dentistName,
                              BigDecimal total) {}
    private record Slip(String settlementNo, long visitId, LocalDateTime issuedAt, String issuedBy,
                        PatientName patient, Name clinic, Name dentist, List<VisitDtos.PriceLine> lines, BigDecimal total) {}
    private record PatientName(String accountNo, String name) {}
    private record Name(String name) {}
    private record Quarter(long id, int year, int quarter, LocalDateTime confirmedAt, LocalDateTime firstConfirmedAt,
                           BigDecimal companyNetProfit, BigDecimal incentiveFund, LocalDateTime fundTransferAt, String fundNote) {}
    private record QuarterRow(long dentistId, String employeeNo, String name, BigDecimal revenue,
                              BigDecimal supplyCost, BigDecimal operatingCost, String basis, BigDecimal netProfit) {}
    private record ClosedQuarter(long id, int year, int quarter, LocalDateTime confirmedAt, LocalDateTime firstConfirmedAt,
                                 long collectionWorkdays, BigDecimal companyNetProfit, BigDecimal incentiveFund,
                                 String fundStatus, LocalDateTime fundTransferAt, String fundNote, List<QuarterRow> rows) {}
    private record Price(long id, LocalDate effectiveFrom, BigDecimal unitPrice) {}
    private record Item(long id, String name, String standardUnit, int quantityScale, boolean active) {
        ItemView at(int version, List<Price> prices, LocalDate day) {
            BigDecimal current = null;
            for (Price price : prices) {
                if (!price.effectiveFrom().isAfter(day)) current = price.unitPrice();
            }
            return new ItemView(id, name, standardUnit, quantityScale, active, version, current, prices);
        }
    }
    private record ItemView(long id, String name, String standardUnit, int quantityScale, boolean active,
                            int version, BigDecimal currentPrice, List<Price> prices) {}
    private record Clinic(long id, String name, String postalAddress, String managerName, String phone, String email,
                          BigDecimal officeArea, boolean active, int version) {
        Clinic withPhone(String oldPhone) {
            return new Clinic(id, name, postalAddress, managerName, oldPhone, email, officeArea, active, version);
        }
    }

    @Override
    public void migrate(Context context) {
        var jdbc = JdbcClient.create(new SingleConnectionDataSource(context.getConnection(), true));
        var clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        new Backfill(jdbc, clock).run();
    }

    private static class Backfill {
        private final JdbcClient jdbc;
        private final LocalDateTime now;
        private final VisitQueries visits;
        private final ObjectMapper json = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        private final Map<String,Actor> actors = new LinkedHashMap<>();

        Backfill(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.now = LocalDateTime.now(clock).withNano(0);
            this.visits = new VisitQueries(jdbc, clock);
        }

        void run() {
            jdbc.sql("""
                SELECT id,username,dentist_id FROM app_user WHERE username IN ('front1','hq1','dentist1','dentist2')
                """).query(Actor.class).list().forEach(actor -> actors.put(actor.username(), actor));
            var candidates = appointments();
            var historical = new LinkedHashMap<LocalDateTime,Appointment>();
            HISTORY.lines().forEach(line -> {
                var parts = line.split(" ");
                var at = LocalDateTime.parse(parts[0]);
                candidates.stream().filter(a -> a.scheduledAt().equals(at) && a.patient().accountNo().equals(parts[1]))
                    .findFirst().ifPresent(a -> historical.put(at, a));
            });

            var selected = new LinkedHashMap<Long,Appointment>();
            historical.values().forEach(a -> selected.put(a.id(), a));
            var days = new LinkedHashMap<LocalDate,List<Appointment>>();
            for (Appointment a : candidates) {
                days.computeIfAbsent(a.scheduledAt().toLocalDate(), ignored -> new ArrayList<>()).add(a);
            }
            for (var day : days.values()) {
                var daily = dailySeed(day, historical);
                daily.forEach(a -> selected.put(a.id(), a));
            }
            selected.values().forEach(this::recordAppointment);
            recordQuarter();
            recordPrice("局部麻醉", LocalDate.of(2026, 7, 1));
            recordPrice("洁牙", LocalDate.of(2026, 10, 1));
            var original = historical.get(LocalDateTime.parse("2026-07-03T09:00"));
            if (original != null) recordClinic(original.clinic().id());
        }

        private List<Appointment> appointments() {
            return jdbc.sql("""
                SELECT a.id,a.scheduled_at,a.cancelled_at,a.cancelled_by,p.id patient_id,p.name patient_name,
                       p.account_no,p.allergy_verified_at,d.id dentist_id,d.employee_no,d.name dentist_name,
                       c.id clinic_id,c.name clinic_name
                FROM appointment a JOIN patient p ON p.id=a.patient_id
                JOIN dentist d ON d.id=a.dentist_id JOIN clinic c ON c.id=a.clinic_id
                WHERE p.account_no BETWEEN 'P000001' AND 'P000008' ORDER BY a.id
                """).query((r,n) -> new Appointment(r.getLong("id"), r.getObject("scheduled_at", LocalDateTime.class),
                    new AppointmentView.Patient(r.getLong("patient_id"), r.getString("account_no"), r.getString("patient_name")),
                    new AppointmentView.Dentist(r.getLong("dentist_id"), r.getString("employee_no"), r.getString("dentist_name")),
                    new AppointmentView.Named(r.getLong("clinic_id"), r.getString("clinic_name")),
                    r.getObject("cancelled_at", LocalDateTime.class), r.getString("cancelled_by"),
                    r.getObject("allergy_verified_at", LocalDateTime.class))).list();
        }

        private List<Appointment> dailySeed(List<Appointment> day, Map<LocalDateTime,Appointment> historical) {
            // Require the six slots together so an isolated live booking does not get attributed to the demo users.
            var matched = new ArrayList<Appointment>();
            for (DailySlot slot : DAILY) {
                var source = historical.get(slot.reference());
                if (source == null) return List.of();
                var match = day.stream().filter(a -> a.scheduledAt().toLocalTime().equals(slot.time())
                    && a.patient().accountNo().equals(slot.account())
                    && a.dentist().id() == source.dentist().id() && a.clinic().id() == source.clinic().id()).findFirst();
                if (match.isEmpty()) return List.of();
                matched.add(match.get());
            }
            return matched;
        }

        private void recordAppointment(Appointment a) {
            var items = jdbc.sql("""
                SELECT i.id,i.name FROM appointment_item ai JOIN treatment_item i ON i.id=ai.item_id
                WHERE ai.appointment_id=? ORDER BY i.id
                """).param(a.id()).query(AppointmentView.Named.class).list();
            Actor front = actors.get("front1");
            var createdAt = a.scheduledAt().toLocalDate().minusDays(3).atTime(10, 0);
            if (createdAt.isAfter(now)) createdAt = now.minusSeconds(1);
            write(front, createdAt, "CREATE_APPOINTMENT", "APPOINTMENT", a.id(),
                new AppointmentView(a.id(), "VALID", a.scheduledAt(), a.patient(), a.dentist(), a.clinic(), items,
                    false, null, null, null, 0));
            if (a.cancelledAt() != null) {
                write(front, a.cancelledAt(), "CANCEL_APPOINTMENT", "APPOINTMENT", a.id(),
                    new AppointmentView(a.id(), "CANCELLED", a.scheduledAt(), a.patient(), a.dentist(), a.clinic(), items,
                        false, a.cancelledAt(), a.cancelledBy(), null, 1));
            }
            jdbc.sql("""
                SELECT v.id,v.arrived_at,v.registered_at,v.confirmed_at,v.problem,v.special_needs,v.related_history,
                       v.no_treatment_reason,d.id dentist_id,d.name dentist_name,c.id clinic_id,c.name clinic_name
                FROM visit v JOIN dentist d ON d.id=v.dentist_id JOIN clinic c ON c.id=v.clinic_id
                WHERE v.appointment_id=?
                """).param(a.id()).query((r,n) -> new Visit(r.getLong("id"),
                    r.getObject("arrived_at", LocalDateTime.class), r.getObject("registered_at", LocalDateTime.class),
                    r.getObject("confirmed_at", LocalDateTime.class),
                    new VisitDtos.Named(r.getLong("dentist_id"), r.getString("dentist_name")),
                    new VisitDtos.Named(r.getLong("clinic_id"), r.getString("clinic_name")),
                    r.getString("problem"), r.getString("special_needs"), r.getString("related_history"),
                    r.getString("no_treatment_reason"))).optional().ifPresent(v -> recordVisit(a, v, items));
        }

        private void recordVisit(Appointment a, Visit v, List<AppointmentView.Named> items) {
            if (v.arrivedAt().isAfter(now) || v.registeredAt().isAfter(now)) return;
            Actor front = actors.get("front1");
            write(front, v.registeredAt(), "REGISTER_ARRIVAL", "APPOINTMENT", a.id(),
                Map.of("visitId", v.id(), "arrivedAt", v.arrivedAt(), "registeredAt", v.registeredAt(),
                    "dentistId", v.dentist().id(), "clinicId", v.clinic().id()));
            var lines = visits.savedLines(List.of(v.id())).getOrDefault(v.id(), List.of());
            recordConfirmation(a, v, items, lines);
            jdbc.sql("""
                SELECT id,settlement_no,visit_id,issued_at,issued_by,patient_account_no,patient_name,clinic_name,dentist_name,total
                FROM settlement WHERE visit_id=?
                """).param(v.id()).query(Settlement.class).optional().ifPresent(s -> write(front, s.issuedAt(),
                    "ISSUE_SETTLEMENT", "SETTLEMENT", s.id(),
                    new Slip(s.settlementNo(), s.visitId(), s.issuedAt(), s.issuedBy(),
                        new PatientName(s.patientAccountNo(), s.patientName()), new Name(s.clinicName()),
                        new Name(s.dentistName()), lines, s.total())));
        }

        private void recordConfirmation(Appointment a, Visit v, List<AppointmentView.Named> items, List<VisitDtos.PriceLine> lines) {
            if (v.confirmedAt() == null || v.confirmedAt().isAfter(now)) return;
            var doctor = actors.values().stream()
                .filter(actor -> actor.dentistId() != null && actor.dentistId() == v.dentist().id()).findFirst().orElse(null);
            if (doctor == null) return;
            var allergies = jdbc.sql("""
                SELECT a.id,a.name FROM allergy a JOIN patient_allergy pa ON pa.allergy_id=a.id
                WHERE pa.patient_id=? ORDER BY a.id
                """).param(a.patient().id()).query(VisitDtos.Named.class).list();
            var intended = items.stream().map(i -> new VisitDtos.Named(i.id(), i.name())).toList();
            var priorVerification = a.allergyVerifiedAt();
            if (priorVerification != null && priorVerification.isAfter(v.confirmedAt())) priorVerification = null;
            boolean noTreatment = v.noTreatmentReason() != null;
            var before = new VisitDtos.Detail(v.id(), "UNCONFIRMED", v.arrivedAt(), null,
                patient(a, allergies, priorVerification), v.clinic(), v.dentist(), intended,
                null, null, null, null, List.of(), null, null, 0);
            BigDecimal total = noTreatment ? null : lines.stream().map(VisitDtos.PriceLine::subtotal)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            var after = new VisitDtos.Detail(v.id(), noTreatment ? "NO_TREATMENT" : "CONFIRMED", v.arrivedAt(), v.confirmedAt(),
                patient(a, allergies, noTreatment ? priorVerification : v.confirmedAt()), v.clinic(), v.dentist(), intended,
                v.problem(), v.specialNeeds(), v.relatedHistory(), v.noTreatmentReason(), lines, total, null, 1);
            write(doctor, v.confirmedAt(), noTreatment ? "END_WITHOUT_TREATMENT" : "CONFIRM_VISIT", "VISIT", v.id(),
                Map.of("before", before, "after", after));
        }

        private VisitDtos.PatientInfo patient(Appointment a, List<VisitDtos.Named> allergies, LocalDateTime verifiedAt) {
            return new VisitDtos.PatientInfo(a.patient().id(), a.patient().accountNo(), a.patient().name(), allergies, verifiedAt);
        }

        private void recordQuarter() {
            var q = jdbc.sql("""
                SELECT id,year,quarter,confirmed_at,first_confirmed_at,company_net_profit,incentive_fund,fund_transfer_at,fund_note
                FROM quarter_close WHERE year=2026 AND quarter=2
                """).query(Quarter.class).optional().orElse(null);
            if (q == null) return;
            var rows = jdbc.sql("""
                SELECT p.dentist_id,d.employee_no,d.name,p.revenue,p.supply_cost,p.operating_cost,p.basis,
                       p.revenue-p.supply_cost-p.operating_cost net_profit
                FROM dentist_quarter_performance p JOIN dentist d ON d.id=p.dentist_id
                WHERE p.quarter_close_id=? ORDER BY p.dentist_id
                """).param(q.id()).query(QuarterRow.class).list();
            var periodEnd = LocalDate.of(2026, 6, 30);
            var firstDay = q.firstConfirmedAt().toLocalDate();
            long workdays = firstDay.isAfter(periodEnd) ? periodEnd.plusDays(1).datesUntil(firstDay.plusDays(1))
                .filter(day -> day.getDayOfWeek().getValue() <= 5).count() : 0;
            write(actors.get("hq1"), LocalDateTime.of(2026, 7, 8, 16, 0), "CONFIRM_QUARTER", "QUARTER_CLOSE", q.id(),
                new ClosedQuarter(q.id(), q.year(), q.quarter(), q.confirmedAt(), q.firstConfirmedAt(), workdays,
                    q.companyNetProfit(), q.incentiveFund(), q.companyNetProfit().signum() > 0 ? "ACCRUED" : "TRANSFERRED",
                    q.fundTransferAt(), q.fundNote(), rows));
        }

        private void recordPrice(String name, LocalDate effectiveFrom) {
            var item = jdbc.sql("SELECT id,name,standard_unit,quantity_scale,active FROM treatment_item WHERE name=?")
                .param(name).query(Item.class).optional().orElse(null);
            if (item == null) return;
            var after = jdbc.sql("""
                SELECT id,effective_from,unit_price FROM standard_price
                WHERE item_id=? AND effective_from<=? ORDER BY effective_from
                """).params(item.id(), effectiveFrom).query(Price.class).list();
            if (after.size() < 2 || !after.get(after.size() - 1).effectiveFrom().equals(effectiveFrom)) return;
            var before = after.subList(0, after.size() - 1);
            var day = effectiveFrom.minusDays(3);
            write(actors.get("hq1"), day.atTime(10, 0), "ADD_STANDARD_PRICE", "TREATMENT_ITEM", item.id(),
                Map.of("before", item.at(0, before, day), "after", item.at(1, after, day)));
        }

        private void recordClinic(long clinicId) {
            var after = jdbc.sql("""
                SELECT id,name,postal_address,manager_name,phone,email,office_area,active,version FROM clinic WHERE id=?
                """).param(clinicId).query(Clinic.class).single();
            var before = after.withPhone(after.phone().equals("020-38880000") ? "020-38880001" : "020-38880000");
            write(actors.get("hq1"), now.minusDays(2), "UPDATE", "CLINIC", clinicId, Map.of("before", before, "after", after));
        }

        private void write(Actor actor, LocalDateTime at, String action, String type, long id, Object detail) {
            if (actor == null || at == null || at.isAfter(now)) return;
            jdbc.sql("""
                INSERT INTO operation_log (user_id,username,occurred_at,action,target_type,target_id,detail)
                SELECT ?,?,?,?,?,?,?
                WHERE NOT EXISTS (
                    SELECT 1 FROM operation_log WHERE action=? AND UPPER(target_type)=? AND target_id=?
                )
                """).params(actor.id(), actor.username(), at, action, type, id, json.writeValueAsString(detail),
                    action, type, id).update();
        }
    }
}
