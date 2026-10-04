package co.edu.corhuila.barbersaas.appointment.application.port.out;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The barber's free slots for a service on a date, asked through schedule-api (seq-book-appointment,
 * divergence D-2 of 08-diagrams: hexagonal-architecture.md does not list this port yet).
 * Empty when the barber or the service does not exist in the caller's barbershop (404).
 */
public interface BarberAvailability {

    Optional<List<LocalTime>> freeStarts(Caller caller, UUID barberId, UUID serviceId, LocalDate date);
}
