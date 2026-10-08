package co.edu.corhuila.barbersaas.appointment.domain.model;

import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidValue;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate of the appointment domain (02-domain/entities-and-rules.md). It owns its state
 * machine (INV-APPT-004), the price snapshot (INV-APPT-002) and the client's cancellation window
 * (INV-APPT-003). The overlap with other appointments (INV-APPT-001) needs other aggregates: the
 * use case checks it and the database guarantees it. A loyalty reward coupon, when the client has one,
 * pays the whole appointment (DEC-APPT-09, FR-010).
 */
public final class Appointment {

    static final int NOTES_MAX = 500;
    static final int REASON_MAX = 255;

    private final UUID id;
    private final UUID barbershopId;
    private final UUID clientId;
    private final UUID barberId;
    private final UUID serviceId;
    private final Slot slot;
    private final Money price;
    private final String notes;
    private final UUID createdBy;
    private final Instant createdAt;
    private final UUID couponId;
    private AppointmentStatus status;
    private String cancelledReason;
    private Instant updatedAt;

    private Appointment(UUID id, UUID barbershopId, UUID clientId, UUID barberId, UUID serviceId, Slot slot,
                        Money price, String notes, UUID createdBy, Instant createdAt, UUID couponId,
                        AppointmentStatus status, String cancelledReason, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.barbershopId = Objects.requireNonNull(barbershopId);
        this.clientId = clientId;
        this.barberId = Objects.requireNonNull(barberId);
        this.serviceId = Objects.requireNonNull(serviceId);
        this.slot = Objects.requireNonNull(slot);
        this.price = Objects.requireNonNull(price);
        this.notes = notes;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.couponId = couponId;
        this.status = Objects.requireNonNull(status);
        this.cancelledReason = cancelledReason;
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /**
     * A new appointment in PENDING. {@code clientId} is null for a walk-in; who may leave it empty
     * is the use case's decision (DEC-APPT-04). {@code nowAtBarbershop} is the barbershop's local time.
     */
    public static Appointment book(UUID id, UUID barbershopId, UUID clientId, UUID barberId, UUID serviceId,
                                   Slot slot, Money price, String notes, UUID createdBy,
                                   LocalDateTime nowAtBarbershop, Instant now) {
        return book(id, barbershopId, clientId, barberId, serviceId, slot, price, notes, createdBy, null,
                nowAtBarbershop, now);
    }

    /**
     * As {@link #book}, paid by the client's reward coupon when {@code couponId} is set: the price
     * snapshot is then 0 (DEC-APPT-09). A walk-in has no account, so it cannot carry a coupon.
     */
    public static Appointment book(UUID id, UUID barbershopId, UUID clientId, UUID barberId, UUID serviceId,
                                   Slot slot, Money price, String notes, UUID createdBy, UUID couponId,
                                   LocalDateTime nowAtBarbershop, Instant now) {
        if (couponId != null && clientId == null) {
            throw new InvalidValue("A walk-in has no reward coupon");
        }
        if (slot.startsAt().isBefore(nowAtBarbershop)) {
            throw new InvalidValue("An appointment cannot be booked in the past");
        }
        if (notes != null && notes.length() > NOTES_MAX) {
            throw new InvalidValue("notes has at most " + NOTES_MAX + " characters");
        }
        Money charged = couponId == null ? price : Money.ofCents(0);
        return new Appointment(id, barbershopId, clientId, barberId, serviceId, slot, charged, notes, createdBy,
                now, couponId, AppointmentStatus.PENDING, null, now);
    }

    /** Rebuilds a stored appointment; no rule is checked again. */
    public static Appointment restore(UUID id, UUID barbershopId, UUID clientId, UUID barberId, UUID serviceId,
                                      Slot slot, Money price, String notes, UUID createdBy, Instant createdAt,
                                      AppointmentStatus status, String cancelledReason, Instant updatedAt) {
        return restore(id, barbershopId, clientId, barberId, serviceId, slot, price, notes, createdBy, createdAt, null,
                status, cancelledReason, updatedAt);
    }

    /** As {@link #restore}, with the reward coupon that paid it, if any. */
    public static Appointment restore(UUID id, UUID barbershopId, UUID clientId, UUID barberId, UUID serviceId,
                                      Slot slot, Money price, String notes, UUID createdBy, Instant createdAt,
                                      UUID couponId, AppointmentStatus status, String cancelledReason,
                                      Instant updatedAt) {
        return new Appointment(id, barbershopId, clientId, barberId, serviceId, slot, price, notes, createdBy,
                createdAt, couponId, status, cancelledReason, updatedAt);
    }

    public void confirm(Instant now) {
        move(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, "be confirmed", now);
    }

    public void start(Instant now) {
        move(AppointmentStatus.CONFIRMED, AppointmentStatus.IN_PROGRESS, "start", now);
    }

    public void complete(Instant now) {
        move(AppointmentStatus.IN_PROGRESS, AppointmentStatus.COMPLETED, "be completed", now);
    }

    public void markNoShow(Instant now) {
        move(AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW, "be marked as a no-show", now);
    }

    /**
     * PENDING or CONFIRMED only. A client must cancel before start − policyHours; staff is exempt
     * (INV-APPT-003). {@code nowAtBarbershop} is the barbershop's local time.
     */
    public void cancel(String reason, boolean byClient, int policyHours, LocalDateTime nowAtBarbershop, Instant now) {
        if (status != AppointmentStatus.PENDING && status != AppointmentStatus.CONFIRMED) {
            throw new InvalidStatusTransition(status, "be cancelled");
        }
        if (reason != null && reason.length() > REASON_MAX) {
            throw new InvalidValue("reason has at most " + REASON_MAX + " characters");
        }
        if (byClient && nowAtBarbershop.isAfter(slot.startsAt().minusHours(policyHours))) {
            throw new BusinessRuleViolation("The appointment is inside the cancellation window of "
                    + policyHours + " hours");
        }
        status = AppointmentStatus.CANCELLED;
        cancelledReason = reason;
        updatedAt = now;
    }

    public boolean takesTime() {
        return status.takesTime();
    }

    private void move(AppointmentStatus from, AppointmentStatus to, String transition, Instant now) {
        if (status != from) {
            throw new InvalidStatusTransition(status, transition);
        }
        status = to;
        updatedAt = now;
    }

    public UUID id() { return id; }
    public UUID barbershopId() { return barbershopId; }
    public UUID clientId() { return clientId; }
    public UUID barberId() { return barberId; }
    public UUID serviceId() { return serviceId; }
    public Slot slot() { return slot; }
    public Money price() { return price; }
    public String notes() { return notes; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    /** The loyalty reward coupon that paid it, or null (DEC-APPT-09). */
    public UUID couponId() { return couponId; }
    public AppointmentStatus status() { return status; }
    public String cancelledReason() { return cancelledReason; }
    public Instant updatedAt() { return updatedAt; }
}
