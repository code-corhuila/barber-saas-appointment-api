package co.edu.corhuila.barbersaas.appointment.application.usecase;

import co.edu.corhuila.barbersaas.appointment.application.port.in.BusySlotUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.domain.model.Slot;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The busy slots of DEC-APPT-05: the same answer for every role of schedule's caller. */
public class QueryBusySlots implements BusySlotUseCases {

    private final AppointmentRepository appointments;

    public QueryBusySlots(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    @Override
    public List<Slot> busy(Caller caller, UUID barbershopId, UUID barberId, LocalDate date) {
        caller.requireService(SCHEDULE_API);
        return appointments.busy(barbershopId, barberId, date);
    }
}
