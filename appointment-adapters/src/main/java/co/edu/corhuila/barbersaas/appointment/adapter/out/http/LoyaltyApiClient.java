package co.edu.corhuila.barbersaas.appointment.adapter.out.http;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyFailure;
import co.edu.corhuila.barbersaas.appointment.application.port.out.RewardCoupons;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;

/**
 * RewardCoupons over loyalty-service.yaml (DEC-APPT-09): listCoupons with the caller's token, so
 * loyalty-api applies the tenant and lets a client see only their own coupons.
 */
public class LoyaltyApiClient implements RewardCoupons {

    private final JsonApi api;

    public LoyaltyApiClient(String baseUrl) {
        this.api = new JsonApi("loyalty-api", baseUrl);
    }

    /** listCoupons?clientId&status=ACTIVE: the first one, if any. A missing route is a failure, not "none". */
    @Override
    public Optional<UUID> activeCoupon(Caller caller, UUID clientId) {
        JsonNode page = api.get(caller, "/api/v1/loyalty/coupons?clientId=" + clientId + "&status=ACTIVE&limit=1")
                .orElseThrow(() -> new DependencyFailure("loyalty-api", "answered 404 to the coupon list"));
        JsonNode data = page.path("data");
        if (!data.isArray()) {
            throw new DependencyFailure("loyalty-api", "the coupon list has no data");
        }
        if (data.isEmpty()) {
            return Optional.empty();
        }
        JsonNode coupon = data.get(0);
        if (!"ACTIVE".equals(coupon.path("status").asText()) || !coupon.path("id").isTextual()) {
            throw new DependencyFailure("loyalty-api", "the coupon list answered a coupon that is not ACTIVE");
        }
        return Optional.of(UUID.fromString(coupon.get("id").asText()));
    }
}
