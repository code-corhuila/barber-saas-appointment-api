package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DailyJobsStore;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Money;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and writes the appointment schema (06-data/models.md §5 and §10) as appointment_app. Every
 * read filters by the barbershop. ex_appointment_no_double_booking is the final guarantee against
 * double booking: its violation becomes SlotTaken, which the use case answers with 422.
 */
public class JdbcAppointmentRepository implements AppointmentRepository, OutboxStore, DailyJobsStore {

    private static final String COLUMNS = "id, barbershop_id, client_id, barber_id, service_id, appointment_date, "
            + "start_time, end_time, status, price_at_booking_cents, notes, cancelled_reason, created_by, "
            + "created_at, updated_at";
    private static final String NO_DOUBLE_BOOKING = "ex_appointment_no_double_booking";
    private static final String KEY_PRIMARY_KEY = "pk_idempotency_key";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcAppointmentRepository(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public Optional<Appointment> findById(UUID tenant, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM appointment.appointment WHERE barbershop_id = ? AND id = ?",
                (rs, n) -> map(rs), tenant, id).stream().findFirst();
    }

    @Override
    public Page<Appointment> page(UUID tenant, Query q, Page.Request page) {
        StringBuilder from = new StringBuilder("FROM appointment.appointment WHERE barbershop_id = ?");
        List<Object> args = new ArrayList<>(List.of(tenant));
        if (q.clientId() != null) {
            from.append(" AND client_id = ?");
            args.add(q.clientId());
        }
        if (q.status() != null) {
            from.append(" AND status = ?");
            args.add(q.status().name());
        }
        if (q.barberId() != null) {
            from.append(" AND barber_id = ?");
            args.add(q.barberId());
        }
        if (q.date() != null) {
            from.append(" AND appointment_date = ?");
            args.add(q.date());
        }
        return JdbcPages.page(jdbc, COLUMNS, new JdbcPages.Query(from.toString(), args,
                "ORDER BY appointment_date DESC, start_time DESC, id"), (rs, n) -> map(rs), page);
    }

    @Override
    public Page<Appointment> pageOfClient(UUID clientId, AppointmentStatus status, LocalDate date, Page.Request page) {
        StringBuilder from = new StringBuilder("FROM appointment.appointment WHERE client_id = ?");
        List<Object> args = new ArrayList<>(List.of(clientId));
        if (status != null) {
            from.append(" AND status = ?");
            args.add(status.name());
        }
        if (date != null) {
            from.append(" AND appointment_date = ?");
            args.add(date);
        }
        return JdbcPages.page(jdbc, COLUMNS, new JdbcPages.Query(from.toString(), args,
                "ORDER BY appointment_date DESC, start_time DESC, id"), (rs, n) -> map(rs), page);
    }

    @Override
    public List<Slot> busy(UUID tenant, UUID barberId, LocalDate date) {
        return jdbc.query("SELECT appointment_date, start_time, end_time FROM appointment.appointment "
                        + "WHERE barbershop_id = ? AND barber_id = ? AND appointment_date = ? "
                        + "AND status IN ('PENDING', 'CONFIRMED', 'IN_PROGRESS') ORDER BY start_time",
                (rs, n) -> new Slot(rs.getObject("appointment_date", LocalDate.class),
                        rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class)),
                tenant, barberId, date);
    }

    @Override
    public boolean overlaps(UUID barberId, Slot slot) {
        Boolean found = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM appointment.appointment "
                        + "WHERE barber_id = ? AND appointment_date = ? AND status NOT IN ('CANCELLED', 'NO_SHOW') "
                        + "AND start_time < ? AND end_time > ?)",
                Boolean.class, barberId, slot.date(), slot.end(), slot.start());
        return Boolean.TRUE.equals(found);
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return JdbcIdempotency.find(jdbc, key, operation);
    }

    @Override
    public void saveNew(Appointment a, Idempotency.Key key, List<OutboxEvent> events) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO appointment.appointment (" + COLUMNS + ") "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        a.id(), a.barbershopId(), a.clientId(), a.barberId(), a.serviceId(), a.slot().date(),
                        a.slot().start(), a.slot().end(), a.status().name(), a.price().cents(), a.notes(),
                        a.cancelledReason(), a.createdBy(), Timestamp.from(a.createdAt()),
                        Timestamp.from(a.updatedAt()));
                JdbcIdempotency.insert(jdbc, key, a.id());
                insert(events);
            });
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains(NO_DOUBLE_BOOKING)) {
                throw new SlotTaken();
            }
            if (message.contains(KEY_PRIMARY_KEY)) {
                throw new KeyTaken();
            }
            throw e;
        }
    }

    /** Only what a transition changes; the price, the slot and the people never change (INV-APPT-002). */
    @Override
    public void update(Appointment a, List<OutboxEvent> events) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE appointment.appointment SET status = ?, cancelled_reason = ?, updated_at = ? "
                            + "WHERE barbershop_id = ? AND id = ?",
                    a.status().name(), a.cancelledReason(), Timestamp.from(a.updatedAt()), a.barbershopId(), a.id());
            insert(events);
        });
    }

    /** Inside the caller's transaction. The correlation id ties the event to the request that caused it. */
    private void insert(List<OutboxEvent> events) {
        String correlationId = Optional.ofNullable(MDC.get("correlationId")).orElse("none");
        for (OutboxEvent e : events) {
            jdbc.update("INSERT INTO appointment.outbox_event (id, aggregate_type, aggregate_id, event_type, payload, "
                            + "correlation_id, occurred_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                    e.id(), OutboxEvent.AGGREGATE_TYPE, e.aggregateId(), e.type(), toJson(e), correlationId,
                    Timestamp.from(e.occurredAt()));
        }
    }

    @Override
    public List<Appointment> confirmedBetween(LocalDate from, LocalDate to, boolean withoutReminder) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM appointment.appointment "
                + "WHERE status = 'CONFIRMED' AND appointment_date <= ?");
        List<Object> args = new ArrayList<>(List.of(to));
        if (from != null) {
            sql.append(" AND appointment_date >= ?");
            args.add(from);
        }
        if (withoutReminder) {
            sql.append(" AND reminder_sent_at IS NULL");
        }
        sql.append(" ORDER BY appointment_date, start_time, id");
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray());
    }

    @Override
    public boolean markReminderSent(UUID appointmentId, OutboxEvent event, Instant now) {
        Boolean marked = tx.execute(status -> {
            int changed = jdbc.update("UPDATE appointment.appointment SET reminder_sent_at = ? "
                    + "WHERE id = ? AND status = 'CONFIRMED' AND reminder_sent_at IS NULL", Timestamp.from(now), appointmentId);
            if (changed == 1) {
                insert(List.of(event));
            }
            return changed == 1;
        });
        return Boolean.TRUE.equals(marked);
    }

    /** idx_outbox_event_unpublished serves this read: pending only, oldest first. */
    @Override
    public List<Stored> pending(int limit) {
        return jdbc.query("SELECT id, aggregate_id, event_type, payload::text AS payload, correlation_id, occurred_at "
                        + "FROM appointment.outbox_event WHERE published_at IS NULL AND failed_at IS NULL "
                        + "ORDER BY occurred_at, id LIMIT ?",
                (rs, n) -> new Stored(new OutboxEvent(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"), fromJson(rs.getString("payload")),
                        rs.getTimestamp("occurred_at").toInstant()), rs.getString("correlation_id")), limit);
    }

    @Override
    public boolean markPublished(UUID id, Instant now) {
        return jdbc.update("UPDATE appointment.outbox_event SET published_at = coalesce(published_at, ?) WHERE id = ?",
                Timestamp.from(now), id) == 1;
    }

    @Override
    public boolean markFailed(UUID id, String reason, Instant now) {
        return jdbc.update("UPDATE appointment.outbox_event SET failed_at = coalesce(failed_at, ?), last_error = ? "
                + "WHERE id = ?", Timestamp.from(now), reason, id) == 1;
    }

    private Map<String, Object> fromJson(String payload) {
        try {
            return json.readValue(payload, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A stored payload is not JSON", ex);
        }
    }

    private String toJson(OutboxEvent e) {
        try {
            return json.writeValueAsString(e.payload());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The payload of " + e.type() + " cannot be written as JSON", ex);
        }
    }

    private static Appointment map(ResultSet rs) throws SQLException {
        return Appointment.restore(rs.getObject("id", UUID.class), rs.getObject("barbershop_id", UUID.class),
                rs.getObject("client_id", UUID.class), rs.getObject("barber_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                new Slot(rs.getObject("appointment_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class)),
                Money.ofCents(rs.getLong("price_at_booking_cents")), rs.getString("notes"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                AppointmentStatus.valueOf(rs.getString("status")), rs.getString("cancelled_reason"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
