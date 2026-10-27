package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository.AlreadyReviewed;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository.Summary;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.Money;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** What every ReviewRepository must do, in memory and in PostgreSQL alike (DEC-APPT-11). */
abstract class ReviewRepositoryContract {

    final UUID shop = UUID.randomUUID();
    final UUID barber = UUID.randomUUID();
    final UUID client = UUID.randomUUID();
    final LocalDate day = LocalDate.now().plusDays(40);
    final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private int nextStart = 0;

    abstract AppointmentRepository appointments();

    abstract ReviewRepository reviews();

    /** A stored appointment of the barber; the review table references it. */
    Appointment appointment(UUID barberId) {
        Appointment a = Appointment.book(UUID.randomUUID(), shop, client, barberId, UUID.randomUUID(),
                Slot.of(day, LocalTime.of(6, 0).plusMinutes(30L * nextStart++), 30), Money.ofCents(2_500_000), null,
                client, LocalDateTime.now(), now);
        appointments().saveNew(a, new Idempotency.Key("it-" + UUID.randomUUID(), "POST /api/v1/appointments", "h"),
                List.of());
        return a;
    }

    Review review(Appointment a, int rating, Instant at) {
        return new Review(UUID.randomUUID(), a.id(), a.barbershopId(), a.barberId(), client, "María G.", rating,
                "Muy bien", at);
    }

    @Test
    void aReviewIsStoredAndReadBackWithEveryField() {
        Appointment a = appointment(barber);
        Review r = review(a, 5, now);

        assertFalse(reviews().exists(a.id()));
        reviews().saveNew(r);

        assertTrue(reviews().exists(a.id()));
        assertEquals(List.of(r), reviews().page(shop, null, new Page.Request(1, 20)).items());
    }

    @Test
    void onlyTheAppointmentsWithAReviewAreReturned() {
        Appointment reviewed = appointment(barber);
        Appointment notYet = appointment(barber);
        reviews().saveNew(review(reviewed, 4, now));

        assertEquals(Set.of(reviewed.id()), reviews().reviewedAmong(List.of(reviewed.id(), notYet.id(), UUID.randomUUID())));
        assertEquals(Set.of(), reviews().reviewedAmong(List.of()));
    }

    @Test
    void theSecondReviewOfAnAppointmentIsRefused() {
        Appointment a = appointment(barber);
        reviews().saveNew(review(a, 5, now));

        assertThrows(AlreadyReviewed.class, () -> reviews().saveNew(review(a, 1, now)));
        assertEquals(1, reviews().summary(shop, null).reviewCount());
    }

    @Test
    void twoConcurrentReviewsOfOneAppointmentStoreOnlyOne() throws Exception {
        Appointment a = appointment(barber);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            go.await();
            try {
                reviews().saveNew(review(a, 4, now));
                return true;
            } catch (AlreadyReviewed refused) {
                return false;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            go.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0), "exactly one review is stored");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, reviews().summary(shop, null).reviewCount());
    }

    @Test
    void thePageIsNewestFirstFiltersByBarberAndTheSummaryMatchesTheFilter() {
        UUID otherBarber = UUID.randomUUID();
        Review oldest = review(appointment(barber), 5, now.minusSeconds(120));
        Review middle = review(appointment(barber), 4, now.minusSeconds(60));
        Review newest = review(appointment(otherBarber), 3, now);
        reviews().saveNew(middle);
        reviews().saveNew(newest);
        reviews().saveNew(oldest);

        Page<Review> all = reviews().page(shop, null, new Page.Request(1, 2));
        assertEquals(List.of(newest.id(), middle.id()), all.items().stream().map(Review::id).toList());
        assertEquals(3, all.total());
        assertEquals(new Summary(4.0, 3), reviews().summary(shop, null));

        Page<Review> ofBarber = reviews().page(shop, barber, new Page.Request(1, 20));
        assertEquals(List.of(middle.id(), oldest.id()), ofBarber.items().stream().map(Review::id).toList());
        assertEquals(new Summary(4.5, 2), reviews().summary(shop, barber));
    }

    @Test
    void anotherBarbershopSeesNoReviewAndANullAverage() {
        reviews().saveNew(review(appointment(barber), 5, now));

        UUID otherShop = UUID.randomUUID();
        assertTrue(reviews().page(otherShop, null, new Page.Request(1, 20)).items().isEmpty());
        Summary none = reviews().summary(otherShop, null);
        assertNull(none.averageRating());
        assertEquals(0, none.reviewCount());
    }
}
