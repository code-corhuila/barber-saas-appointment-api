package co.edu.corhuila.barbersaas.appointment.application.port.out;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import java.util.UUID;

/**
 * The review table. Reads are scoped by the barbershop the public query names (DEC-APPT-11): reviews
 * are discovery data, like the catalog, and carry no contact data.
 */
public interface ReviewRepository {

    /** The appointment already has a review: uq_review_appointment, even when two requests race. */
    class AlreadyReviewed extends RuntimeException {
        public AlreadyReviewed() {
            super("The appointment already has a review");
        }
    }

    /** Computed on read; {@code averageRating} is null when there is no review. */
    record Summary(Double averageRating, long reviewCount) { }

    boolean exists(UUID appointmentId);

    /** @throws AlreadyReviewed when the appointment already has one */
    void saveNew(Review review);

    /** Newest first; {@code barberId} null means every barber of the barbershop. */
    Page<Review> page(UUID barbershopId, UUID barberId, Page.Request page);

    Summary summary(UUID barbershopId, UUID barberId);
}
