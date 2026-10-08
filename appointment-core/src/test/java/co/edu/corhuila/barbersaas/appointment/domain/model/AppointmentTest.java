package co.edu.corhuila.barbersaas.appointment.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidValue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The aggregate on its own: no ports, no framework (05-architecture/hexagonal-architecture.md, "Testing"). */
class AppointmentTest {

    static final UUID SHOP = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID BARBER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 9, 0);
    static final Instant INSTANT = Instant.parse("2026-10-05T14:00:00Z");

    static Appointment booked(LocalTime start, int minutes) {
        return Appointment.book(UUID.randomUUID(), SHOP, CLIENT, BARBER, SERVICE, Slot.of(DAY, start, minutes),
                Money.ofCents(2_500_000), "Fade, please", CLIENT, NOW, INSTANT);
    }

    static Appointment in(AppointmentStatus status) {
        Appointment a = booked(LocalTime.of(10, 0), 30);
        switch (status) {
            case PENDING -> { }
            case CONFIRMED -> a.confirm(INSTANT);
            case IN_PROGRESS -> { a.confirm(INSTANT); a.start(INSTANT); }
            case COMPLETED -> { a.confirm(INSTANT); a.start(INSTANT); a.complete(INSTANT); }
            case CANCELLED -> a.cancel("Changed my mind", false, 0, NOW, INSTANT);
            case NO_SHOW -> { a.confirm(INSTANT); a.markNoShow(INSTANT); }
        }
        return a;
    }

    // --- booking -------------------------------------------------------------------------------

    @Test
    void aBookingStartsPendingWithTheEndAndPriceComputedByTheServer() {
        Appointment a = booked(LocalTime.of(14, 30), 45);

        assertEquals(AppointmentStatus.PENDING, a.status());
        assertEquals(LocalTime.of(15, 15), a.slot().end());
        assertEquals(2_500_000, a.price().cents());
        assertEquals(CLIENT, a.createdBy());
        assertEquals(INSTANT, a.createdAt());
        assertNull(a.couponId());
    }

    @Test
    void aRewardCouponMakesThePriceSnapshotZero() {
        UUID coupon = UUID.randomUUID();

        Appointment a = Appointment.book(UUID.randomUUID(), SHOP, CLIENT, BARBER, SERVICE,
                Slot.of(DAY, LocalTime.of(14, 30), 45), Money.ofCents(2_500_000), null, CLIENT, coupon, NOW, INSTANT);

        assertEquals(0, a.price().cents());
        assertEquals(coupon, a.couponId());
    }

    @Test
    void aWalkInCannotCarryARewardCoupon() {
        assertThrows(InvalidValue.class, () -> Appointment.book(UUID.randomUUID(), SHOP, null, BARBER, SERVICE,
                Slot.of(DAY, LocalTime.of(14, 30), 45), Money.ofCents(2_500_000), null, CLIENT, UUID.randomUUID(),
                NOW, INSTANT));
    }

    @Test
    void aWalkInHasNoClient() {
        Appointment a = Appointment.book(UUID.randomUUID(), SHOP, null, BARBER, SERVICE,
                Slot.of(DAY, LocalTime.of(9, 0), 30), Money.ofCents(0), null, BARBER, NOW, INSTANT);

        assertNull(a.clientId());
        assertEquals(BARBER, a.createdBy());
    }

    @Test
    void aSlotInThePastCannotBeBooked() {
        Slot yesterday = Slot.of(NOW.toLocalDate().minusDays(1), LocalTime.of(10, 0), 30);
        Slot earlierToday = Slot.of(NOW.toLocalDate(), LocalTime.of(8, 30), 30);

        for (Slot past : new Slot[] {yesterday, earlierToday}) {
            assertThrows(InvalidValue.class, () -> Appointment.book(UUID.randomUUID(), SHOP, CLIENT, BARBER, SERVICE,
                    past, Money.ofCents(100), null, CLIENT, NOW, INSTANT));
        }
    }

    @Test
    void laterTodayCanBeBooked() {
        Slot laterToday = Slot.of(NOW.toLocalDate(), LocalTime.of(11, 0), 30);

        Appointment a = Appointment.book(UUID.randomUUID(), SHOP, CLIENT, BARBER, SERVICE, laterToday,
                Money.ofCents(100), null, CLIENT, NOW, INSTANT);

        assertEquals(AppointmentStatus.PENDING, a.status());
    }

    @Test
    void notesAreLimitedTo500Characters() {
        assertThrows(InvalidValue.class, () -> Appointment.book(UUID.randomUUID(), SHOP, CLIENT, BARBER, SERVICE,
                Slot.of(DAY, LocalTime.of(10, 0), 30), Money.ofCents(100), "x".repeat(501), CLIENT, NOW, INSTANT));
    }

    // --- slot ----------------------------------------------------------------------------------

    @Test
    void aServiceThatDoesNotFitInTheDayIsRefused() {
        assertThrows(BusinessRuleViolation.class, () -> Slot.of(DAY, LocalTime.of(23, 30), 45));
    }

    @Test
    void aSlotNeedsAPositiveDuration() {
        assertThrows(InvalidValue.class, () -> Slot.of(DAY, LocalTime.of(10, 0), 0));
    }

    @Test
    void slotsOverlapOnlyWhenTheyShareTime() {
        Slot ten = Slot.of(DAY, LocalTime.of(10, 0), 30);

        assertEquals(true, ten.overlaps(Slot.of(DAY, LocalTime.of(10, 15), 30)));
        assertEquals(true, ten.overlaps(Slot.of(DAY, LocalTime.of(9, 45), 30)));
        assertEquals(false, ten.overlaps(Slot.of(DAY, LocalTime.of(10, 30), 30)), "adjacent slots do not overlap");
        assertEquals(false, ten.overlaps(Slot.of(DAY.plusDays(1), LocalTime.of(10, 0), 30)), "another day");
    }

    @Test
    void cancelledAndNoShowAppointmentsFreeTheirSlot() {
        assertEquals(true, in(AppointmentStatus.PENDING).takesTime());
        assertEquals(true, in(AppointmentStatus.CONFIRMED).takesTime());
        assertEquals(true, in(AppointmentStatus.IN_PROGRESS).takesTime());
        assertEquals(false, in(AppointmentStatus.CANCELLED).takesTime());
        assertEquals(false, in(AppointmentStatus.NO_SHOW).takesTime());
    }

    // --- price ---------------------------------------------------------------------------------

    @Test
    void aPriceIsNeverNegative() {
        assertThrows(InvalidValue.class, () -> Money.ofCents(-1));
    }

    // --- state machine (state-appointment.md, INV-APPT-004) ------------------------------------

    @Test
    void theHappyPathGoesFromPendingToCompleted() {
        Appointment a = booked(LocalTime.of(10, 0), 30);
        Instant later = INSTANT.plusSeconds(60);

        a.confirm(later);
        assertEquals(AppointmentStatus.CONFIRMED, a.status());
        a.start(later);
        assertEquals(AppointmentStatus.IN_PROGRESS, a.status());
        a.complete(later);
        assertEquals(AppointmentStatus.COMPLETED, a.status());
        assertEquals(later, a.updatedAt());
    }

    @Test
    void aConfirmedClientWhoDoesNotComeIsANoShow() {
        Appointment a = in(AppointmentStatus.CONFIRMED);

        a.markNoShow(INSTANT);

        assertEquals(AppointmentStatus.NO_SHOW, a.status());
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"PENDING"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyAPendingAppointmentCanBeConfirmed(AppointmentStatus status) {
        assertRefused(status, a -> a.confirm(INSTANT));
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CONFIRMED"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyAConfirmedAppointmentCanStart(AppointmentStatus status) {
        assertRefused(status, a -> a.start(INSTANT));
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"IN_PROGRESS"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyAnAppointmentInProgressCanBeCompleted(AppointmentStatus status) {
        assertRefused(status, a -> a.complete(INSTANT));
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CONFIRMED"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyAConfirmedAppointmentCanBeANoShow(AppointmentStatus status) {
        assertRefused(status, a -> a.markNoShow(INSTANT));
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"IN_PROGRESS", "COMPLETED", "CANCELLED", "NO_SHOW"})
    void onlyPendingOrConfirmedAppointmentsCanBeCancelled(AppointmentStatus status) {
        assertRefused(status, a -> a.cancel(null, false, 0, NOW, INSTANT));
    }

    private static void assertRefused(AppointmentStatus status, Consumer<Appointment> transition) {
        Appointment a = in(status);
        assertThrows(InvalidStatusTransition.class, () -> transition.accept(a));
        assertEquals(status, a.status(), "a refused transition changes nothing");
    }

    // --- cancellation window (INV-APPT-003) ----------------------------------------------------

    @Test
    void aClientCancelsOutsideTheWindowAndTheReasonIsKept() {
        Appointment a = booked(LocalTime.of(10, 0), 30);

        a.cancel("Something came up", true, 4, DAY.atTime(5, 59), INSTANT);

        assertEquals(AppointmentStatus.CANCELLED, a.status());
        assertEquals("Something came up", a.cancelledReason());
    }

    @Test
    void aClientCannotCancelInsideTheWindow() {
        Appointment a = booked(LocalTime.of(10, 0), 30);

        assertThrows(BusinessRuleViolation.class, () -> a.cancel(null, true, 4, DAY.atTime(6, 1), INSTANT));
        assertEquals(AppointmentStatus.PENDING, a.status());
    }

    @Test
    void staffIsExemptFromTheWindow() {
        Appointment a = booked(LocalTime.of(10, 0), 30);

        a.cancel(null, false, 4, DAY.atTime(9, 55), INSTANT);

        assertEquals(AppointmentStatus.CANCELLED, a.status());
    }

    @Test
    void theCancellationReasonIsLimitedTo255Characters() {
        Appointment a = booked(LocalTime.of(10, 0), 30);

        assertThrows(InvalidValue.class, () -> a.cancel("x".repeat(256), false, 0, NOW, INSTANT));
    }
}
