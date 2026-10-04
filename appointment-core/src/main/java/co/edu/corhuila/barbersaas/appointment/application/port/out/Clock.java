package co.edu.corhuila.barbersaas.appointment.application.port.out;

import java.time.Instant;

/** The current instant; the tests fix it, so "in the past" and the cancellation window are repeatable. */
public interface Clock {

    Instant now();
}
