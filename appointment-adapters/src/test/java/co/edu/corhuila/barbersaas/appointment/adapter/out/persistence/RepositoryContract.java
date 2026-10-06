package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository.KeyTaken;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository.Query;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository.SlotTaken;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Money;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What every AppointmentRepository must do, in memory or in PostgreSQL. */
abstract class RepositoryContract {

    final UUID shop = UUID.randomUUID();
    final UUID client = UUID.randomUUID();
    final UUID barber = UUID.randomUUID();
    final LocalDate day = LocalDate.now().plusDays(30);
    final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    abstract AppointmentRepository repository();

    Appointment at(LocalTime start) {
        return Appointment.book(UUID.randomUUID(), shop, client, barber, UUID.randomUUID(), Slot.of(day, start, 30),
                Money.ofCents(2_500_000), "Fade", client, LocalDateTime.now(), now);
    }

    Idempotency.Key key() {
        return new Idempotency.Key("it-" + UUID.randomUUID(), "POST /api/v1/appointments", "hash");
    }

    OutboxEvent event(Appointment a, String type) {
        return new OutboxEvent(UUID.randomUUID(), a.id(), type, Map.of("appointmentId", a.id().toString()), now);
    }

    @Test
    void anAppointmentIsStoredWithItsKeyAndReadBackOnlyInItsBarbershop() {
        Appointment a = at(LocalTime.of(10, 0));
        Idempotency.Key key = key();

        repository().saveNew(a, key, List.of(event(a, "AppointmentCreated")));

        Appointment read = repository().findById(shop, a.id()).orElseThrow();
        assertEquals(a.slot(), read.slot());
        assertEquals(2_500_000, read.price().cents());
        assertEquals(AppointmentStatus.PENDING, read.status());
        assertEquals(a.id(), repository().findKey(key.key(), key.operation()).orElseThrow().resourceId());
        assertTrue(repository().findById(UUID.randomUUID(), a.id()).isEmpty(), "another barbershop sees nothing");
    }

    @Test
    void twoActiveAppointmentsOfABarberCannotOverlap() {
        Appointment first = at(LocalTime.of(10, 0));
        repository().saveNew(first, key(), List.of());

        assertTrue(repository().overlaps(barber, Slot.of(day, LocalTime.of(10, 15), 30)));
        assertFalse(repository().overlaps(barber, Slot.of(day, LocalTime.of(10, 30), 30)), "adjacent is free");
        assertThrows(SlotTaken.class, () -> repository().saveNew(at(LocalTime.of(10, 15)), key(), List.of()));
    }

    @Test
    void aCancelledAppointmentFreesItsSlot() {
        Appointment first = at(LocalTime.of(11, 0));
        repository().saveNew(first, key(), List.of());
        first.cancel("No longer needed", false, 0, LocalDateTime.now(), now);
        repository().update(first, List.of(event(first, "AppointmentCancelled")));

        assertEquals("No longer needed", repository().findById(shop, first.id()).orElseThrow().cancelledReason());
        assertFalse(repository().overlaps(barber, first.slot()));
        repository().saveNew(at(LocalTime.of(11, 0)), key(), List.of());
    }

    @Test
    void aKeyIsStoredOnlyOnce() {
        Idempotency.Key key = key();
        repository().saveNew(at(LocalTime.of(12, 0)), key, List.of());

        assertThrows(KeyTaken.class, () -> repository().saveNew(at(LocalTime.of(13, 0)), key, List.of()));
    }

    @Test
    void aPageFiltersAndShowsTheMostRecentFirst() {
        repository().saveNew(at(LocalTime.of(14, 0)), key(), List.of());
        repository().saveNew(at(LocalTime.of(15, 0)), key(), List.of());
        Appointment other = Appointment.book(UUID.randomUUID(), shop, UUID.randomUUID(), barber, UUID.randomUUID(),
                Slot.of(day, LocalTime.of(16, 0), 30), Money.ofCents(1), null, client, LocalDateTime.now(), now);
        repository().saveNew(other, key(), List.of());

        Page<Appointment> mine = repository().page(shop, new Query(client, AppointmentStatus.PENDING, barber, day),
                new Page.Request(1, 10));

        assertEquals(2, mine.total());
        assertEquals(List.of(LocalTime.of(15, 0), LocalTime.of(14, 0)),
                mine.items().stream().map(a -> a.slot().start()).toList());
        assertEquals(0, repository().page(UUID.randomUUID(), new Query(null, null, null, null),
                new Page.Request(1, 10)).total());
    }

    /** DEC-APPT-06: the client's own appointments of every barbershop, and nobody else's. */
    @Test
    void aClientsPageCrossesBarbershopsButNeverClients() {
        UUID otherShop = UUID.randomUUID();
        Appointment here = at(LocalTime.of(14, 0));
        repository().saveNew(here, key(), List.of());
        Appointment there = Appointment.book(UUID.randomUUID(), otherShop, client, UUID.randomUUID(),
                UUID.randomUUID(), Slot.of(day, LocalTime.of(9, 0), 30), Money.ofCents(1), null, client,
                LocalDateTime.now(), now);
        repository().saveNew(there, key(), List.of());
        Appointment notMine = Appointment.book(UUID.randomUUID(), otherShop, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Slot.of(day, LocalTime.of(10, 0), 30), Money.ofCents(1), null, client,
                LocalDateTime.now(), now);
        repository().saveNew(notMine, key(), List.of());

        Page<Appointment> mine = repository().pageOfClient(client, null, null, new Page.Request(1, 10));

        assertEquals(List.of(here.id(), there.id()), mine.items().stream().map(Appointment::id).toList());
        assertEquals(List.of(shop, otherShop), mine.items().stream().map(Appointment::barbershopId).toList());
        assertEquals(1, repository().pageOfClient(client, AppointmentStatus.PENDING, day, new Page.Request(1, 1))
                .items().size());
        assertEquals(0, repository().pageOfClient(client, AppointmentStatus.COMPLETED, null,
                new Page.Request(1, 10)).total());
        assertEquals(0, repository().pageOfClient(client, null, day.plusDays(1), new Page.Request(1, 10)).total());
    }

    @Test
    void theBusySlotsAreTheActiveTimesOfThatBarberAndDateInThatBarbershopInStartOrder() {
        Appointment late = at(LocalTime.of(15, 30));
        repository().saveNew(late, key(), List.of());
        Appointment early = at(LocalTime.of(8, 0));
        repository().saveNew(early, key(), List.of());
        Appointment cancelled = at(LocalTime.of(12, 0));
        repository().saveNew(cancelled, key(), List.of());
        cancelled.cancel(null, false, 0, LocalDateTime.now(), now);
        repository().update(cancelled, List.of());

        assertEquals(List.of(early.slot(), late.slot()), repository().busy(shop, barber, day));
        assertTrue(repository().busy(UUID.randomUUID(), barber, day).isEmpty(), "another barbershop");
        assertTrue(repository().busy(shop, barber, day.plusDays(1)).isEmpty(), "another date");
    }

    /** DEC-APPT-07: what the worker reads, in the order the events happened, and its confirmations. */
    @Test
    void theOutboxGivesPendingEventsOldestFirstAndForgetsConfirmedAndFailedOnes() {
        OutboxStore outbox = (OutboxStore) repository();
        Appointment a = at(LocalTime.of(19, 0));
        OutboxEvent first = new OutboxEvent(UUID.randomUUID(), a.id(), "AppointmentCreated",
                Map.of("barbershopId", shop.toString()), Instant.parse("2000-01-01T00:00:00Z"));
        OutboxEvent second = new OutboxEvent(UUID.randomUUID(), a.id(), "AppointmentConfirmed",
                Map.of("barbershopId", shop.toString()), Instant.parse("2000-01-01T00:00:01Z"));
        repository().saveNew(a, key(), List.of(second, first));

        List<OutboxStore.Stored> oldest = outbox.pending(2);

        assertEquals(List.of(first.id(), second.id()), oldest.stream().map(st -> st.event().id()).toList());
        assertEquals(shop.toString(), oldest.get(0).event().payload().get("barbershopId"));
        assertEquals(1, outbox.pending(1).size());
        assertTrue(outbox.markPublished(first.id(), now));
        assertTrue(outbox.markPublished(first.id(), now), "confirming again is accepted");
        assertTrue(outbox.markFailed(second.id(), "loyalty-api 422", now));
        assertTrue(outbox.pending(100).stream().noneMatch(st -> st.event().aggregateId().equals(a.id())));
        assertFalse(outbox.markPublished(UUID.randomUUID(), now));
        assertFalse(outbox.markFailed(UUID.randomUUID(), "x", now));
    }
}
