package co.edu.corhuila.barbersaas.appointment.application.port.out;

import java.time.ZoneId;
import java.util.UUID;

/**
 * The time zone of a barbershop, so a daily job knows its local "today" (DEC-APPT-07), and its name,
 * which the reminder carries (02-domain/domain-events.md).
 */
public interface BarbershopZones {

    ZoneId zoneOf(UUID barbershopId);

    /** Null when it cannot be read: the reminder still goes, without the name. */
    default String nameOf(UUID barbershopId) {
        return null;
    }
}
