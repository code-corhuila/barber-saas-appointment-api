package co.edu.corhuila.barbersaas.appointment.adapter.out.http;

import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyFailure;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyUnavailable;
import co.edu.corhuila.barbersaas.appointment.application.port.out.UserDirectory;
import java.util.UUID;

/**
 * UserDirectory over getInternalUser of auth-service.yaml, with this service's own SERVICE_TOKEN
 * (sub barber-saas-appointment-api, ADR-014): only the name is read, never contact data. Any failure,
 * including a user it does not know, is a 503: the review is not stored and the client can retry.
 */
public class IdentityAuthClient implements UserDirectory {

    private static final String NAME = "identity-auth-api";

    private final JsonApi api;
    private final String serviceToken;

    public IdentityAuthClient(String baseUrl, String serviceToken) {
        this.api = new JsonApi(NAME, baseUrl);
        this.serviceToken = serviceToken;
    }

    @Override
    public String fullName(UUID userId) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new DependencyUnavailable(NAME, "SERVICE_TOKEN is not set");
        }
        try {
            return api.getAsService("/internal/v1/users/" + userId, serviceToken)
                    .map(user -> user.path("fullName").asText(""))
                    .orElseThrow(() -> new DependencyUnavailable(NAME, "does not know the user " + userId));
        } catch (DependencyUnavailable e) {
            throw e;
        } catch (DependencyFailure e) {
            throw new DependencyUnavailable(NAME, e.getMessage());
        }
    }
}
