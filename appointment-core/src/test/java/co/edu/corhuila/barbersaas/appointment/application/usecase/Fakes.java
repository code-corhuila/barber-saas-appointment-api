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
import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyUnavailable;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.RewardCoupons;
import co.edu.corhuila.barbersaas.appointment.application.port.out.UserDirectory;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
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
        /** Simulates a concurrent booking that applied the same coupon first (uq_appointment_coupon). */
        boolean loseTheCoupon;

        @Override
        public Optional<Appointment> findById(UUID tenant, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(a -> a.barbershopId().equals(tenant));
        }

        @Override
        public Optional<Appointment> findOfClient(UUID clientId, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(a -> clientId.equals(a.clientId()));
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
            if (loseTheCoupon) {
                throw new CouponTaken();
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
        Policy policy = new Policy(ZoneId.of("America/Bogota"), 4, true);

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

    static final class Coupons implements RewardCoupons {
        /** Client → their ACTIVE coupon in the caller's barbershop. */
        final Map<UUID, UUID> active = new HashMap<>();
        final List<UUID> askedFor = new ArrayList<>();
        RuntimeException failure;

        @Override
        public Optional<UUID> activeCoupon(Caller caller, UUID clientId) {
            askedFor.add(clientId);
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(active.get(clientId));
        }
    }

    static final class Sequence implements IdGenerator {
        @Override
        public UUID next() {
            return UUID.randomUUID();
        }
    }

    static final class Reviews implements ReviewRepository {
        final Map<UUID, Review> byAppointment = new LinkedHashMap<>();
        /** Simulates a concurrent review that was stored after the exists() check (uq_review_appointment). */
        boolean loseTheRace;

        @Override
        public boolean exists(UUID appointmentId) {
            return byAppointment.containsKey(appointmentId);
        }

        @Override
        public void saveNew(Review review) {
            if (loseTheRace || byAppointment.containsKey(review.appointmentId())) {
                throw new AlreadyReviewed();
            }
            byAppointment.put(review.appointmentId(), review);
        }

        private List<Review> of(UUID barbershopId, UUID barberId) {
            return byAppointment.values().stream()
                    .filter(r -> r.barbershopId().equals(barbershopId))
                    .filter(r -> barberId == null || barberId.equals(r.barberId()))
                    .sorted(Comparator.comparing(Review::createdAt).reversed()).toList();
        }

        @Override
        public Page<Review> page(UUID barbershopId, UUID barberId, Page.Request page) {
            return Page.of(of(barbershopId, barberId), page);
        }

        @Override
        public Summary summary(UUID barbershopId, UUID barberId) {
            List<Review> all = of(barbershopId, barberId);
            return new Summary(all.isEmpty() ? null : all.stream().mapToInt(Review::rating).average().orElseThrow(),
                    all.size());
        }
    }

    static final class Users implements UserDirectory {
        final Map<UUID, String> names = new HashMap<>();
        boolean down;
        int calls;

        @Override
        public String fullName(UUID userId) {
            calls++;
            if (down) {
                throw new DependencyUnavailable("identity-auth-api", "unreachable");
            }
            return names.getOrDefault(userId, "Ana Pérez");
        }
    }
}
