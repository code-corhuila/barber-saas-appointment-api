package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
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

    /** Appointment: every field maps to a column of appointment.appointment; barbershopId is never exposed. */
    record AppointmentView(UUID id, UUID clientId, UUID barberId, UUID serviceId, LocalDate date, String startTime,
                           String endTime, String status, long priceAtBookingCents, String notes,
                           String cancelledReason, Instant createdAt, Instant updatedAt, UUID createdBy) {

        static AppointmentView of(Appointment a) {
            return new AppointmentView(a.id(), a.clientId(), a.barberId(), a.serviceId(), a.slot().date(),
                    time(a.slot().start()), time(a.slot().end()), a.status().name(), a.price().cents(), a.notes(),
                    a.cancelledReason(), a.createdAt(), a.updatedAt(), a.createdBy());
        }
    }
}
