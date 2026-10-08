package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DailyJobsStore;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.MDC;

/**
 * Used when DATABASE_URL is empty: the service starts and its HTTP contract can be tested without a
 * database. Writes are synchronized so the no-double-booking rule holds as it does in PostgreSQL.
 */
public class InMemoryAppointmentRepository implements AppointmentRepository, OutboxStore, DailyJobsStore {

    private final Map<UUID, Appointment> rows = new ConcurrentHashMap<>();
    private final Map<String, Idempotency.Stored> keys = new ConcurrentHashMap<>();
    private final List<OutboxEvent> outbox = new CopyOnWriteArrayList<>();
    /** Per event: its correlation id, and whether it was published or set aside as failed. */
    private final Map<UUID, Relay> relay = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> reminders = new ConcurrentHashMap<>();

    private record Relay(String correlationId, Instant publishedAt, Instant failedAt, String lastError) { }

    @Override
    public Optional<Appointment> findById(UUID tenant, UUID id) {
        return Optional.ofNullable(rows.get(id)).filter(a -> a.barbershopId().equals(tenant));
    }

    @Override
    public Page<Appointment> page(UUID tenant, Query q, Page.Request page) {
        return Page.of(rows.values().stream()
                .filter(a -> a.barbershopId().equals(tenant))
                .filter(a -> q.clientId() == null || q.clientId().equals(a.clientId()))
                .filter(a -> q.status() == null || q.status() == a.status())
                .filter(a -> q.barberId() == null || q.barberId().equals(a.barberId()))
                .filter(a -> q.date() == null || q.date().equals(a.slot().date()))
                .sorted(Comparator.comparing((Appointment a) -> a.slot().startsAt()).reversed()
                        .thenComparing(Appointment::id))
                .toList(), page);
    }

    @Override
    public Page<Appointment> pageOfClient(UUID clientId, AppointmentStatus status, LocalDate date, Page.Request page) {
        return Page.of(rows.values().stream()
                .filter(a -> clientId.equals(a.clientId()))
                .filter(a -> status == null || status == a.status())
                .filter(a -> date == null || date.equals(a.slot().date()))
                .sorted(Comparator.comparing((Appointment a) -> a.slot().startsAt()).reversed()
                        .thenComparing(Appointment::id))
                .toList(), page);
    }

    @Override
    public List<Slot> busy(UUID tenant, UUID barberId, LocalDate date) {
        return rows.values().stream()
                .filter(a -> a.barbershopId().equals(tenant) && a.barberId().equals(barberId))
                .filter(a -> a.slot().date().equals(date) && a.status().isOpen())
                .map(Appointment::slot).sorted(Comparator.comparing(Slot::start)).toList();
    }

    @Override
    public boolean overlaps(UUID barberId, Slot slot) {
        return rows.values().stream()
                .anyMatch(a -> a.barberId().equals(barberId) && a.takesTime() && a.slot().overlaps(slot));
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return Optional.ofNullable(keys.get(operation + " " + key));
    }

    @Override
    public synchronized void saveNew(Appointment appointment, Idempotency.Key key, List<OutboxEvent> events) {
        if (overlaps(appointment.barberId(), appointment.slot())) {
            throw new SlotTaken();
        }
        if (appointment.couponId() != null
                && rows.values().stream().anyMatch(a -> appointment.couponId().equals(a.couponId()))) {
            throw new CouponTaken();   // as uq_appointment_coupon
        }
        if (keys.putIfAbsent(key.operation() + " " + key.key(),
                new Idempotency.Stored(appointment.id(), key.requestHash())) != null) {
            throw new KeyTaken();
        }
        rows.put(appointment.id(), appointment);
        append(events);
    }

    @Override
    public synchronized void update(Appointment appointment, List<OutboxEvent> events) {
        rows.put(appointment.id(), appointment);
        append(events);
    }

    private void append(List<OutboxEvent> events) {
        String correlationId = Optional.ofNullable(MDC.get("correlationId")).orElse("none");
        events.forEach(e -> relay.put(e.id(), new Relay(correlationId, null, null, null)));
        outbox.addAll(events);
    }

    @Override
    public List<Appointment> confirmedBetween(LocalDate from, LocalDate to, boolean withoutReminder) {
        return rows.values().stream().filter(a -> a.status() == AppointmentStatus.CONFIRMED)
                .filter(a -> !a.slot().date().isAfter(to) && (from == null || !a.slot().date().isBefore(from)))
                .filter(a -> !withoutReminder || !reminders.containsKey(a.id()))
                .sorted(Comparator.comparing((Appointment a) -> a.slot().startsAt()).thenComparing(Appointment::id))
                .toList();
    }

    @Override
    public synchronized boolean markReminderSent(UUID appointmentId, OutboxEvent event, Instant now) {
        Appointment a = rows.get(appointmentId);
        if (a == null || a.status() != AppointmentStatus.CONFIRMED || reminders.putIfAbsent(appointmentId, now) != null) {
            return false;
        }
        append(List.of(event));
        return true;
    }

    @Override
    public List<Stored> pending(int limit) {
        return outbox.stream().filter(e -> relay.get(e.id()).publishedAt() == null && relay.get(e.id()).failedAt() == null)
                .sorted(Comparator.comparing(OutboxEvent::occurredAt))
                .limit(limit).map(e -> new Stored(e, relay.get(e.id()).correlationId())).toList();
    }

    @Override
    public synchronized boolean markPublished(UUID id, Instant now) {
        Relay r = relay.get(id);
        if (r != null && r.publishedAt() == null) {
            relay.put(id, new Relay(r.correlationId(), now, r.failedAt(), r.lastError()));
        }
        return r != null;
    }

    @Override
    public synchronized boolean markFailed(UUID id, String reason, Instant now) {
        Relay r = relay.get(id);
        if (r != null) {
            relay.put(id, new Relay(r.correlationId(), r.publishedAt(), r.failedAt() == null ? now : r.failedAt(), reason));
        }
        return r != null;
    }

    /** What would be published; only for tests and local runs. */
    public List<OutboxEvent> outbox() {
        return List.copyOf(outbox);
    }
}
