package co.edu.corhuila.barbersaas.appointment.application.port.out;

import java.util.UUID;

/** Rows of appointment.idempotency_key: written in the SAME transaction as the resource (norm 5.3.8). */
public final class Idempotency {

    private Idempotency() {
    }

    public record Key(String key, String operation, String requestHash) { }

    public record Stored(UUID resourceId, String requestHash) { }
}
