package co.edu.corhuila.barbersaas.appointment.application.port.in;

import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository.Summary;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Reviews of completed appointments (DEC-APPT-11, tag Reviews of appointment-service.yaml). */
public interface ReviewUseCases {

    /** A page of a barbershop's reviews and the average and count of the same filter. */
    record Listing(Page<Review> page, Summary summary) { }

    /** Role CLIENT, own COMPLETED appointment only, once. */
    Review review(Caller caller, UUID appointmentId, int rating, String comment);

    /**
     * The ones of {@code appointments} that already have a review, so the app offers "Calificar" only
     * once. The appointments were read for this caller, so they are already in its tenant.
     */
    Set<UUID> reviewedAmong(Collection<Appointment> appointments);

    /** Public: no caller, the barbershop comes from the query. */
    Listing list(UUID barbershopId, UUID barberId, Page.Request page);
}
