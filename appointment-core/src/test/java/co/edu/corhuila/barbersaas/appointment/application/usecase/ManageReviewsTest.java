package co.edu.corhuila.barbersaas.appointment.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases.BookCommand;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ReviewUseCases.Listing;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog.CatalogService;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyUnavailable;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidValue;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DEC-APPT-11 with doubles of every outbound port (HU-APPT-005 #130). */
class ManageReviewsTest {

    static final UUID SHOP = UUID.randomUUID();
    static final UUID OTHER_SHOP = UUID.randomUUID();
    static final UUID BARBER = UUID.randomUUID();
    static final UUID OTHER_BARBER = UUID.randomUUID();
    static final UUID SERVICE = UUID.randomUUID();
    static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    static final Instant NOW = Instant.parse("2026-10-05T14:00:00Z");

    final UUID clientId = UUID.randomUUID();
    final Caller client = new Caller(clientId.toString(), Role.CLIENT, SHOP, "token");
    final Caller clientWithoutShop = new Caller(clientId.toString(), Role.CLIENT, null, "token");
    final Caller clientOfAnotherShop = new Caller(clientId.toString(), Role.CLIENT, OTHER_SHOP, "token");
    final Caller otherClient = new Caller(UUID.randomUUID().toString(), Role.CLIENT, SHOP, "token");
    final Caller barber = new Caller(UUID.randomUUID().toString(), Role.BARBER, SHOP, "token");

    Fakes.Repository repository;
    Fakes.Reviews reviews;
    Fakes.Users users;
    Fakes.FixedClock clock;
    ManageAppointments appointments;
    ManageReviews manage;

    @BeforeEach
    void setUp() {
        repository = new Fakes.Repository();
        Fakes.Catalog catalog = new Fakes.Catalog();
        catalog.services.put(SERVICE, new CatalogService(SERVICE, 30, 2_500_000));
        Fakes.Availability availability = new Fakes.Availability();
        availability.barbers.put(BARBER, List.of(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0)));
        availability.barbers.put(OTHER_BARBER, List.of(LocalTime.of(9, 0)));
        clock = new Fakes.FixedClock(NOW);
        appointments = new ManageAppointments(repository, catalog, availability, new Fakes.Coupons(), clock,
                new Fakes.Sequence());
        reviews = new Fakes.Reviews();
        users = new Fakes.Users();
        users.names.put(clientId, "María Gómez");
        manage = new ManageReviews(repository, reviews, users, clock, new Fakes.Sequence());
    }

    Appointment booked(UUID barberId, LocalTime start) {
        return appointments.book(client, new BookCommand(barberId, SERVICE, null, DAY, start, null),
                "key-" + UUID.randomUUID()).value();
    }

    Appointment completed(UUID barberId, LocalTime start) {
        Appointment a = booked(barberId, start);
        appointments.confirm(barber, a.id());
        appointments.start(barber, a.id());
        return appointments.complete(barber, a.id());
    }

    @Test
    void theClientReviewsTheirCompletedAppointmentWithItsBarbershopBarberAndDisplayName() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));

        Review r = manage.review(client, a.id(), 5, "Excelente corte");

        assertEquals(a.id(), r.appointmentId());
        assertEquals(SHOP, r.barbershopId());
        assertEquals(BARBER, r.barberId());
        assertEquals(clientId, r.clientId());
        assertEquals("María G.", r.clientDisplayName());
        assertEquals(5, r.rating());
        assertEquals("Excelente corte", r.comment());
        assertEquals(r, reviews.byAppointment.get(a.id()));
    }

    @Test
    void onlyTheReviewedCompletedAppointmentsAreMarked() {
        Appointment reviewed = completed(BARBER, LocalTime.of(9, 0));
        Appointment notYet = completed(BARBER, LocalTime.of(9, 30));
        Appointment pending = booked(BARBER, LocalTime.of(10, 0));
        manage.review(client, reviewed.id(), 4, null);

        assertEquals(Set.of(reviewed.id()), manage.reviewedAmong(List.of(reviewed, notYet, pending)));
        assertEquals(Set.of(), manage.reviewedAmong(List.of(pending)));
    }

    @Test
    void aClientTokenWithoutABarbershopReviewsTooButNotWithAnotherBarbershop() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));

        assertThrows(NotFound.class, () -> manage.review(clientOfAnotherShop, a.id(), 4, null));
        assertEquals(SHOP, manage.review(clientWithoutShop, a.id(), 4, null).barbershopId());
    }

    @Test
    void anotherClientsAppointmentIsNotFound() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));

        assertThrows(NotFound.class, () -> manage.review(otherClient, a.id(), 5, null));
        assertThrows(NotFound.class, () -> manage.review(client, UUID.randomUUID(), 5, null));
        assertTrue(reviews.byAppointment.isEmpty());
    }

    @Test
    void onlyAClientReviews() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));

        assertThrows(Forbidden.class, () -> manage.review(barber, a.id(), 5, null));
    }

    @Test
    void anAppointmentThatIsNotCompletedIsA422() {
        Appointment a = booked(BARBER, LocalTime.of(9, 0));

        BusinessRuleViolation refused = assertThrows(BusinessRuleViolation.class,
                () -> manage.review(client, a.id(), 5, null));
        assertEquals("Solo se puede calificar una cita completada", refused.getMessage());
        assertEquals(0, users.calls, "identity-auth is not asked for a refused review");
    }

    @Test
    void theSecondReviewOfAnAppointmentIsA422AndAsksNobody() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));
        manage.review(client, a.id(), 5, null);

        BusinessRuleViolation refused = assertThrows(BusinessRuleViolation.class,
                () -> manage.review(client, a.id(), 1, "otra vez"));
        assertEquals("Esta cita ya fue calificada", refused.getMessage());
        assertEquals(1, users.calls);
        assertEquals(5, reviews.byAppointment.get(a.id()).rating());
    }

    @Test
    void losingTheRaceToUqReviewAppointmentIsTheSame422() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));
        reviews.loseTheRace = true;

        BusinessRuleViolation refused = assertThrows(BusinessRuleViolation.class,
                () -> manage.review(client, a.id(), 5, null));
        assertEquals("Esta cita ya fue calificada", refused.getMessage());
    }

    @Test
    void aRatingOutsideOneToFiveOrALongCommentIsInvalid() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));

        assertThrows(InvalidValue.class, () -> manage.review(client, a.id(), 0, null));
        assertThrows(InvalidValue.class, () -> manage.review(client, a.id(), 6, null));
        assertThrows(InvalidValue.class, () -> manage.review(client, a.id(), 5, "a".repeat(501)));
        assertEquals(5, manage.review(client, a.id(), 5, "a".repeat(500)).rating());
    }

    @Test
    void whenIdentityAuthDoesNotAnswerNothingIsStored() {
        Appointment a = completed(BARBER, LocalTime.of(9, 0));
        users.down = true;

        assertThrows(DependencyUnavailable.class, () -> manage.review(client, a.id(), 5, null));
        assertTrue(reviews.byAppointment.isEmpty());
    }

    @Test
    void theListIsNewestFirstWithTheAverageToOneDecimalAndFiltersByBarber() {
        Appointment first = completed(BARBER, LocalTime.of(9, 0));
        Appointment second = completed(BARBER, LocalTime.of(9, 30));
        Appointment third = completed(OTHER_BARBER, LocalTime.of(9, 0));
        manage.review(client, first.id(), 5, null);
        clock.now = NOW.plusSeconds(60);
        manage.review(client, second.id(), 4, null);
        clock.now = NOW.plusSeconds(120);
        manage.review(client, third.id(), 4, null);

        Listing all = manage.list(SHOP, null, new Page.Request(1, 20));
        assertEquals(List.of(third.id(), second.id(), first.id()),
                all.page().items().stream().map(Review::appointmentId).toList());
        assertEquals(4.3, all.summary().averageRating());
        assertEquals(3, all.summary().reviewCount());

        Listing ofBarber = manage.list(SHOP, BARBER, new Page.Request(1, 20));
        assertEquals(2, ofBarber.page().total());
        assertEquals(4.5, ofBarber.summary().averageRating());
    }

    @Test
    void aBarbershopWithoutReviewsHasANullAverageAndAnotherBarbershopSeesNone() {
        manage.review(client, completed(BARBER, LocalTime.of(9, 0)).id(), 5, null);

        Listing other = manage.list(OTHER_SHOP, null, new Page.Request(1, 20));
        assertTrue(other.page().items().isEmpty());
        assertNull(other.summary().averageRating());
        assertEquals(0, other.summary().reviewCount());
    }
}
