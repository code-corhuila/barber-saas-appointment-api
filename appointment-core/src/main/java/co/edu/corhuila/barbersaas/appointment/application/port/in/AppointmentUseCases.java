package co.edu.corhuila.barbersaas.appointment.application.port.in;

import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * What the service offers (appointment-service.yaml, tag Appointments). The tenant and the user
 * always come from the {@link Caller}, never from the request. SUPER_ADMIN may do nothing here.
 */
public interface AppointmentUseCases {

    /** {@code clientId} only from staff; null or absent is a walk-in (DEC-APPT-04). */
    record BookCommand(UUID barberId, UUID serviceId, UUID clientId, LocalDate date, LocalTime startTime,
                       String notes) { }

    /** Every field is optional. A CLIENT only ever sees their own appointments, whatever the filter. */
    record Filter(AppointmentStatus status, UUID barberId, LocalDate date) {
        public static Filter none() {
            return new Filter(null, null, null);
        }
    }

    /**
     * CLIENT, ADMIN_BARBERSHOP and BARBER. Creates the appointment in PENDING with the end and the
     * price computed from the service. The same Idempotency-Key returns the appointment already
     * created ({@code created} false); with a different request it is refused.
     */
    Created<Appointment> book(Caller caller, BookCommand command, String idempotencyKey);

    /**
     * Most recent first. A CLIENT whose token carries no barbershop gets their own appointments of
     * every barbershop, and the barber filter does not apply (DEC-APPT-06).
     */
    Page<Appointment> list(Caller caller, Filter filter, Page.Request page);

    /** A CLIENT only gets their own appointments: any other id is not found. */
    Appointment get(Caller caller, UUID id);

    /** ADMIN_BARBERSHOP and BARBER. */
    Appointment confirm(Caller caller, UUID id);

    /** ADMIN_BARBERSHOP and BARBER. */
    Appointment start(Caller caller, UUID id);

    /** ADMIN_BARBERSHOP and BARBER. Emits AppointmentCompleted in the same transaction. */
    Appointment complete(Caller caller, UUID id);

    /** ADMIN_BARBERSHOP and BARBER. */
    Appointment markNoShow(Caller caller, UUID id);

    /** A CLIENT cancels their own appointment outside the barbershop's window; staff is exempt. */
    Appointment cancel(Caller caller, UUID id, String reason);
}
