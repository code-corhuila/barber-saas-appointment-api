package co.edu.corhuila.barbersaas.appointment.application.port.out;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import java.util.Optional;
import java.util.UUID;

/**
 * The client's loyalty reward coupon, asked through loyalty-api and never its database (DEC-APPT-09,
 * FR-010). loyalty-api applies the caller's tenant itself; a client only sees their own coupons.
 */
public interface RewardCoupons {

    /** The id of the client's ACTIVE coupon in the caller's barbershop, or empty when there is none. */
    Optional<UUID> activeCoupon(Caller caller, UUID clientId);
}
