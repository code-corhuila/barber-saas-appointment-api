package co.edu.corhuila.barbersaas.appointment.application.port.out;

import java.time.ZoneId;
import java.util.UUID;

/** The time zone of a barbershop, so a daily job knows its local "today" (DEC-APPT-07). */
public interface BarbershopZones {

    ZoneId zoneOf(UUID barbershopId);
}
