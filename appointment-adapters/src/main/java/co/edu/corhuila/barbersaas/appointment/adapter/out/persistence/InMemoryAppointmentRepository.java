package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Used when DATABASE_URL is empty: the service starts and its HTTP contract can be tested without a
 * database. Writes are synchronized so the no-double-booking rule holds as it does in PostgreSQL.
 */
public class InMemoryAppointmentRepository implements AppointmentRepository {

    private final Map<UUID, Appointment> rows = new ConcurrentHashMap<>();
    private final Map<String, Idempotency.Stored> keys = new ConcurrentHashMap<>();
    private final List<OutboxEvent> outbox = new CopyOnWriteArrayList<>();

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
        if (keys.putIfAbsent(key.operation() + " " + key.key(),
                new Idempotency.Stored(appointment.id(), key.requestHash())) != null) {
            throw new KeyTaken();
        }
        rows.put(appointment.id(), appointment);
        outbox.addAll(events);
    }

    @Override
    public synchronized void update(Appointment appointment, List<OutboxEvent> events) {
        rows.put(appointment.id(), appointment);
        outbox.addAll(events);
    }

    /** What would be published; only for tests and local runs. */
    public List<OutboxEvent> outbox() {
        return List.copyOf(outbox);
    }
}
