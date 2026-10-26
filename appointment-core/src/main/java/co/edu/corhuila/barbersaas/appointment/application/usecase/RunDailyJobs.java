package co.edu.corhuila.barbersaas.appointment.application.usecase;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopZones;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Clock;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DailyJobsStore;
import co.edu.corhuila.barbersaas.appointment.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.ReminderWindow;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The reminder and no-show jobs (DEC-APPT-07, DEC-APPT-12, FR-012). The rules live here; the worker
 * only calls at the right time. "Tomorrow", "in 55 to 65 minutes" and "before today" are judged in the
 * barbershop's local time, so the candidates are read with a margin of a day around UTC.
 */
public class RunDailyJobs implements DailyJobUseCases {

    private final DailyJobsStore jobs;
    private final AppointmentRepository appointments;
    private final BarbershopZones zones;
    private final Clock clock;
    private final IdGenerator ids;

    public RunDailyJobs(DailyJobsStore jobs, AppointmentRepository appointments, BarbershopZones zones, Clock clock,
                        IdGenerator ids) {
        this.jobs = jobs;
        this.appointments = appointments;
        this.zones = zones;
        this.clock = clock;
        this.ids = ids;
    }

    /** The one-hour reminder goes to the appointments that start between these, from now (DEC-APPT-12). */
    static final Duration HOUR_FROM = Duration.ofMinutes(55);
    static final Duration HOUR_TO = Duration.ofMinutes(65);

    @Override
    public JobResult writeDueReminders(Caller caller, ReminderWindow window, int limit) {
        caller.requireService(RelayOutbox.WORKER);
        Instant now = clock.now();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Map<UUID, ZoneId> zoneOf = new HashMap<>();
        List<Appointment> candidates;
        Predicate<Appointment> due;
        if (window == ReminderWindow.HOUR_BEFORE) {
            candidates = jobs.confirmedBetween(utcToday.minusDays(1), utcToday.plusDays(1), window);
            due = a -> {
                Duration until = Duration.between(now, a.slot().date().atTime(a.slot().start())
                        .atZone(zone(a, zoneOf)).toInstant());
                return until.compareTo(HOUR_FROM) >= 0 && until.compareTo(HOUR_TO) <= 0;
            };
        } else {
            candidates = jobs.confirmedBetween(utcToday, utcToday.plusDays(2), window);
            due = a -> a.slot().date().equals(LocalDate.ofInstant(now, zone(a, zoneOf)).plusDays(1));
        }
        return run(candidates, due, limit,
                a -> jobs.markReminderSent(a.id(), window, Events.reminder(a, window, ids, now), now));
    }

    @Override
    public JobResult markNoShows(Caller caller, int limit) {
        caller.requireService(RelayOutbox.WORKER);
        Instant now = clock.now();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<Appointment> candidates = jobs.confirmedBetween(null, utcToday, null);
        Map<UUID, ZoneId> zoneOf = new HashMap<>();
        Predicate<Appointment> past = a -> a.slot().date().isBefore(LocalDate.ofInstant(now, zone(a, zoneOf)));
        return run(candidates, past, limit, a -> {
            a.markNoShow(now);                               // the same transition as the manual no-show
            appointments.update(a, List.of(Events.of(Events.NO_SHOW, a, ids, now)));
            return true;
        });
    }

    /** Handles the due ones up to {@code limit}; {@code remaining} says another due one is left. */
    private static JobResult run(List<Appointment> candidates, Predicate<Appointment> due, int limit,
                                 Predicate<Appointment> handle) {
        int processed = 0;
        for (Appointment a : candidates) {
            if (!due.test(a)) {
                continue;
            }
            if (processed == limit) {
                return new JobResult(processed, true);
            }
            if (handle.test(a)) {
                processed++;
            }
        }
        return new JobResult(processed, false);
    }

    /** The barbershop's time zone, asked once per barbershop and call. */
    private ZoneId zone(Appointment a, Map<UUID, ZoneId> zoneOf) {
        return zoneOf.computeIfAbsent(a.barbershopId(), zones::zoneOf);
    }
}
