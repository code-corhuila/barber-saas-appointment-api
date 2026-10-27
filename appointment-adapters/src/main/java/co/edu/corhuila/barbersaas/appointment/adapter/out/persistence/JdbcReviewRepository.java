package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * appointment.review (06-data/models.md §5) as appointment_app. Every read filters by the barbershop;
 * uq_review_appointment is the guarantee of one review per appointment, and its violation becomes
 * AlreadyReviewed, which the use case answers with 422.
 */
public class JdbcReviewRepository implements ReviewRepository {

    private static final String COLUMNS = "id, appointment_id, barbershop_id, barber_id, client_id, "
            + "client_display_name, rating, comment, created_at";
    private static final String ONE_REVIEW_PER_APPOINTMENT = "uq_review_appointment";

    private final JdbcTemplate jdbc;

    public JdbcReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(UUID appointmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM appointment.review WHERE appointment_id = ?)", Boolean.class,
                appointmentId));
    }

    @Override
    public Set<UUID> reviewedAmong(Collection<UUID> appointmentIds) {
        if (appointmentIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "SELECT appointment_id FROM appointment.review WHERE appointment_id = ANY (?)", UUID.class,
                (Object) appointmentIds.toArray(UUID[]::new)));
    }

    @Override
    public void saveNew(Review r) {
        try {
            jdbc.update("INSERT INTO appointment.review (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    r.id(), r.appointmentId(), r.barbershopId(), r.barberId(), r.clientId(), r.clientDisplayName(),
                    r.rating(), r.comment(), Timestamp.from(r.createdAt()));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains(ONE_REVIEW_PER_APPOINTMENT)) {
                throw new AlreadyReviewed();
            }
            throw e;
        }
    }

    @Override
    public Page<Review> page(UUID barbershopId, UUID barberId, Page.Request page) {
        JdbcPages.Query q = filter(barbershopId, barberId);
        return JdbcPages.page(jdbc, COLUMNS, new JdbcPages.Query(q.from(), q.args(), "ORDER BY created_at DESC, id"),
                (rs, n) -> map(rs), page);
    }

    /** idx_review_barbershop_created serves both the page and this aggregate. */
    @Override
    public Summary summary(UUID barbershopId, UUID barberId) {
        JdbcPages.Query q = filter(barbershopId, barberId);
        return jdbc.queryForObject("SELECT avg(rating) AS average, count(*) AS total " + q.from(),
                (rs, n) -> {
                    BigDecimal average = rs.getBigDecimal("average");
                    return new Summary(average == null ? null : average.doubleValue(), rs.getLong("total"));
                }, q.args().toArray());
    }

    private static JdbcPages.Query filter(UUID barbershopId, UUID barberId) {
        StringBuilder from = new StringBuilder("FROM appointment.review WHERE barbershop_id = ?");
        List<Object> args = new ArrayList<>(List.of(barbershopId));
        if (barberId != null) {
            from.append(" AND barber_id = ?");
            args.add(barberId);
        }
        return new JdbcPages.Query(from.toString(), args, "");
    }

    private static Review map(ResultSet rs) throws SQLException {
        return new Review(rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class),
                rs.getObject("barbershop_id", UUID.class), rs.getObject("barber_id", UUID.class),
                rs.getObject("client_id", UUID.class), rs.getString("client_display_name"), rs.getInt("rating"),
                rs.getString("comment"), rs.getTimestamp("created_at").toInstant());
    }
}
