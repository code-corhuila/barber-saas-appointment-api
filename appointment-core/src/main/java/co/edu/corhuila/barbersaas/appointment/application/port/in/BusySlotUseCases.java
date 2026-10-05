package co.edu.corhuila.barbersaas.appointment.application.port.in;

import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What schedule-api needs to compute availability (appointment-service.yaml, listBusySlots,
 * DEC-APPT-05, ADR-015): the time a barber has taken on a date, never who booked it or what.
 */
public interface BusySlotUseCases {

    /** The service is schedule-api, the only caller this operation accepts. */
    String SCHEDULE_API = "barber-saas-schedule-api";

    /**
     * Only the service token of schedule-api. {@code barbershopId} comes from the query because the
     * caller is a service: schedule took it from its user's token. The PENDING, CONFIRMED and
     * IN_PROGRESS slots of that barber, date and barbershop, by start; anything unknown is empty.
     */
    List<Slot> busy(Caller caller, UUID barbershopId, UUID barberId, LocalDate date);
}
