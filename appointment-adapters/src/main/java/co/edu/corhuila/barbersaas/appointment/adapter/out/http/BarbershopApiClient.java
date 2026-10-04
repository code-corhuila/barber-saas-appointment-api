package co.edu.corhuila.barbersaas.appointment.adapter.out.http;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyFailure;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/** BarbershopCatalog over barbershop-service.yaml; barbershop-api applies the token's tenant itself. */
public class BarbershopApiClient implements BarbershopCatalog {

    private final JsonApi api;

    public BarbershopApiClient(String baseUrl) {
        this.api = new JsonApi("barbershop-api", baseUrl);
    }

    /** getServiceById. An inactive service cannot be booked: it is treated as absent. */
    @Override
    public Optional<CatalogService> service(Caller caller, UUID serviceId) {
        return api.get(caller, "/api/v1/services/" + serviceId)
                .filter(s -> s.path("isActive").asBoolean(false))
                .map(s -> new CatalogService(UUID.fromString(s.path("id").asText()), s.path("durationMinutes").asInt(),
                        s.path("priceCents").asLong()));
    }

    /** getMyBarbershop: the time zone and the cancellation window of the token's barbershop. */
    @Override
    public Policy policy(Caller caller) {
        JsonNode shop = api.get(caller, "/api/v1/barbershops/me")
                .orElseThrow(() -> new DependencyFailure("barbershop-api", "the token's barbershop does not exist"));
        if (!shop.path("timezone").isTextual() || !shop.path("cancellationPolicyHours").isInt()) {
            throw new DependencyFailure("barbershop-api", "the barbershop has no time zone or cancellation policy");
        }
        return new Policy(ZoneId.of(shop.get("timezone").asText()), shop.get("cancellationPolicyHours").asInt());
    }
}
