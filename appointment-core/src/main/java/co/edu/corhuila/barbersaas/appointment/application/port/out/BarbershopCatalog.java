package co.edu.corhuila.barbersaas.appointment.application.port.out;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * What appointment needs from the barbershop domain, asked through barbershop-api and never its
 * database (golden rule 8, Annex J J.3.3). barbershop-api applies the caller's tenant itself.
 */
public interface BarbershopCatalog {

    /** The service's current price and duration; the price becomes the snapshot (INV-APPT-002). */
    record CatalogService(UUID id, int durationMinutes, long priceCents) { }

    /** The barbershop's local time zone and the client's cancellation window (INV-APPT-003). */
    record Policy(ZoneId timezone, int cancellationPolicyHours) { }

    /** Empty when the service does not exist, is inactive or belongs to another barbershop (404). */
    Optional<CatalogService> service(Caller caller, UUID serviceId);

    /** The policy of the caller's barbershop. */
    Policy policy(Caller caller);
}
