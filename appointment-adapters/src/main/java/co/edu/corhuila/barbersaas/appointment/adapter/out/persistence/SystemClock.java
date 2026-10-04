package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.out.Clock;
import java.time.Instant;

/** The machine's clock. The barbershop's local time is derived from it with the barbershop's time zone. */
public class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
