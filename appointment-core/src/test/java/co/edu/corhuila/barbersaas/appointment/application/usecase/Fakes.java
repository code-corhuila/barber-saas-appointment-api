package co.edu.corhuila.barbersaas.appointment.application.usecase;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarberAvailability;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Clock;
import co.edu.corhuila.barbersaas.appointment.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Doubles of the outbound ports: no database, no HTTP, no framework. */
final class Fakes {

    private Fakes() {
    }

    static final class Repository implements AppointmentRepository {
        final Map<UUID, Appointment> rows = new LinkedHashMap<>();
        final Map<String, Idempotency.Stored> keys = new HashMap<>();
        final List<OutboxEvent> outbox = new ArrayList<>();
        /** Simulates a concurrent booking that wins the race after the overlap check. */
        boolean loseTheRace;

        @Override
        public Optional<Appointment> findById(UUID tenant, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(a -> a.barbershopId().equals(tenant));
        }

        @Override
        public Page<Appointment> page(UUID tenant, Query q, Page.Request page) {
            List<Appointment> all = rows.values().stream()
                    .filter(a -> a.barbershopId().equals(tenant))
                    .filter(a -> q.clientId() == null || q.clientId().equals(a.clientId()))
                    .filter(a -> q.status() == null || q.status() == a.status())
                    .filter(a -> q.barberId() == null || q.barberId().equals(a.barberId()))
                    .filter(a -> q.date() == null || q.date().equals(a.slot().date()))
                    .sorted(Comparator.comparing((Appointment a) -> a.slot().startsAt()).reversed())
                    .toList();
            return Page.of(all, page);
        }

        @Override
        public Page<Appointment> pageOfClient(UUID clientId, AppointmentStatus status, LocalDate date,
                                              Page.Request page) {
            List<Appointment> all = rows.values().stream()
                    .filter(a -> clientId.equals(a.clientId()))
                    .filter(a -> status == null || status == a.status())
                    .filter(a -> date == null || date.equals(a.slot().date()))
                    .sorted(Comparator.comparing((Appointment a) -> a.slot().startsAt()).reversed())
                    .toList();
            return Page.of(all, page);
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
            return rows.values().stream().anyMatch(a -> a.barberId().equals(barberId) && a.takesTime()
                    && a.slot().overlaps(slot));
        }

        @Override
        public Optional<Idempotency.Stored> findKey(String key, String operation) {
            return Optional.ofNullable(keys.get(key + " " + operation));
        }

        @Override
        public void saveNew(Appointment appointment, Idempotency.Key key, List<OutboxEvent> events) {
            if (loseTheRace) {
                throw new SlotTaken();
            }
            rows.put(appointment.id(), appointment);
            keys.put(key.key() + " " + key.operation(), new Idempotency.Stored(appointment.id(), key.requestHash()));
            outbox.addAll(events);
        }

        @Override
        public void update(Appointment appointment, List<OutboxEvent> events) {
            rows.put(appointment.id(), appointment);
            outbox.addAll(events);
        }
    }

    static final class Catalog implements BarbershopCatalog {
        final Map<UUID, CatalogService> services = new HashMap<>();
        Policy policy = new Policy(ZoneId.of("America/Bogota"), 4);

        @Override
        public Optional<CatalogService> service(Caller caller, UUID serviceId) {
            return Optional.ofNullable(services.get(serviceId));
        }

        @Override
        public Policy policy(Caller caller) {
            return policy;
        }
    }

    static final class Availability implements BarberAvailability {
        /** Barbers known in the tenant and the starts schedule-api offers for any date. */
        final Map<UUID, List<LocalTime>> barbers = new HashMap<>();

        @Override
        public Optional<List<LocalTime>> freeStarts(Caller caller, UUID barberId, UUID serviceId, LocalDate date) {
            return Optional.ofNullable(barbers.get(barberId));
        }
    }

    static final class FixedClock implements Clock {
        Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }
    }

    static final class Sequence implements IdGenerator {
        @Override
        public UUID next() {
            return UUID.randomUUID();
        }
    }
}
