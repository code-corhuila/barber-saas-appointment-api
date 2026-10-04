package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository.SlotTaken;
import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs against a real PostgreSQL migrated by barber-saas-appointment-db, connected as appointment_app
 * (never the administrator). It does not create the schema: set TEST_DATABASE_URL, TEST_DATABASE_USER
 * and TEST_DATABASE_PASSWORD to run it; without them it is skipped.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcAppointmentRepositoryTest extends RepositoryContract {

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
            System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD")));
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    private final JdbcAppointmentRepository repository = new JdbcAppointmentRepository(jdbc, tx, new ObjectMapper());

    @Override
    AppointmentRepository repository() {
        return repository;
    }

    @Test
    void theAppointmentItsKeyAndItsEventCommitTogether() {
        Appointment a = at(LocalTime.of(17, 0));
        MDC.put("correlationId", "it-correlation");
        try {
            repository.saveNew(a, key(), List.of(event(a, "AppointmentCreated")));
        } finally {
            MDC.remove("correlationId");
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT event_type, payload::text AS payload, correlation_id, "
                + "published_at FROM appointment.outbox_event WHERE aggregate_id = ?", a.id());
        assertEquals("AppointmentCreated", row.get("event_type"));
        assertEquals("{\"appointmentId\": \"" + a.id() + "\"}", row.get("payload"));
        assertEquals("it-correlation", row.get("correlation_id"));
        assertEquals(null, row.get("published_at"), "pending until the publisher sends it");
    }

    @Test
    void aRefusedBookingLeavesNeitherTheKeyNorTheEvent() {
        repository.saveNew(at(LocalTime.of(18, 0)), key(), List.of());
        Appointment clash = at(LocalTime.of(18, 15));
        Idempotency.Key key = key();

        assertThrows(SlotTaken.class, () -> repository.saveNew(clash, key, List.of(event(clash, "AppointmentCreated"))));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment.idempotency_key WHERE key = ?",
                Integer.class, key.key()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment.outbox_event WHERE aggregate_id = ?",
                Integer.class, clash.id()));
    }
}
