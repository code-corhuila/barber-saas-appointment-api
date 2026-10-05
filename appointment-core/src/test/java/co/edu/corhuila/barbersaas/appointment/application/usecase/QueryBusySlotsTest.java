package co.edu.corhuila.barbersaas.appointment.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.appointment.application.port.in.BusySlotUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.Money;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DEC-APPT-05, ADR-015: schedule-api reads a barber's taken time, and nothing else. */
class QueryBusySlotsTest {

    static final UUID SHOP = UUID.randomUUID();
    static final UUID BARBER = UUID.randomUUID();
    static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    static final Instant NOW = Instant.parse("2026-10-05T14:00:00Z");

    final Caller schedule = new Caller("barber-saas-schedule-api", Role.SERVICE, null, "token");

    Fakes.Repository repository;
    BusySlotUseCases busySlots;

    @BeforeEach
    void setUp() {
        repository = new Fakes.Repository();
        busySlots = new QueryBusySlots(repository);
    }

    Appointment stored(UUID shop, UUID barber, LocalDate date, LocalTime start) {
        Appointment a = Appointment.book(UUID.randomUUID(), shop, UUID.randomUUID(), barber, UUID.randomUUID(),
                Slot.of(date, start, 30), Money.ofCents(1), null, UUID.randomUUID(), LocalDateTime.of(2026, 10, 5, 9, 0),
                NOW);
        repository.rows.put(a.id(), a);
        return a;
    }

    @Test
    void scheduleGetsTheTakenTimesOfTheBarberThatDateInStartOrder() {
        stored(SHOP, BARBER, DAY, LocalTime.of(11, 0));
        stored(SHOP, BARBER, DAY, LocalTime.of(9, 0)).confirm(NOW);
        Appointment inProgress = stored(SHOP, BARBER, DAY, LocalTime.of(10, 0));
        inProgress.confirm(NOW);
        inProgress.start(NOW);

        List<Slot> busy = busySlots.busy(schedule, SHOP, BARBER, DAY);

        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0)),
                busy.stream().map(Slot::start).toList());
        assertEquals(LocalTime.of(9, 30), busy.get(0).end());
    }

    @Test
    void cancelledCompletedAndNoShowAppointmentsTakeNoTime() {
        stored(SHOP, BARBER, DAY, LocalTime.of(9, 0)).cancel(null, false, 0, LocalDateTime.of(2026, 10, 5, 9, 0), NOW);
        Appointment completed = stored(SHOP, BARBER, DAY, LocalTime.of(10, 0));
        completed.confirm(NOW);
        completed.start(NOW);
        completed.complete(NOW);
        Appointment noShow = stored(SHOP, BARBER, DAY, LocalTime.of(11, 0));
        noShow.confirm(NOW);
        noShow.markNoShow(NOW);

        assertTrue(busySlots.busy(schedule, SHOP, BARBER, DAY).isEmpty());
    }

    @Test
    void onlyThatBarbershopThatBarberAndThatDateCount() {
        stored(UUID.randomUUID(), BARBER, DAY, LocalTime.of(9, 0));
        stored(SHOP, UUID.randomUUID(), DAY, LocalTime.of(10, 0));
        stored(SHOP, BARBER, DAY.plusDays(1), LocalTime.of(11, 0));

        assertTrue(busySlots.busy(schedule, SHOP, BARBER, DAY).isEmpty(), "unknown or foreign barber: empty");
    }

    @Test
    void onlyTheServiceTokenOfScheduleApiMayAsk() {
        List<Caller> refused = List.of(
                new Caller("barber-saas-workflow", Role.SERVICE, null, "t"),
                new Caller(UUID.randomUUID().toString(), Role.CLIENT, SHOP, "t"),
                new Caller(UUID.randomUUID().toString(), Role.BARBER, SHOP, "t"),
                new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, SHOP, "t"),
                new Caller("barber-saas-schedule-api", Role.CLIENT, SHOP, "t"));

        for (Caller caller : refused) {
            assertThrows(Forbidden.class, () -> busySlots.busy(caller, SHOP, BARBER, DAY), caller.toString());
        }
    }
}
