package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Against a real PostgreSQL migrated by barber-saas-appointment-db, as appointment_app, like
 * JdbcAppointmentRepositoryTest: set TEST_DATABASE_URL, TEST_DATABASE_USER and TEST_DATABASE_PASSWORD.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcReviewRepositoryTest extends ReviewRepositoryContract {

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
            System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD")));
    private final JdbcAppointmentRepository appointments = new JdbcAppointmentRepository(jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), new ObjectMapper());
    private final JdbcReviewRepository reviews = new JdbcReviewRepository(jdbc);

    @Override
    AppointmentRepository appointments() {
        return appointments;
    }

    @Override
    ReviewRepository reviews() {
        return reviews;
    }

    @Test
    void theReviewGoesWithItsAppointment() {
        Appointment a = appointment(barber);
        reviews.saveNew(review(a, 5, now));

        jdbc.update("DELETE FROM appointment.appointment WHERE id = ?", a.id());

        assertEquals(0, reviews.summary(shop, null).reviewCount(), "fk_review_appointment ON DELETE CASCADE");
    }
}
