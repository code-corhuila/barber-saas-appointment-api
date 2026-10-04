package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryAppointmentRepositoryTest extends RepositoryContract {

    private final InMemoryAppointmentRepository repository = new InMemoryAppointmentRepository();

    @Override
    AppointmentRepository repository() {
        return repository;
    }

    @Test
    void eventsAreKeptInTheOrderTheyHappened() {
        Appointment a = at(LocalTime.of(9, 0));
        repository.saveNew(a, key(), List.of(event(a, "AppointmentCreated")));
        repository.update(a, List.of(event(a, "AppointmentConfirmed")));

        assertEquals(List.of("AppointmentCreated", "AppointmentConfirmed"),
                repository.outbox().stream().map(OutboxEvent::type).toList());
    }
}
