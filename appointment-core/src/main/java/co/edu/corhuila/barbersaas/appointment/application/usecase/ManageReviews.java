package co.edu.corhuila.barbersaas.appointment.application.usecase;

import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ReviewUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Clock;
import co.edu.corhuila.barbersaas.appointment.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository.AlreadyReviewed;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository.Summary;
import co.edu.corhuila.barbersaas.appointment.application.port.out.UserDirectory;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import java.util.UUID;

/** DEC-APPT-11: only the client of a completed appointment reviews it, once. */
public class ManageReviews implements ReviewUseCases {

    /** The text the app shows when the appointment was already reviewed. */
    static final String ALREADY_REVIEWED = "Esta cita ya fue calificada";

    private final AppointmentRepository appointments;
    private final ReviewRepository reviews;
    private final UserDirectory users;
    private final Clock clock;
    private final IdGenerator ids;

    public ManageReviews(AppointmentRepository appointments, ReviewRepository reviews, UserDirectory users,
                         Clock clock, IdGenerator ids) {
        this.appointments = appointments;
        this.reviews = reviews;
        this.users = users;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public Review review(Caller caller, UUID appointmentId, int rating, String comment) {
        caller.require(Role.CLIENT);
        UUID clientId = caller.userId();
        // The token's sub scopes the read; a token with a barbershop also limits it to that barbershop.
        Appointment appointment = appointments.findOfClient(clientId, appointmentId)
                .filter(a -> caller.barbershopId() == null || caller.barbershopId().equals(a.barbershopId()))
                .orElseThrow(() -> new NotFound("Appointment"));
        Review.check(appointment, rating, comment);
        if (reviews.exists(appointmentId)) {
            throw new BusinessRuleViolation(ALREADY_REVIEWED);
        }
        Review review = Review.of(ids.next(), appointment, Review.displayName(users.fullName(clientId)), rating,
                comment, clock.now());
        try {
            reviews.saveNew(review);
        } catch (AlreadyReviewed race) {
            throw new BusinessRuleViolation(ALREADY_REVIEWED);
        }
        return review;
    }

    @Override
    public Listing list(UUID barbershopId, UUID barberId, Page.Request page) {
        Summary s = reviews.summary(barbershopId, barberId);
        Double average = s.averageRating() == null ? null : Math.round(s.averageRating() * 10) / 10.0;
        return new Listing(reviews.page(barbershopId, barberId, page), new Summary(average, s.reviewCount()));
    }
}
