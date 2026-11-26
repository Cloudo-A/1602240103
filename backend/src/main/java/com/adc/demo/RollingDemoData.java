
package com.adc.demo;

import com.adc.appointment.AppointmentView;
import com.adc.demo.RollingDemoCatalog.*;
import com.adc.demo.RollingDemoWriter.Outcome;
import com.adc.demo.RollingDemoWriter.Slot;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("demo")
public class RollingDemoData {
    static final LocalDate FIRST_DAY = LocalDate.of(2026, 7, 1);
    private static final Logger LOG = LoggerFactory.getLogger(RollingDemoData.class);

    private final Clock clock;
    private final JdbcClient jdbc;
    private final DataSource locks;
    private final TransactionTemplate transaction;
    private final DailyDemoAppointments daily;
    private final DemoProgress progress;
    private final RollingDemoCatalog catalog;
    private final RollingDemoWriter writer;
    private final RollingDemoRequests requests;

    RollingDemoData(Clock clock, JdbcClient jdbc, DataSourceProperties dataSource, PlatformTransactionManager manager,
                    DailyDemoAppointments daily, DemoProgress progress, RollingDemoCatalog catalog,
                    RollingDemoWriter writer, RollingDemoRequests requests) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.locks = dataSource.initializeDataSourceBuilder().type(SimpleDriverDataSource.class).build();
        this.transaction = new TransactionTemplate(manager);
        this.daily = daily;
        this.progress = progress;
        this.catalog = catalog;
        this.writer = writer;
        this.requests = requests;
    }

    void seedToday() {
        exclusive(now -> seedToday(now, catalog.load()));
    }

    void run() {
        exclusive(this::fill);
    }

    /** Runs the same job against the supplied Beijing time. */
    public void runAt(LocalDateTime now) {
        exclusive(ignored -> fill(now.withNano(0)));
    }

    void issueDueBills() {
        exclusive(now -> issueDueBills(now, catalog.load()));
    }

    private void exclusive(Consumer<LocalDateTime> job) {
        // A dedicated session holds the same schema-scoped lock for every entry point,
        // without keeping a transaction or using up a request's pooled connection.
        try (var connection = locks.getConnection();
             var statement = connection.prepareStatement("SELECT GET_LOCK(SHA2(CONCAT(DATABASE(), ':demo-rolling'),256),0)")) {
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) return;
            }
            job.accept(LocalDateTime.now(clock).withNano(0));
        } catch (SQLException failure) {
            throw new IllegalStateException("无法取得演示补种数据库锁", failure);
        }
    }

    private void fill(LocalDateTime now) {
        long began = System.nanoTime();
        var roster = catalog.load();
        seedToday(now, roster);
        if (roster.clinics().isEmpty() || roster.dentists().isEmpty() || roster.patients().isEmpty()) {
            LOG.warn("演示资料缺少可用的门店、牙医或患者，暂缓补齐");
            return;
        }
        issueDueBills(now, roster);
        var today = now.toLocalDate();
        var completed = new HashSet<String>();
        progress.due("day:", today.atStartOfDay()).forEach(entry -> completed.add(entry.key()));
        int days = 0;
        int appointments = 0;
        for (var day = FIRST_DAY; day.isBefore(today); day = day.plusDays(1)) {
            if (completed.contains("day:" + day)) continue;
            var date = day;
            Integer added = transaction.execute(status -> fillDay(date, now, roster));
            appointments += added == null ? 0 : added;
            if (++days % 7 == 0) LOG.info("滚动演示已补到 {}，本次累计新增 {} 条预约", day, appointments);
        }
        if (!today.isBefore(FIRST_DAY)) {
            transaction.executeWithoutResult(status -> {
                requests.submit(today, roster);
                bookTodayRequests(now, roster);
            });
        }
        issueDueBills(now, roster);
        LOG.info("滚动演示补齐至 {}，本次处理 {} 天、新增 {} 条预约，用时 {} ms",
            today.minusDays(1), days, appointments, (System.nanoTime() - began) / 1_000_000);
    }

    private void seedToday(LocalDateTime now, Roster roster) {
        var day = now.toLocalDate();
        if (day.isBefore(FIRST_DAY)) return;
        transaction.executeWithoutResult(status -> {
            String key = "today:" + day;
            if (progress.get(key).isPresent()) return;
            var created = daily.seedAndList(day);
            for (long id : created) writer.recordToday(id, now, roster.front());
            if (!created.isEmpty()) progress.put(key, day.atStartOfDay(), created);
        });
    }

    private int fillDay(LocalDate day, LocalDateTime now, Roster roster) {
        // Lock the same visit-date range as quarter confirmation before reading the closed-quarter record.
        jdbc.sql("""
            SELECT id FROM visit FORCE INDEX (idx_visit_arrived_at) WHERE arrived_at>=? AND arrived_at<?
            ORDER BY arrived_at,id FOR UPDATE
            """).params(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).query(Long.class).list();
        boolean closed = jdbc.sql("SELECT id FROM quarter_close WHERE year=? AND quarter=? FOR SHARE")
            .params(day.getYear(), (day.getMonthValue()-1)/3+1).query(Long.class).optional().isPresent();
        List<Long> added = closed ? List.of() : writeDay(day, now, roster);
        progress.put("day:" + day, day.atStartOfDay(), added);
        return added.size();
    }

    private List<Long> writeDay(LocalDate day, LocalDateTime now, Roster roster) {
        var random = new Random(day.toEpochDay() ^ 0x53617239L);
        boolean sunday = day.getDayOfWeek() == DayOfWeek.SUNDAY;
        int target = sunday ? 2 + random.nextInt(3) : 6 + random.nextInt(4);
        int existing = jdbc.sql("SELECT COUNT(*) FROM appointment WHERE scheduled_at>=? AND scheduled_at<?")
            .params(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).query(Integer.class).single();
        boolean hold = !day.isBefore(now.toLocalDate().minusDays(2));
        var templates = progress.get("today:" + day).map(e -> DemoProgress.ids(e.data())).orElse(List.of());
        for (int i = 0; i < templates.size(); i++) {
            boolean held = writer.finishTodayTemplate(templates.get(i), now, hold && i == 1, random, roster,
                i < 2 ? Outcome.SETTLED : outcome(random, false));
            hold = hold && !held;
        }
        for (var entry : progress.due("scheduled:", day.plusDays(1).atStartOfDay())) {
            boolean held = writer.finishTodayTemplate(entry.data().asLong(), now, hold, random, roster, outcome(random, false));
            hold = hold && !held;
            progress.remove(entry.key());
        }

        requests.submit(day, roster);
        var due = requests.due(day, roster);
        var times = times(day, random);
        var added = new ArrayList<Long>();
        var served = new HashSet<Long>();
        for (var request : due) {
            if (existing + added.size() >= target) break;
            var dentist = nextDentist(roster, day, added.size(), served);
            var time = preferredTime(times, request.timeSlot());
            if (time == null) break;
            var slot = requestSlot(request, dentist, day.atTime(time), random, roster);
            if (slot.items().isEmpty()) continue;
            Long id = writer.book(slot, roster.front(), request.handledAt(), request.id());
            if (id == null) continue;
            added.add(id);
            requests.confirm(request, id, roster);
            var result = outcome(random, !sunday && !served.contains(dentist.profile().id()));
            boolean held = writer.finish(id, slot, result, hold, request.handledAt(), now, random, roster);
            hold = hold && !held;
            if (arrived(result)) served.add(dentist.profile().id());
        }
        int remaining = Math.max(0, target - existing - added.size());
        for (int i = 0; i < remaining && !times.isEmpty(); i++) {
            var dentist = nextDentist(roster, day, i + added.size(), served);
            int clinicIndex = roster.dentists().indexOf(dentist) % roster.clinics().size();
            if (sunday) clinicIndex = Math.floorMod((int) day.toEpochDay() + i, roster.clinics().size());
            var chosen = roster.patients().get(random.nextInt(roster.patients().size()));
            var slot = slot(chosen, dentist, roster.clinics().get(clinicIndex), day.atTime(times.remove(0)), List.of(), random, roster);
            if (slot.items().isEmpty()) continue;
            var creationDate = day.minusDays(3).isBefore(FIRST_DAY) ? FIRST_DAY : day.minusDays(3);
            var created = creationDate.equals(day) ? day.atTime(7, 0) : creationDate.atTime(10, 0);
            Long id = writer.book(slot, roster.front(), created, null);
            if (id == null) continue;
            added.add(id);
            var result = outcome(random, !sunday && !served.contains(dentist.profile().id()));
            boolean held = writer.finish(id, slot, result, hold, created, now, random, roster);
            hold = hold && !held;
            if (arrived(result)) served.add(dentist.profile().id());
        }
        return added;
    }

    private void bookTodayRequests(LocalDateTime now, Roster roster) {
        var day = now.toLocalDate();
        var random = new Random(day.toEpochDay() ^ 0x53617239L);
        int target = day.getDayOfWeek() == DayOfWeek.SUNDAY ? 2 + random.nextInt(3) : 6 + random.nextInt(4);
        int count = jdbc.sql("SELECT COUNT(*) FROM appointment WHERE scheduled_at>=? AND scheduled_at<?")
            .params(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).query(Integer.class).single();
        var times = times(day, random);
        times.removeIf(time -> !day.atTime(time).isAfter(now));
        for (var request : requests.due(day, roster)) {
            if (count >= target || times.isEmpty()) return;
            var dentist = nextDentist(roster, day, count, Set.of());
            var slot = requestSlot(request, dentist, day.atTime(preferredTime(times, request.timeSlot())), random, roster);
            if (slot.items().isEmpty()) continue;
            Long id = writer.book(slot, roster.front(), request.handledAt(), request.id());
            if (id == null) continue;
            requests.confirm(request, id, roster);
            progress.put("scheduled:" + id, day.atStartOfDay(), id);
            count++;
        }
    }

    private Slot requestSlot(RollingDemoRequests.Due request, Dentist dentist, LocalDateTime at, Random random, Roster roster) {
        var clinic = roster.clinics().stream().filter(c -> c.id() == request.clinic()).findFirst().orElse(roster.clinics().get(0));
        return slot(roster.patient(request.contact().patientId()), dentist, clinic, at, requests.preferredItems(request.id()), random, roster);
    }

    private static boolean arrived(Outcome outcome) {
        return outcome != Outcome.CANCELLED && outcome != Outcome.MISSED;
    }

    private static Dentist nextDentist(Roster roster, LocalDate day, int index, Set<Long> served) {
        int size = roster.dentists().size();
        int start = Math.floorMod((int) day.toEpochDay() + index, size);
        for (int i = 0; i < size; i++) {
            var dentist = roster.dentists().get((start + i) % size);
            if (!served.contains(dentist.profile().id())) return dentist;
        }
        return roster.dentists().get(start);
    }

    private static List<LocalTime> times(LocalDate day, Random random) {
        var times = new ArrayList<LocalTime>();
        for (int i = 0; i < 18; i++) times.add(day.atTime(9, 0).plusMinutes(i * 30L).toLocalTime());
        Collections.shuffle(times, random);
        return times;
    }

    private static LocalTime preferredTime(List<LocalTime> times, String slot) {
        for (int i = 0; i < times.size(); i++) {
            var time = times.get(i);
            int hour = time.getHour();
            if (("MORNING".equals(slot) && hour < 12) || ("AFTERNOON".equals(slot) && hour >= 14 && hour < 17)
                    || ("EVENING".equals(slot) && hour >= 17)) {
                return times.remove(i);
            }
        }
        return times.isEmpty() ? null : times.remove(0);
    }

    private static Slot slot(Patient patient, Dentist dentist, AppointmentView.Named clinic, LocalDateTime time,
                             List<Long> preferred, Random random, Roster roster) {
        var items = new ArrayList<>(roster.eligible(dentist.profile().id(), time));
        Collections.shuffle(items, random);
        items.sort(java.util.Comparator.comparing(item -> !preferred.contains(item.id())));
        int count = Math.min(items.size(), 1 + random.nextInt(2));
        var selected = items.subList(0, count).stream().sorted(java.util.Comparator.comparingLong(Item::id)).toList();
        return new Slot(patient, dentist, clinic, time, selected);
    }

    private static Outcome outcome(Random random, boolean coverDentist) {
        int draw = random.nextInt(100);
        if (coverDentist) {
            if (draw < 86) return Outcome.SETTLED;
            return draw < 94 ? Outcome.NEXT_MORNING : Outcome.NO_TREATMENT;
        }
        if (draw < 63) return Outcome.SETTLED;
        if (draw < 69) return Outcome.NEXT_MORNING;
        if (draw < 87) return Outcome.CANCELLED;
        return draw < 90 ? Outcome.NO_TREATMENT : Outcome.MISSED;
    }

    private void issueDueBills(LocalDateTime now, Roster roster) {
        for (var bill : progress.due("bill:", now)) {
            transaction.executeWithoutResult(status -> {
                long id = bill.data().get(0).asLong();
                int version = bill.data().get(1).asInt();
                writer.issue(id, version, bill.at(), now, roster.front());
                progress.remove(bill.key());
            });
        }
    }
}
