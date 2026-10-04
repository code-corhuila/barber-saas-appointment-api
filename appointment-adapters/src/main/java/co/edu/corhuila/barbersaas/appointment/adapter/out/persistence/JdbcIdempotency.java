package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.out.Idempotency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** appointment.idempotency_key. The insert runs inside the caller's transaction, next to the resource. */
final class JdbcIdempotency {

    private JdbcIdempotency() {
    }

    static Optional<Idempotency.Stored> find(JdbcTemplate jdbc, String key, String operation) {
        return jdbc.query("SELECT resource_id, request_hash FROM appointment.idempotency_key "
                        + "WHERE key = ? AND operation = ?",
                (rs, n) -> new Idempotency.Stored(rs.getObject("resource_id", UUID.class), rs.getString("request_hash")),
                key, operation).stream().findFirst();
    }

    static void insert(JdbcTemplate jdbc, Idempotency.Key key, UUID resourceId) {
        jdbc.update("INSERT INTO appointment.idempotency_key (key, operation, resource_id, request_hash) "
                + "VALUES (?, ?, ?, ?)", key.key(), key.operation(), resourceId, key.requestHash());
    }
}
