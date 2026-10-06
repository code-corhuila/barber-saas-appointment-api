package co.edu.corhuila.barbersaas.appointment.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases.JobResult;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DailyJobsStore;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Money;
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
        final Set<UUID> reminded = new HashSet<>();

        @Override
        public List<Appointment> confirmedBetween(LocalDate from, LocalDate to, boolean withoutReminder) {
            return repository.rows.values().stream().filter(a -> a.status() == AppointmentStatus.CONFIRMED)
                    .filter(a -> !a.slot().date().isAfter(to) && (from == null || !a.slot().date().isBefore(from)))
                    .filter(a -> !withoutReminder || !reminded.contains(a.id()))
                    .sorted(Comparator.comparing((Appointment a) -> a.slot().startsAt())).toList();
        }

        @Override
        public boolean markReminderSent(UUID appointmentId, OutboxEvent event, Instant now) {
            if (!reminded.add(appointmentId)) {
                return false;
            }
            repository.outbox.add(event);
            return true;
        }
    }

    Appointment stored(UUID shop, LocalDate date, AppointmentStatus status) {
        Appointment a = Appointment.restore(UUID.randomUUID(), shop, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Slot.of(date, LocalTime.of(10, 0), 30), Money.ofCents(2_500_000), null,
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

        assertEquals(new JobResult(2, false), jobs.writeDueReminders(worker, 20));
        assertEquals(new JobResult(0, false), jobs.writeDueReminders(worker, 20));

        assertEquals(Set.of(bogotaTomorrow.id().toString(), aucklandTomorrow.id().toString()),
                Set.copyOf(events(Events.REMINDER_DUE)));
        assertEquals(2, zoneCalls.get(AUCKLAND), "two calls: the zone is asked once per barbershop in each");
    }

    @Test
    void theLimitIsRespectedAndRemainingAsksForAnotherCall() {
        for (int i = 0; i < 3; i++) {
            stored(BOGOTA, BOGOTA_TODAY.plusDays(1), AppointmentStatus.CONFIRMED);
        }

        assertEquals(new JobResult(2, true), jobs.writeDueReminders(worker, 2));
        assertEquals(new JobResult(1, false), jobs.writeDueReminders(worker, 2));
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
            assertThrows(Forbidden.class, () -> jobs.writeDueReminders(caller, 20));
            assertThrows(Forbidden.class, () -> jobs.markNoShows(caller, 20));
        }
    }
}
