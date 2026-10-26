package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.Views.ReviewPageView;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.Views.ReviewView;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.ReviewUseCases;
import co.edu.corhuila.barbersaas.appointment.domain.model.Review;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP to use case for the tag Reviews (DEC-APPT-11): shapes are checked here, rules in the core. */
@RestController
public class ReviewController {

    private static final Set<String> REVIEW_FIELDS = Set.of("rating", "comment");

    private final ReviewUseCases reviews;

    public ReviewController(ReviewUseCases reviews) {
        this.reviews = reviews;
    }

    /** reviewAppointment: 201 with the review. */
    @PostMapping("/api/v1/appointments/{id}/review")
    public ResponseEntity<ReviewView> review(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                             @PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
        JsonBody b = JsonBody.of(body, REVIEW_FIELDS);
        int rating = b.integer("rating", 1, 5);
        String comment = b.optionalText("comment", Review.MAX_COMMENT);
        b.validate();
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewView.of(reviews.review(caller, id, rating, comment)));
    }

    /** listReviews: anonymous (AuthFilter lets it through); the barbershop comes from the query. */
    @GetMapping(AuthFilter.PUBLIC_REVIEWS)
    public ReviewPageView list(@RequestParam(required = false) UUID barbershopId,
                               @RequestParam(required = false) UUID barberId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit) {
        if (barbershopId == null) {
            throw new ValidationException("the request is not valid", List.of(new FieldError("barbershopId", "required")));
        }
        return ReviewPageView.of(reviews.list(barbershopId, barberId, Requests.page(page, limit)));
    }
}
