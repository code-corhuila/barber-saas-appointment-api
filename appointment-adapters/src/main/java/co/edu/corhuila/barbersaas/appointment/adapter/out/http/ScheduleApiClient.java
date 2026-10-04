package co.edu.corhuila.barbersaas.appointment.adapter.out.http;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarberAvailability;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BarberAvailability over schedule-service.yaml (getAvailability). schedule-api answers 404 when the
 * barber or the service is not in the token's barbershop, which here becomes "empty".
 */
public class ScheduleApiClient implements BarberAvailability {

    private final JsonApi api;

    public ScheduleApiClient(String baseUrl) {
        this.api = new JsonApi("schedule-api", baseUrl);
    }

    @Override
    public Optional<List<LocalTime>> freeStarts(Caller caller, UUID barberId, UUID serviceId, LocalDate date) {
        return api.get(caller, "/api/v1/availability?barberId=" + barberId + "&serviceId=" + serviceId
                        + "&date=" + date)
                .map(body -> {
                    List<LocalTime> starts = new ArrayList<>();
                    body.path("slots").forEach(slot -> starts.add(LocalTime.parse(slot.path("startTime").asText())));
                    return starts;
                });
    }
}
