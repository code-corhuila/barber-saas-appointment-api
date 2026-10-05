package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.appointment.application.port.in.BusySlotUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * listBusySlots (tag Internal, DEC-APPT-05, ADR-015), for schedule-api on the internal network: the
 * api-gateway routes /api/v1, never /internal/v1. The use case checks the service token.
 */
@RestController
@RequestMapping("/internal/v1/busy-slots")
public class InternalBusySlotsController {

    /** BusySlots: times only — no appointment, client or service data. */
    record BusySlotsView(List<SlotView> data) { }

    record SlotView(String startTime, String endTime) { }

    private final BusySlotUseCases busySlots;

    public InternalBusySlotsController(BusySlotUseCases busySlots) {
        this.busySlots = busySlots;
    }

    @GetMapping
    public BusySlotsView busy(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                              @RequestParam(required = false) String barbershopId,
                              @RequestParam(required = false) String barberId,
                              @RequestParam(required = false) String date) {
        List<FieldError> errors = new ArrayList<>();
        UUID shop = uuid("barbershopId", barbershopId, errors);
        UUID barber = uuid("barberId", barberId, errors);
        LocalDate day = date(date, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException("the request is not valid", errors);
        }
        List<Slot> busy = busySlots.busy(caller, shop, barber, day);
        return new BusySlotsView(busy.stream()
                .map(s -> new SlotView(Views.time(s.start()), Views.time(s.end()))).toList());
    }

    private static UUID uuid(String field, String value, List<FieldError> errors) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, value == null ? "required" : "must be a UUID"));
            return null;
        }
    }

    private static LocalDate date(String value, List<FieldError> errors) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            errors.add(new FieldError("date", value == null ? "required" : "must be a date YYYY-MM-DD"));
            return null;
        }
    }
}
