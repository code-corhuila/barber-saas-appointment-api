package co.edu.corhuila.barbersaas.appointment.application.usecase;

import co.edu.corhuila.barbersaas.appointment.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The events of 02-domain/domain-events.md, built from the appointment as it is after the change.
 * Every payload carries enough for a consumer to act without calling back: loyalty grants the sticker
 * and finance registers the income from AppointmentCompleted alone.
 */
final class Events {

    static final String CREATED = "AppointmentCreated";
    static final String CONFIRMED = "AppointmentConfirmed";
    static final String CANCELLED = "AppointmentCancelled";
    static final String NO_SHOW = "AppointmentMarkedNoShow";
    static final String COMPLETED = "AppointmentCompleted";

    private Events() {
    }

    static OutboxEvent of(String type, Appointment a, IdGenerator ids, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appointmentId", a.id().toString());
        payload.put("barbershopId", a.barbershopId().toString());
        payload.put("clientId", a.clientId() == null ? null : a.clientId().toString());
        payload.put("barberId", a.barberId().toString());
        payload.put("serviceId", a.serviceId().toString());
        payload.put("date", a.slot().date().toString());
        payload.put("startTime", a.slot().start().toString());
        payload.put("endTime", a.slot().end().toString());
        payload.put("status", a.status().name());
        payload.put("priceAtBookingCents", a.price().cents());
        if (a.cancelledReason() != null) {
            payload.put("cancelledReason", a.cancelledReason());
        }
        return new OutboxEvent(ids.next(), a.id(), type, payload, now);
    }
}
