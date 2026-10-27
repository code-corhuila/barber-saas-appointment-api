package co.edu.corhuila.barbersaas.appointment.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases.JobResult;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopZones;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DailyJobsStore;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Money;
import co.edu.corhuila.barbersaas.appointment.domain.model.ReminderWindow;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DEC-APPT-07 and FR-012: the reminder and no-show jobs, judged in each barbershop's local date. */
class RunDailyJobsTest {

    /** 2026-10-06 22:00 in Bogotá (UTC−5) is already 2026-10-07 in Auckland (UTC+13). */
    static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    static final LocalDate BOGOTA_TODAY = LocalDate.of(2026, 10, 6);
    static final LocalDate AUCKLAND_TODAY = LocalDate.of(2026, 10, 7);
    static final UUID BOGOTA = UUID.randomUUID();
    static final UUID AUCKLAND = UUID.randomUUID();

    final Caller worker = new Caller("barber-saas-worker", Role.SERVICE, null, "token");
    final Fakes.Repository repository = new Fakes.Repository();
    final Jobs store = new Jobs();
    final Map<UUID, Integer> zoneCalls = new HashMap<>();
    final RunDailyJobs jobs = new RunDailyJobs(store, repository, shop -> {
        zoneCalls.merge(shop, 1, Integer::sum);
        return ZoneId.of(shop.equals(AUCKLAND) ? "Pacific/Auckland" : "America/Bogota");
    }, () -> NOW, new Fakes.Sequence());

    /** DailyJobsStore over the same rows and outbox as the fake repository. */
    final class Jobs implements DailyJobsStore {
        final Map<ReminderWindow, Set<UUID>> reminded = Map.of(ReminderWindow.DAY_BEFORE, new HashSet<>(),
                ReminderWindow.HOUR_BEFORE, new HashSet<>());

        @Override
        public List<Appointment> confirmedBetween(LocalDate from, LocalDate to, ReminderWindow withoutReminder) {
            return repository.rows.values().stream().filter(a -> a.status() == AppointmentStatus.CONFIRMED)
                    .filter(a -> !a.slot().date().isAfter(to) && (from == null || !a.slot().date().isBefore(from)))
                    .filter(a -> withoutReminder == null || !reminded.get(withoutReminder).contains(a.id()))
                    .sorted(Comparator.comparing((Appointment a) -> a.slot().startsAt())).toList();
        }

        @Override
        public boolean markReminderSent(UUID appointmentId, ReminderWindow window, OutboxEvent event, Instant now) {
            if (!reminded.get(window).add(appointmentId)) {
                return false;
            }
            repository.outbox.add(event);
            return true;
        }
    }

    Appointment stored(UUID shop, LocalDate date, AppointmentStatus status) {
        return stored(shop, date, LocalTime.of(10, 0), status);
    }

    Appointment stored(UUID shop, LocalDate date, LocalTime start, AppointmentStatus status) {
        Appointment a = Appointment.restore(UUID.randomUUID(), shop, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Slot.of(date, start, 30), Money.ofCents(2_500_000), null,
                UUID.randomUUID(), NOW, status, null, NOW);
        repository.rows.put(a.id(), a);
        return a;
    }

    List<String> events(String type) {
        return repository.outbox.stream().filter(e -> e.type().equals(type))
                .map(e -> e.aggregateId().toString()).toList();
    }

    @Test
    void aReminderIsWrittenOnceForTheConfirmedAppointmentsOfEachBarbershopsTomorrow() {
        Appointment bogotaTomorrow = stored(BOGOTA, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);
        Appointment aucklandTomorrow = stored(AUCKLAND, AUCKLAND_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);
        stored(AUCKLAND, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);   // today in Auckland
        stored(BOGOTA, BOGOTA_TODAY.plusDays(1), AppointmentStatus.PENDING);
        stored(BOGOTA, BOGOTA_TODAY.plusDays(2), AppointmentStatus.CONFIRMED);

        assertEquals(new JobResult(2, false), jobs.writeDueReminders(worker, ReminderWindow.DAY_BEFORE, 20));
        assertEquals(new JobResult(0, false), jobs.writeDueReminders(worker, ReminderWindow.DAY_BEFORE, 20));

        assertEquals(Set.of(bogotaTomorrow.id().toString(), aucklandTomorrow.id().toString()),
                Set.copyOf(events(Events.REMINDER_DUE)));
        assertEquals(2, zoneCalls.get(AUCKLAND), "two calls: the zone is asked once per barbershop in each");
    }

    @Test
    void theDayBeforeReminderNowCarriesItsWindowInVersionTwo() {
        stored(BOGOTA, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);

        jobs.writeDueReminders(worker, ReminderWindow.DAY_BEFORE, 20);

        OutboxEvent e = repository.outbox.get(0);
        assertEquals("DAY_BEFORE", e.payload().get("window"));
        assertEquals(2, Events.version(Events.REMINDER_DUE));
    }

    @Test
    void theReminderNamesTheBarbershopAskedOncePerCall() {
        Map<UUID, Integer> nameCalls = new HashMap<>();
        RunDailyJobs named = new RunDailyJobs(store, repository, new BarbershopZones() {
            public ZoneId zoneOf(UUID shop) {
                return ZoneId.of("America/Bogota");
            }

            public String nameOf(UUID shop) {
                nameCalls.merge(shop, 1, Integer::sum);
                return shop.equals(BOGOTA) ? "El Clasico" : null;
            }
        }, () -> NOW, new Fakes.Sequence());
        stored(BOGOTA, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);
        stored(BOGOTA, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);
        stored(AUCKLAND, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);

        named.writeDueReminders(worker, ReminderWindow.DAY_BEFORE, 20);

        Map<Object, Long> byName = new HashMap<>();
        repository.outbox.forEach(e -> byName.merge(String.valueOf(e.payload().get("barbershopName")), 1L, Long::sum));
        assertEquals(Map.of("El Clasico", 2L, "null", 1L), byName);
        assertTrue(repository.outbox.stream().allMatch(e -> e.payload().containsKey("barbershopName")));
        assertEquals(1, nameCalls.get(BOGOTA));
    }

    /** 22:00 in Bogotá now: a start at 23:00 is 60 minutes away, at 22:55 55, at 23:05 65. */
    @Test
    void theHourBeforeReminderGoesToConfirmedAppointmentsStartingIn55To65MinutesOnce() {
        Appointment inAnHour = stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(23, 0), AppointmentStatus.CONFIRMED);
        Appointment in55 = stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(22, 55), AppointmentStatus.CONFIRMED);
        Appointment in65 = stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(23, 5), AppointmentStatus.CONFIRMED);
        stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(22, 50), AppointmentStatus.CONFIRMED);      // 50 minutes
        stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(23, 10), AppointmentStatus.CONFIRMED);      // 70 minutes
        stored(BOGOTA, BOGOTA_TODAY.plusDays(1), LocalTime.of(0, 0), AppointmentStatus.CONFIRMED);   // 2 hours
        stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(23, 0), AppointmentStatus.CANCELLED);
        stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(23, 0), AppointmentStatus.PENDING);

        assertEquals(new JobResult(3, false), jobs.writeDueReminders(worker, ReminderWindow.HOUR_BEFORE, 20));
        assertEquals(new JobResult(0, false), jobs.writeDueReminders(worker, ReminderWindow.HOUR_BEFORE, 20));

        assertEquals(Set.of(inAnHour.id().toString(), in55.id().toString(), in65.id().toString()),
                Set.copyOf(events(Events.REMINDER_DUE)));
        assertTrue(repository.outbox.stream().allMatch(e -> "HOUR_BEFORE".equals(e.payload().get("window"))));
    }

    /** 03:00 UTC is 16:00 of the next day in Auckland: a start at 17:00 there is 60 minutes away. */
    @Test
    void theHourIsTheBarbershopsLocalTimeAndTheTwoWindowsAreIndependent() {
        Appointment auckland = stored(AUCKLAND, AUCKLAND_TODAY, LocalTime.of(17, 0), AppointmentStatus.CONFIRMED);
        stored(BOGOTA, BOGOTA_TODAY.plusDays(1), LocalTime.of(17, 0), AppointmentStatus.CONFIRMED);   // 19 hours
        Appointment tomorrowInBogota = stored(BOGOTA, BOGOTA_TODAY, LocalTime.of(23, 0), AppointmentStatus.CONFIRMED);
        store.reminded.get(ReminderWindow.DAY_BEFORE).add(tomorrowInBogota.id());   // the day-before one was sent

        assertEquals(new JobResult(2, false), jobs.writeDueReminders(worker, ReminderWindow.HOUR_BEFORE, 20));

        assertEquals(Set.of(auckland.id().toString(), tomorrowInBogota.id().toString()),
                Set.copyOf(events(Events.REMINDER_DUE)));
    }

    @Test
    void theLimitIsRespectedAndRemainingAsksForAnotherCall() {
        for (int i = 0; i < 3; i++) {
            stored(BOGOTA, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);
        }

        assertEquals(new JobResult(2, true), jobs.writeDueReminders(worker, ReminderWindow.DAY_BEFORE, 2));
        assertEquals(new JobResult(1, false), jobs.writeDueReminders(worker, ReminderWindow.DAY_BEFORE, 2));
        assertEquals(3, events(Events.REMINDER_DUE).size());
    }

    @Test
    void confirmedAppointmentsBeforeEachBarbershopsTodayBecomeNoShowsOnce() {
        Appointment bogotaYesterday = stored(BOGOTA, BOGOTA_TODAY.minusDays(1), AppointmentStatus.CONFIRMED);
        Appointment aucklandYesterday = stored(AUCKLAND, BOGOTA_TODAY, AppointmentStatus.CONFIRMED);
        Appointment bogotaToday = stored(BOGOTA, BOGOTA_TODAY, AppointmentStatus.CONFIRMED);
        Appointment completed = stored(BOGOTA, BOGOTA_TODAY.minusDays(3), AppointmentStatus.COMPLETED);

        assertEquals(new JobResult(2, false), jobs.markNoShows(worker, 20));
        assertEquals(new JobResult(0, false), jobs.markNoShows(worker, 20));

        assertEquals(AppointmentStatus.NO_SHOW, repository.rows.get(bogotaYesterday.id()).status());
        assertEquals(AppointmentStatus.NO_SHOW, repository.rows.get(aucklandYesterday.id()).status());
        assertEquals(AppointmentStatus.CONFIRMED, repository.rows.get(bogotaToday.id()).status());
        assertEquals(AppointmentStatus.COMPLETED, repository.rows.get(completed.id()).status());
        assertEquals(Set.of(bogotaYesterday.id().toString(), aucklandYesterday.id().toString()),
                Set.copyOf(events(Events.NO_SHOW)));
    }

    @Test
    void noShowsRespectTheLimit() {
        stored(BOGOTA, BOGOTA_TODAY.minusDays(1), AppointmentStatus.CONFIRMED);
        stored(BOGOTA, BOGOTA_TODAY.minusDays(2), AppointmentStatus.CONFIRMED);

        assertEquals(new JobResult(1, true), jobs.markNoShows(worker, 1));
        assertEquals(new JobResult(1, false), jobs.markNoShows(worker, 1));
    }

    @Test
    void onlyTheWorkerRunsTheJobs() {
        Caller admin = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, BOGOTA, "t");
        Caller other = new Caller("barber-saas-schedule-api", Role.SERVICE, null, "t");

        for (Caller caller : List.of(admin, other)) {
            assertThrows(Forbidden.class, () -> jobs.writeDueReminders(caller, ReminderWindow.DAY_BEFORE, 20));
            assertThrows(Forbidden.class, () -> jobs.writeDueReminders(caller, ReminderWindow.HOUR_BEFORE, 20));
            assertThrows(Forbidden.class, () -> jobs.markNoShows(caller, 20));
        }
    }
}
