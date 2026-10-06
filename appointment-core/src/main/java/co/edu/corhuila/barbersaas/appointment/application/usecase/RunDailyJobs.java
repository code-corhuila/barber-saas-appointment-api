package co.edu.corhuila.barbersaas.appointment.application.usecase;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopZones;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Clock;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DailyJobsStore;
import co.edu.corhuila.barbersaas.appointment.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The reminder and no-show jobs (DEC-APPT-07, FR-012). The rules live here; the worker only calls
 * at the right time. "Tomorrow" and "before today" are the barbershop's local dates, so the
 * candidates are read with a margin of a day around UTC and each one is judged in its zone.
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

    @Override
    public JobResult writeDueReminders(Caller caller, int limit) {
        caller.requireService(RelayOutbox.WORKER);
        Instant now = clock.now();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<Appointment> candidates = jobs.confirmedBetween(utcToday, utcToday.plusDays(2), true);
        Map<UUID, LocalDate> todayOf = new HashMap<>();
        Predicate<Appointment> tomorrow = a -> a.slot().date().equals(localToday(a, now, todayOf).plusDays(1));
        return run(candidates, tomorrow, limit,
                a -> jobs.markReminderSent(a.id(), Events.of(Events.REMINDER_DUE, a, ids, now), now));
    }

    @Override
    public JobResult markNoShows(Caller caller, int limit) {
        caller.requireService(RelayOutbox.WORKER);
        Instant now = clock.now();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<Appointment> candidates = jobs.confirmedBetween(null, utcToday, false);
        Map<UUID, LocalDate> todayOf = new HashMap<>();
        Predicate<Appointment> past = a -> a.slot().date().isBefore(localToday(a, now, todayOf));
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

    /** The barbershop's local date, asked once per barbershop and call. */
    private LocalDate localToday(Appointment a, Instant now, Map<UUID, LocalDate> todayOf) {
        return todayOf.computeIfAbsent(a.barbershopId(), shop -> {
            ZoneId zone = zones.zoneOf(shop);
            return LocalDate.ofInstant(now, zone);
        });
    }
}
