package co.edu.corhuila.barbersaas.appointment.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases.BookCommand;
import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases.Filter;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Created;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog.CatalogService;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidValue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The use cases with doubles of every outbound port (HU-APPT-001 #4, HU-APPT-002 #8, HU-TENANT-001 #13). */
class ManageAppointmentsTest {

    static final UUID SHOP = UUID.randomUUID();
    static final UUID OTHER_SHOP = UUID.randomUUID();
    static final UUID BARBER = UUID.randomUUID();
    static final UUID SERVICE = UUID.randomUUID();
    static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    /** 09:00 in Bogotá (UTC−5) on 2026-10-05. */
    static final Instant NOW = Instant.parse("2026-10-05T14:00:00Z");

    final UUID clientId = UUID.randomUUID();
    final Caller client = new Caller(clientId.toString(), Role.CLIENT, SHOP, "token");
    final Caller otherClient = new Caller(UUID.randomUUID().toString(), Role.CLIENT, SHOP, "token");
    final Caller barber = new Caller(UUID.randomUUID().toString(), Role.BARBER, SHOP, "token");
    final Caller admin = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, SHOP, "token");
    final Caller otherAdmin = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, OTHER_SHOP, "token");
    final Caller superAdmin = new Caller(UUID.randomUUID().toString(), Role.SUPER_ADMIN, null, "token");

    Fakes.Repository repository;
    Fakes.Catalog catalog;
    Fakes.Availability availability;
    Fakes.FixedClock clock;
    ManageAppointments appointments;

    @BeforeEach
    void setUp() {
        repository = new Fakes.Repository();
        catalog = new Fakes.Catalog();
        catalog.services.put(SERVICE, new CatalogService(SERVICE, 30, 2_500_000));
        availability = new Fakes.Availability();
        availability.barbers.put(BARBER, List.of(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0)));
        clock = new Fakes.FixedClock(NOW);
        appointments = new ManageAppointments(repository, catalog, availability, clock, new Fakes.Sequence());
    }

    BookCommand at(LocalTime start) {
        return new BookCommand(BARBER, SERVICE, null, DAY, start, null);
    }

    Appointment bookedByClient() {
        return appointments.book(client, at(LocalTime.of(10, 0)), "key-" + UUID.randomUUID()).value();
    }

    // --- book ----------------------------------------------------------------------------------

    @Test
    void aClientBooksAndTheServerComputesTheEndAndThePrice() {
        Created<Appointment> result = appointments.book(client, at(LocalTime.of(9, 30)), "key-00000001");

        Appointment a = result.value();
        assertTrue(result.created());
        assertEquals(AppointmentStatus.PENDING, a.status());
        assertEquals(SHOP, a.barbershopId(), "the tenant comes from the token");
        assertEquals(clientId, a.clientId(), "a client books for themselves");
        assertEquals(clientId, a.createdBy());
        assertEquals(LocalTime.of(10, 0), a.slot().end());
        assertEquals(2_500_000, a.price().cents());
        assertEquals(List.of("AppointmentCreated"), repository.outbox.stream().map(OutboxEvent::type).toList());
    }

    @Test
    void thePriceIsASnapshotThatALaterPriceChangeDoesNotTouch() {
        Appointment a = appointments.book(client, at(LocalTime.of(9, 30)), "key-00000001").value();

        catalog.services.put(SERVICE, new CatalogService(SERVICE, 30, 9_900_000));

        assertEquals(2_500_000, appointments.get(client, a.id()).price().cents());
    }

    @Test
    void theSameKeyAndRequestReturnTheAppointmentAlreadyCreated() {
        Appointment first = appointments.book(client, at(LocalTime.of(9, 30)), "key-00000001").value();

        Created<Appointment> retry = appointments.book(client, at(LocalTime.of(9, 30)), "key-00000001");

        assertFalse(retry.created());
        assertEquals(first.id(), retry.value().id());
        assertEquals(1, repository.rows.size());
    }

    @Test
    void theSameKeyWithAnotherRequestIsRefused() {
        appointments.book(client, at(LocalTime.of(9, 30)), "key-00000001");

        assertThrows(IdempotencyKeyReused.class,
                () -> appointments.book(client, at(LocalTime.of(10, 0)), "key-00000001"));
    }

    @Test
    void staffBooksAWalkInWithoutAClient() {
        Appointment a = appointments.book(barber, at(LocalTime.of(9, 0)), "key-00000001").value();

        assertNull(a.clientId());
        assertEquals(UUID.fromString(barber.subject()), a.createdBy());
    }

    @Test
    void staffBooksForAClient() {
        BookCommand forClient = new BookCommand(BARBER, SERVICE, clientId, DAY, LocalTime.of(9, 0), null);

        assertEquals(clientId, appointments.book(admin, forClient, "key-00000001").value().clientId());
    }

    @Test
    void aClientNeverSendsAClientId() {
        BookCommand forSomeoneElse = new BookCommand(BARBER, SERVICE, UUID.randomUUID(), DAY, LocalTime.of(9, 0), null);

        assertThrows(Forbidden.class, () -> appointments.book(client, forSomeoneElse, "key-00000001"));
    }

    @Test
    void aSuperAdminCannotBook() {
        assertThrows(Forbidden.class, () -> appointments.book(superAdmin, at(LocalTime.of(9, 0)), "key-00000001"));
    }

    @Test
    void aServiceThatDoesNotExistInTheBarbershopIsNotFound() {
        BookCommand unknown = new BookCommand(BARBER, UUID.randomUUID(), null, DAY, LocalTime.of(9, 0), null);

        assertThrows(NotFound.class, () -> appointments.book(client, unknown, "key-00000001"));
    }

    @Test
    void aBarberThatDoesNotExistInTheBarbershopIsNotFound() {
        BookCommand unknown = new BookCommand(UUID.randomUUID(), SERVICE, null, DAY, LocalTime.of(9, 0), null);

        assertThrows(NotFound.class, () -> appointments.book(client, unknown, "key-00000001"));
    }

    @Test
    void aSlotTheScheduleDoesNotOfferIsRefused() {
        assertThrows(BusinessRuleViolation.class,
                () -> appointments.book(client, at(LocalTime.of(14, 0)), "key-00000001"));
        assertTrue(repository.rows.isEmpty());
    }

    @Test
    void anOverlappingActiveAppointmentIsRefused() {
        appointments.book(otherClient, at(LocalTime.of(9, 30)), "key-00000001");

        assertThrows(BusinessRuleViolation.class,
                () -> appointments.book(client, at(LocalTime.of(9, 30)), "key-00000002"));
    }

    @Test
    void aCancelledAppointmentFreesItsSlot() {
        Appointment first = appointments.book(otherClient, at(LocalTime.of(9, 30)), "key-00000001").value();
        appointments.cancel(admin, first.id(), null);

        assertTrue(appointments.book(client, at(LocalTime.of(9, 30)), "key-00000002").created());
    }

    @Test
    void losingTheRaceToTheDatabaseConstraintIsABusinessRuleViolation() {
        repository.loseTheRace = true;

        assertThrows(BusinessRuleViolation.class,
                () -> appointments.book(client, at(LocalTime.of(9, 30)), "key-00000001"));
    }

    @Test
    void aDateInThePastOfTheBarbershopIsRefused() {
        BookCommand yesterday = new BookCommand(BARBER, SERVICE, null, LocalDate.of(2026, 10, 4),
                LocalTime.of(9, 0), null);

        assertThrows(InvalidValue.class, () -> appointments.book(client, yesterday, "key-00000001"));
    }

    // --- list and get --------------------------------------------------------------------------

    @Test
    void aClientListsOnlyTheirOwnAppointments() {
        Appointment mine = bookedByClient();
        appointments.book(otherClient, at(LocalTime.of(9, 0)), "key-00000009");

        Page<Appointment> page = appointments.list(client, Filter.none(), new Page.Request(1, 20));

        assertEquals(List.of(mine.id()), page.items().stream().map(Appointment::id).toList());
    }

    @Test
    void aBarberFilterFromAClientStillOnlyShowsTheirOwn() {
        Appointment mine = bookedByClient();
        appointments.book(otherClient, at(LocalTime.of(9, 0)), "key-00000009");

        Page<Appointment> page = appointments.list(client, new Filter(null, BARBER, DAY), new Page.Request(1, 20));

        assertEquals(List.of(mine.id()), page.items().stream().map(Appointment::id).toList());
    }

    @Test
    void staffListsTheWholeBarbershopMostRecentFirst() {
        bookedByClient();
        appointments.book(otherClient, at(LocalTime.of(9, 0)), "key-00000009");

        Page<Appointment> page = appointments.list(barber, new Filter(AppointmentStatus.PENDING, BARBER, DAY),
                new Page.Request(1, 20));

        assertEquals(List.of(LocalTime.of(10, 0), LocalTime.of(9, 0)),
                page.items().stream().map(a -> a.slot().start()).toList());
    }

    @Test
    void anotherBarbershopSeesNothing() {
        Appointment a = bookedByClient();

        assertEquals(0, appointments.list(otherAdmin, Filter.none(), new Page.Request(1, 20)).total());
        assertThrows(NotFound.class, () -> appointments.get(otherAdmin, a.id()));
        assertThrows(NotFound.class, () -> appointments.confirm(otherAdmin, a.id()));
        assertThrows(NotFound.class, () -> appointments.cancel(otherAdmin, a.id(), null));
    }

    @Test
    void aClientCannotSeeOrCancelAnotherClientsAppointment() {
        Appointment a = bookedByClient();

        assertThrows(NotFound.class, () -> appointments.get(otherClient, a.id()));
        assertThrows(NotFound.class, () -> appointments.cancel(otherClient, a.id(), null));
    }

    // --- transitions ---------------------------------------------------------------------------

    @Test
    void staffTakesAnAppointmentToCompletedAndCompletionGoesToTheOutbox() {
        Appointment a = bookedByClient();
        repository.outbox.clear();

        appointments.confirm(barber, a.id());
        appointments.start(barber, a.id());
        Appointment done = appointments.complete(admin, a.id());

        assertEquals(AppointmentStatus.COMPLETED, done.status());
        assertEquals(List.of("AppointmentConfirmed", "AppointmentCompleted"),
                repository.outbox.stream().map(OutboxEvent::type).toList());
        OutboxEvent completed = repository.outbox.get(1);
        assertEquals(a.id(), completed.aggregateId());
        assertEquals(2_500_000L, completed.payload().get("priceAtBookingCents"));
        assertEquals(clientId.toString(), completed.payload().get("clientId"));
    }

    @Test
    void aConfirmedClientWhoDoesNotComeIsMarkedAsANoShow() {
        Appointment a = bookedByClient();
        appointments.confirm(admin, a.id());

        assertEquals(AppointmentStatus.NO_SHOW, appointments.markNoShow(barber, a.id()).status());
        assertEquals("AppointmentMarkedNoShow", repository.outbox.get(repository.outbox.size() - 1).type());
    }

    @Test
    void aClientCannotMoveTheStateMachine() {
        Appointment a = bookedByClient();

        assertThrows(Forbidden.class, () -> appointments.confirm(client, a.id()));
        assertThrows(Forbidden.class, () -> appointments.start(client, a.id()));
        assertThrows(Forbidden.class, () -> appointments.complete(client, a.id()));
        assertThrows(Forbidden.class, () -> appointments.markNoShow(client, a.id()));
    }

    @Test
    void anInvalidTransitionChangesNothingAndEmitsNothing() {
        Appointment a = bookedByClient();
        repository.outbox.clear();

        assertThrows(InvalidStatusTransition.class, () -> appointments.complete(barber, a.id()));
        assertEquals(AppointmentStatus.PENDING, appointments.get(barber, a.id()).status());
        assertTrue(repository.outbox.isEmpty());
    }

    // --- cancel --------------------------------------------------------------------------------

    @Test
    void aClientCancelsTheirOwnAppointmentOutsideTheWindow() {
        Appointment a = bookedByClient();

        Appointment cancelled = appointments.cancel(client, a.id(), "Something came up");

        assertEquals(AppointmentStatus.CANCELLED, cancelled.status());
        assertEquals("Something came up", cancelled.cancelledReason());
        assertEquals("AppointmentCancelled", repository.outbox.get(repository.outbox.size() - 1).type());
    }

    @Test
    void aClientCannotCancelInsideTheBarbershopWindow() {
        Appointment a = bookedByClient();
        clock.now = Instant.parse("2026-10-10T12:00:00Z"); // 07:00 in Bogotá, 3 h before 10:00

        assertThrows(BusinessRuleViolation.class, () -> appointments.cancel(client, a.id(), null));
    }

    @Test
    void staffCancelsInsideTheWindow() {
        Appointment a = bookedByClient();
        clock.now = Instant.parse("2026-10-10T14:55:00Z"); // 09:55 in Bogotá

        assertEquals(AppointmentStatus.CANCELLED, appointments.cancel(barber, a.id(), null).status());
    }
}
