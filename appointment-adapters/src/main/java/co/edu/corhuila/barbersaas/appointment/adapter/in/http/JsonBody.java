package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads a request body against its contract schema: every schema here is additionalProperties: false,
 * so an unknown field answers 400 instead of being ignored — which is how a booking that carries
 * priceAtBookingCents or status is refused (DEC-APPT-02). Business rules stay in the domain.
 */
final class JsonBody {

    /** LocalTime of appointment-service.yaml: HH:mm, 00:00 to 23:59. */
    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final JsonNode node;
    private final String prefix;
    private final List<FieldError> errors;

    private JsonBody(JsonNode node, String prefix, List<FieldError> errors) {
        this.node = node;
        this.prefix = prefix;
        this.errors = errors;
    }

    static JsonBody of(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new ValidationException("the body must be a JSON object", List.of());
        }
        return new JsonBody(node, "", new ArrayList<>()).allow(allowed);
    }

    private JsonBody allow(Set<String> allowed) {
        node.fieldNames().forEachRemaining(f -> {
            if (!allowed.contains(f)) {
                errors.add(new FieldError(prefix + f, "not allowed"));
            }
        });
        return this;
    }

    String optionalText(String field, int max) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual() || v.asText().length() > max) {
            errors.add(new FieldError(prefix + field, "a string of at most " + max + " characters"));
            return null;
        }
        return v.asText();
    }

    /** HH:mm; absent gives null, and {@code required} decides whether that is an error. */
    LocalTime time(String field, boolean required) {
        JsonNode v = node.get(field);
        if (v == null) {
            if (required) {
                errors.add(new FieldError(prefix + field, "required"));
            }
            return null;
        }
        if (!v.isTextual() || !HH_MM.matcher(v.asText()).matches()) {
            errors.add(new FieldError(prefix + field, "must be a time HH:mm"));
            return null;
        }
        return LocalTime.parse(v.asText());
    }

    LocalDate date(String field) {
        JsonNode v = node.get(field);
        try {
            return LocalDate.parse(v.asText());
        } catch (RuntimeException e) {
            errors.add(new FieldError(prefix + field, v == null ? "required" : "must be a date YYYY-MM-DD"));
            return null;
        }
    }

    /** Absent or null: null (a walk-in has no clientId). */
    UUID optionalUuid(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return uuid(field);
    }

    UUID uuid(String field) {
        JsonNode v = node.get(field);
        try {
            return UUID.fromString(v.asText());
        } catch (RuntimeException e) {
            errors.add(new FieldError(prefix + field, v == null ? "required" : "must be a UUID"));
            return null;
        }
    }

    /** Throws every collected error at once, as one 400. */
    void validate() {
        if (!errors.isEmpty()) {
            throw new ValidationException("the request is not valid", errors);
        }
    }

    static LocalDate parseDate(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ValidationException("the request is not valid", List.of(new FieldError(field, "must be a date YYYY-MM-DD")));
        }
    }
}
