package co.edu.corhuila.barbersaas.appointment.application.port.out;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A row of appointment.outbox_event, written with the change that caused it and published later by
 * a separate process (norm 5.3.11). The adapter adds the correlation id of the request.
 * Names from 02-domain/domain-events.md; AppointmentCompleted is what loyalty and finance consume.
 */
public record OutboxEvent(UUID id, UUID aggregateId, String type, Map<String, Object> payload, Instant occurredAt) {

    public static final String AGGREGATE_TYPE = "appointment";

    /** A copy that keeps the order and allows null values: a walk-in has no clientId. */
    public OutboxEvent {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
