package co.edu.corhuila.barbersaas.appointment.adapter.out.http;

import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopZones;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyFailure;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

/**
 * BarbershopZones over the public detail of barbershop-service.yaml (getBarbershopById, anonymous,
 * DEC-SHOP-02): the daily jobs run with the worker's token, which barbershop-api does not take, and
 * need only the time zone. A barbershop that is no longer visible (404, suspended or cancelled) is
 * judged in the platform's zone, so its appointments still leave the agenda.
 */
public class BarbershopZonesClient implements BarbershopZones {

    /** The zone of the prototype and of every barbershop created so far (Colombia). */
    static final ZoneId PLATFORM_ZONE = ZoneId.of("America/Bogota");

    private final JsonApi api;

    public BarbershopZonesClient(String baseUrl) {
        this.api = new JsonApi("barbershop-api", baseUrl);
    }

    @Override
    public ZoneId zoneOf(UUID barbershopId) {
        return api.getPublic("/api/v1/barbershops/" + barbershopId)
                .map(shop -> {
                    try {
                        return ZoneId.of(shop.path("timezone").asText());
                    } catch (DateTimeException e) {
                        throw new DependencyFailure("barbershop-api", "the barbershop has no valid time zone");
                    }
                })
                .orElse(PLATFORM_ZONE);
    }
}
