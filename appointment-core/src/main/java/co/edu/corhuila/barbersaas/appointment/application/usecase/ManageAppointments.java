package co.edu.corhuila.barbersaas.appointment.application.usecase;

import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Created;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository.KeyTaken;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository.Query;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository.SlotTaken;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarberAvailability;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog.CatalogService;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog.Policy;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Clock;
import co.edu.corhuila.barbersaas.appointment.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.appointment.domain.model.Money;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The appointment use cases. They orchestrate; the aggregate decides (hexagonal-architecture.md).
 * Booking follows 08-diagrams/uml/seq-book-appointment.md.
 */
public class ManageAppointments implements AppointmentUseCases {

    static final String BOOK_OPERATION = "POST /api/v1/appointments";
    private static final String SLOT_TAKEN = "The barber already has an appointment at that time";

    private final AppointmentRepository appointments;
    private final BarbershopCatalog catalog;
    private final BarberAvailability availability;
    private final Clock clock;
    private final IdGenerator ids;

    public ManageAppointments(AppointmentRepository appointments, BarbershopCatalog catalog,
                              BarberAvailability availability, Clock clock, IdGenerator ids) {
        this.appointments = appointments;
        this.catalog = catalog;
        this.availability = availability;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public Created<Appointment> book(Caller caller, BookCommand c, String idempotencyKey) {
        caller.require(Role.CLIENT, Role.ADMIN_BARBERSHOP, Role.BARBER);
        UUID tenant = caller.tenant();
        UUID bookedBy = caller.userId();
        UUID clientId = c.clientId();
        if (caller.is(Role.CLIENT)) {
            if (clientId != null) {
                throw new Forbidden("A client books for themselves and never sends clientId");
            }
            clientId = bookedBy;
        }
        String hash = RequestHash.of(tenant, bookedBy, c.barberId(), c.serviceId(), clientId, c.date(),
                c.startTime(), c.notes());
        Optional<Created<Appointment>> retried = retry(tenant, idempotencyKey, hash);
        if (retried.isPresent()) {
            return retried.get();
        }

        CatalogService service = catalog.service(caller, c.serviceId()).orElseThrow(() -> new NotFound("Service"));
        Policy policy = catalog.policy(caller);
        Instant now = clock.now();
        Slot slot = Slot.of(c.date(), c.startTime(), service.durationMinutes());
        Appointment appointment = Appointment.book(ids.next(), tenant, clientId, c.barberId(), c.serviceId(), slot,
                Money.ofCents(service.priceCents()), c.notes(), bookedBy,
                LocalDateTime.ofInstant(now, policy.timezone()), now);

        List<LocalTime> offered = availability.freeStarts(caller, c.barberId(), c.serviceId(), c.date())
                .orElseThrow(() -> new NotFound("Barber"));
        if (!offered.contains(c.startTime())) {
            throw new BusinessRuleViolation("The barber does not offer that time on " + c.date());
        }
        if (appointments.overlaps(c.barberId(), slot)) {
            throw new BusinessRuleViolation(SLOT_TAKEN);
        }
        try {
            appointments.saveNew(appointment, new Idempotency.Key(idempotencyKey, BOOK_OPERATION, hash),
                    List.of(Events.of(Events.CREATED, appointment, ids, now)));
        } catch (SlotTaken race) {
            throw new BusinessRuleViolation(SLOT_TAKEN);
        } catch (KeyTaken race) {
            return retry(tenant, idempotencyKey, hash).orElseThrow(IdempotencyKeyReused::new);
        }
        return new Created<>(appointment, true);
    }

    /** The same key and request return what was created; the same key with another request is refused. */
    private Optional<Created<Appointment>> retry(UUID tenant, String key, String hash) {
        return appointments.findKey(key, BOOK_OPERATION).map(stored -> {
            if (!stored.requestHash().equals(hash)) {
                throw new IdempotencyKeyReused();
            }
            return new Created<>(appointments.findById(tenant, stored.resourceId())
                    .orElseThrow(IdempotencyKeyReused::new), false);
        });
    }

    @Override
    public Page<Appointment> list(Caller caller, Filter f, Page.Request page) {
        caller.require(Role.CLIENT, Role.ADMIN_BARBERSHOP, Role.BARBER);
        if (caller.is(Role.CLIENT) && caller.barbershopId() == null) {
            // DEC-APPT-06: only the client's own, of every barbershop; the id is the token's sub, never a parameter.
            return appointments.pageOfClient(caller.userId(), f.status(), f.date(), page);
        }
        UUID onlyClient = caller.is(Role.CLIENT) ? caller.userId() : null;
        return appointments.page(caller.tenant(), new Query(onlyClient, f.status(), f.barberId(), f.date()), page);
    }

    @Override
    public Appointment get(Caller caller, UUID id) {
        caller.require(Role.CLIENT, Role.ADMIN_BARBERSHOP, Role.BARBER);
        return visible(caller, id);
    }

    @Override
    public Appointment confirm(Caller caller, UUID id) {
        return transition(caller, id, Events.CONFIRMED, Appointment::confirm);
    }

    @Override
    public Appointment start(Caller caller, UUID id) {
        return transition(caller, id, null, Appointment::start);
    }

    @Override
    public Appointment complete(Caller caller, UUID id) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER);
        Appointment appointment = visible(caller, id);
        Instant now = clock.now();
        appointment.complete(now);
        appointments.update(appointment, List.of(Events.completed(appointment, caller.subject(), ids, now)));
        return appointment;
    }

    @Override
    public Appointment markNoShow(Caller caller, UUID id) {
        return transition(caller, id, Events.NO_SHOW, Appointment::markNoShow);
    }

    @Override
    public Appointment cancel(Caller caller, UUID id, String reason) {
        caller.require(Role.CLIENT, Role.ADMIN_BARBERSHOP, Role.BARBER);
        Appointment appointment = visible(caller, id);
        Instant now = clock.now();
        if (caller.is(Role.CLIENT)) {
            Policy policy = catalog.policy(caller);
            appointment.cancel(reason, true, policy.cancellationPolicyHours(),
                    LocalDateTime.ofInstant(now, policy.timezone()), now);
        } else {
            // Staff is exempt from the window, so the barbershop's local time is not needed.
            appointment.cancel(reason, false, 0, LocalDateTime.ofInstant(now, ZoneOffset.UTC), now);
        }
        appointments.update(appointment, List.of(Events.of(Events.CANCELLED, appointment, ids, now)));
        return appointment;
    }

    /** Staff only. {@code event} null: the change is not announced (starting the service). */
    private Appointment transition(Caller caller, UUID id, String event, BiConsumer<Appointment, Instant> change) {
        caller.require(Role.ADMIN_BARBERSHOP, Role.BARBER);
        Appointment appointment = visible(caller, id);
        Instant now = clock.now();
        change.accept(appointment, now);
        appointments.update(appointment, event == null ? List.of() : List.of(Events.of(event, appointment, ids, now)));
        return appointment;
    }

    /** Another barbershop's appointment, or another client's, is not found: its existence is not confirmed. */
    private Appointment visible(Caller caller, UUID id) {
        Appointment appointment = appointments.findById(caller.tenant(), id)
                .orElseThrow(() -> new NotFound("Appointment"));
        if (caller.is(Role.CLIENT) && !caller.userId().equals(appointment.clientId())) {
            throw new NotFound("Appointment");
        }
        return appointment;
    }
}
