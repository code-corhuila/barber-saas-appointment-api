package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ReviewUseCases.Listing;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** The response schemas of appointment-service.yaml: times always HH:mm, never the entity itself (norm 5.3.4). */
final class Views {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private Views() {
    }

    static String time(LocalTime t) {
        return t.format(HH_MM);
    }

    record Meta(int page, int limit, long total, long totalPages) { }

    record PageView<T>(List<T> data, Meta meta) {
        static <D, T> PageView<T> of(Page<D> page, Function<D, T> view) {
            return new PageView<>(page.items().stream().map(view).toList(),
                    new Meta(page.page(), page.limit(), page.total(), page.totalPages()));
        }
    }

    /**
     * Appointment: every field maps to a column of appointment.appointment. barbershopId (DEC-APPT-06)
     * and couponId (DEC-APPT-09) are output only: no request accepts them.
     */
    record AppointmentView(UUID id, UUID barbershopId, UUID clientId, UUID barberId, UUID serviceId, LocalDate date,
                           String startTime, String endTime, String status, long priceAtBookingCents, String notes,
                           String cancelledReason, Instant createdAt, Instant updatedAt, UUID createdBy,
                           UUID couponId) {

        static AppointmentView of(Appointment a) {
            return new AppointmentView(a.id(), a.barbershopId(), a.clientId(), a.barberId(), a.serviceId(), a.slot().date(),
                    time(a.slot().start()), time(a.slot().end()), a.status().name(), a.price().cents(), a.notes(),
                    a.cancelledReason(), a.createdAt(), a.updatedAt(), a.createdBy(), a.couponId());
        }
    }

    /** Review: table review without client_id, which is not public (DEC-APPT-11). */
    record ReviewView(UUID id, UUID appointmentId, UUID barbershopId, UUID barberId, String clientDisplayName,
                      int rating, String comment, Instant createdAt) {

        static ReviewView of(Review r) {
            return new ReviewView(r.id(), r.appointmentId(), r.barbershopId(), r.barberId(), r.clientDisplayName(),
                    r.rating(), r.comment(), r.createdAt());
        }
    }

    record Summary(Double averageRating, long reviewCount) { }

    /** ReviewPage: the page of _shared.yaml plus the summary of the same filter. */
    record ReviewPageView(List<ReviewView> data, Meta meta, Summary summary) {
        static ReviewPageView of(Listing listing) {
            PageView<ReviewView> page = PageView.of(listing.page(), ReviewView::of);
            return new ReviewPageView(page.data(), page.meta(),
                    new Summary(listing.summary().averageRating(), listing.summary().reviewCount()));
        }
    }
}
