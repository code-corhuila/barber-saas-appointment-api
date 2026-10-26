package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository;

class InMemoryReviewRepositoryTest extends ReviewRepositoryContract {

    private final InMemoryAppointmentRepository appointments = new InMemoryAppointmentRepository();
    private final InMemoryReviewRepository reviews = new InMemoryReviewRepository();

    @Override
    AppointmentRepository appointments() {
        return appointments;
    }

    @Override
    ReviewRepository reviews() {
        return reviews;
    }
}
