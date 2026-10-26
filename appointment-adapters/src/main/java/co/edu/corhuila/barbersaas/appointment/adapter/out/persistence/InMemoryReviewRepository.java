package co.edu.corhuila.barbersaas.appointment.adapter.out.persistence;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Page;
import co.edu.corhuila.barbersaas.appointment.application.port.out.ReviewRepository;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Used when DATABASE_URL is empty. putIfAbsent keeps one review per appointment, as uq_review_appointment does. */
public class InMemoryReviewRepository implements ReviewRepository {

    private final Map<UUID, Review> byAppointment = new ConcurrentHashMap<>();

    @Override
    public boolean exists(UUID appointmentId) {
        return byAppointment.containsKey(appointmentId);
    }

    @Override
    public void saveNew(Review review) {
        if (byAppointment.putIfAbsent(review.appointmentId(), review) != null) {
            throw new AlreadyReviewed();
        }
    }

    @Override
    public Page<Review> page(UUID barbershopId, UUID barberId, Page.Request page) {
        return Page.of(of(barbershopId, barberId), page);
    }

    @Override
    public Summary summary(UUID barbershopId, UUID barberId) {
        List<Review> all = of(barbershopId, barberId);
        return new Summary(all.isEmpty() ? null : all.stream().mapToInt(Review::rating).average().orElseThrow(),
                all.size());
    }

    private List<Review> of(UUID barbershopId, UUID barberId) {
        return byAppointment.values().stream()
                .filter(r -> r.barbershopId().equals(barbershopId))
                .filter(r -> barberId == null || barberId.equals(r.barberId()))
                .sorted(Comparator.comparing(Review::createdAt).reversed().thenComparing(Review::id))
                .toList();
    }
}
