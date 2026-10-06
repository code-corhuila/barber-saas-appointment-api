package co.edu.corhuila.barbersaas.appointment.application.port.out;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The appointment schema. Every read is scoped by the tenant: another barbershop's id is empty (404).
 * The one exception is {@link #pageOfClient}, scoped by the client instead (DEC-APPT-06).
 */
public interface AppointmentRepository {

    /** {@code clientId} set forces a client's own appointments; the other fields are optional filters. */
    record Query(UUID clientId, AppointmentStatus status, UUID barberId, LocalDate date) { }

    /** Another active appointment of the barber took the slot: ex_appointment_no_double_booking. */
    class SlotTaken extends RuntimeException {
        public SlotTaken() {
            super("The barber already has an appointment at that time");
        }
    }

    /** Another request stored the same Idempotency-Key first. */
    class KeyTaken extends RuntimeException {
        public KeyTaken() {
            super("The Idempotency-Key was stored by a concurrent request");
        }
    }

    Optional<Appointment> findById(UUID tenant, UUID id);

    /** Most recent first: date, then start time, descending. */
    Page<Appointment> page(UUID tenant, Query query, Page.Request page);

    /**
     * The single read not scoped by the tenant (DEC-APPT-06): a client's own appointments of every
     * barbershop, filtered only by {@code clientId}, which the caller takes from the token's sub.
     * Most recent first, like {@link #page}.
     */
    Page<Appointment> pageOfClient(UUID clientId, AppointmentStatus status, LocalDate date, Page.Request page);

    /** The slots of the barber's PENDING, CONFIRMED and IN_PROGRESS appointments that date, by start. */
    List<Slot> busy(UUID tenant, UUID barberId, LocalDate date);

    /** True when an active appointment of the barber overlaps the slot (a clean 422 before the insert). */
    boolean overlaps(UUID barberId, Slot slot);

    Optional<Idempotency.Stored> findKey(String key, String operation);

    /** The appointment, its key and its events in ONE transaction (norm 5.3.8, 5.3.11). */
    void saveNew(Appointment appointment, Idempotency.Key key, List<OutboxEvent> events);

    /** The new status and its events in ONE transaction (norm 5.3.11). */
    void update(Appointment appointment, List<OutboxEvent> events);
}
