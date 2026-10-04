package co.edu.corhuila.barbersaas.appointment.domain.model;

import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidValue;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/** The time an appointment takes on one date, in the barbershop's local time: [start, end). */
public record Slot(LocalDate date, LocalTime start, LocalTime end) {

    public Slot {
        Objects.requireNonNull(date);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        if (!end.isAfter(start)) {
            throw new InvalidValue("The end of a slot must be after its start");
        }
    }

    /** The end is computed from the service's duration, never sent by the client (bookAppointment). */
    public static Slot of(LocalDate date, LocalTime start, int durationMinutes) {
        if (durationMinutes <= 0) {
            throw new InvalidValue("A service lasts at least one minute");
        }
        LocalTime end = start.plusMinutes(durationMinutes);
        if (!end.isAfter(start)) {
            throw new BusinessRuleViolation("The service does not fit before the end of the day");
        }
        return new Slot(date, start, end);
    }

    /** Adjacent slots do not overlap: 10:00–10:30 and 10:30–11:00 can both be booked. */
    public boolean overlaps(Slot other) {
        return date.equals(other.date) && start.isBefore(other.end) && other.start.isBefore(end);
    }

    public LocalDateTime startsAt() {
        return date.atTime(start);
    }
}
