package co.edu.corhuila.barbersaas.appointment.application.port.out;

import java.util.UUID;

/**
 * What appointment may know of a user, asked through identity-auth's internal user read with this
 * service's own token (ADR-014): the name, never the e-mail or the phone.
 */
public interface UserDirectory {

    /** @throws DependencyUnavailable when identity-auth does not answer */
    String fullName(UUID userId);
}
