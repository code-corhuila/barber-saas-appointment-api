package co.edu.corhuila.barbersaas.appointment.domain.model;

import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidValue;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The rating of a completed appointment by its client (DEC-APPT-11, table review). The barbershop,
 * the barber and the client's display name are copied when it is written and never change.
 */
public record Review(UUID id, UUID appointmentId, UUID barbershopId, UUID barberId, UUID clientId,
                     String clientDisplayName, int rating, String comment, Instant createdAt) {

    public static final int MAX_COMMENT = 500;
    public static final int MAX_DISPLAY_NAME = 60;
    static final String NOT_COMPLETED = "Solo se puede calificar una cita completada";

    public Review {
        Objects.requireNonNull(id);
        Objects.requireNonNull(appointmentId);
        Objects.requireNonNull(barbershopId);
        Objects.requireNonNull(barberId);
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(clientDisplayName);
        Objects.requireNonNull(createdAt);
    }

    /** The rules of {@link #of}, checked before anything is asked to another service. */
    public static void check(Appointment appointment, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new InvalidValue("rating must be between 1 and 5");
        }
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw new InvalidValue("comment must be at most " + MAX_COMMENT + " characters");
        }
        if (appointment.status() != AppointmentStatus.COMPLETED || appointment.clientId() == null) {
            throw new BusinessRuleViolation(NOT_COMPLETED);
        }
    }

    /** chk_review_rating, chk_review_comment; only a COMPLETED appointment with a client is reviewed. */
    public static Review of(UUID id, Appointment appointment, String clientDisplayName, int rating, String comment,
                            Instant now) {
        check(appointment, rating, comment);
        return new Review(id, appointment.id(), appointment.barbershopId(), appointment.barberId(),
                appointment.clientId(), clientDisplayName, rating, comment, now);
    }

    /**
     * "María G.": the first name and the initial of the surname, never the e-mail (ADR-014). With
     * four or more words the surname is the third ("Juan Pablo Borrero Morales" → "Juan B."), with two
     * or three it is the second. A name with no words reads "Cliente".
     */
    public static String displayName(String fullName) {
        String[] words = fullName == null ? new String[0] : fullName.trim().split("\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "Cliente";
        }
        String first = words[0].length() > MAX_DISPLAY_NAME - 3 ? words[0].substring(0, MAX_DISPLAY_NAME - 3) : words[0];
        if (words.length == 1) {
            return first;
        }
        String surname = words[words.length >= 4 ? 2 : 1];
        return first + " " + surname.substring(0, surname.offsetByCodePoints(0, 1)).toUpperCase() + ".";
    }
}
