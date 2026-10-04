package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.Views.AppointmentView;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.Views.PageView;
import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases.BookCommand;
import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases.Filter;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Created;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.AppointmentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP to use case for the tag Appointments: shapes are checked here, rules in the core. */
@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    private static final Set<String> BOOK_FIELDS = Set.of("barberId", "serviceId", "clientId", "date", "startTime",
            "notes");
    private static final Set<String> CANCEL_FIELDS = Set.of("reason");

    private final AppointmentUseCases appointments;

    public AppointmentController(AppointmentUseCases appointments) {
        this.appointments = appointments;
    }

    /** bookAppointment: 201 with Location, or 200 when the same Idempotency-Key is retried. */
    @PostMapping
    public ResponseEntity<AppointmentView> book(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestBody(required = false) JsonNode body) {
        String idempotencyKey = Requests.idempotencyKey(key);
        JsonBody b = JsonBody.of(body, BOOK_FIELDS);
        BookCommand command = new BookCommand(b.uuid("barberId"), b.uuid("serviceId"), b.optionalUuid("clientId"),
                b.date("date"), b.time("startTime", true), b.optionalText("notes", 500));
        b.validate();
        Created<Appointment> result = appointments.book(caller, command, idempotencyKey);
        AppointmentView view = AppointmentView.of(result.value());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/appointments/" + view.id())).body(view)
                : ResponseEntity.ok(view);
    }

    /** listAppointments: a CLIENT only ever gets their own (applied by the core, not by this filter). */
    @GetMapping
    public PageView<AppointmentView> list(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) UUID barberId,
                                          @RequestParam(required = false) String date) {
        Filter filter = new Filter(status(status), barberId, JsonBody.parseDate("date", date));
        return PageView.of(appointments.list(caller, filter, Requests.page(page, limit)), AppointmentView::of);
    }

    @GetMapping("/{id}")
    public AppointmentView get(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return AppointmentView.of(appointments.get(caller, id));
    }

    @PostMapping("/{id}/confirm")
    public AppointmentView confirm(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return AppointmentView.of(appointments.confirm(caller, id));
    }

    @PostMapping("/{id}/start")
    public AppointmentView start(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return AppointmentView.of(appointments.start(caller, id));
    }

    @PostMapping("/{id}/complete")
    public AppointmentView complete(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                    @PathVariable UUID id) {
        return AppointmentView.of(appointments.complete(caller, id));
    }

    @PostMapping("/{id}/no-show")
    public AppointmentView noShow(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return AppointmentView.of(appointments.markNoShow(caller, id));
    }

    /** cancelAppointment: the body is optional and only carries the reason. */
    @PostMapping("/{id}/cancel")
    public AppointmentView cancel(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id,
                                  @RequestBody(required = false) JsonNode body) {
        String reason = null;
        if (body != null && !body.isNull()) {
            JsonBody b = JsonBody.of(body, CANCEL_FIELDS);
            reason = b.optionalText("reason", 255);
            b.validate();
        }
        return AppointmentView.of(appointments.cancel(caller, id, reason));
    }

    private static AppointmentStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AppointmentStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("the request is not valid",
                    List.of(new FieldError("status", "one of " + List.of(AppointmentStatus.values()))));
        }
    }
}
