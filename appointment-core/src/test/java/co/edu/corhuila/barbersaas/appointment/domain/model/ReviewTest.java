package co.edu.corhuila.barbersaas.appointment.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The display name of a review: first name and the initial of the surname (DEC-APPT-11). */
class ReviewTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "María Gómez|María G.",
        "ana   pérez|ana P.",
        "Carlos Leal Rojas|Carlos L.",
        "Juan Pablo Borrero Morales|Juan B.",
        "Madonna|Madonna",
        "Élmer Ñuñez|Élmer Ñ."})
    void theDisplayNameIsTheFirstNameAndTheSurnamesInitial(String fullName, String expected) {
        assertEquals(expected, Review.displayName(fullName));
        assertEquals(expected, Review.displayName("  " + fullName + "  "));
    }

    @Test
    void anEmptyNameReadsCliente() {
        assertEquals("Cliente", Review.displayName(""));
        assertEquals("Cliente", Review.displayName("   "));
        assertEquals("Cliente", Review.displayName(null));
    }

    @Test
    void theDisplayNameNeverExceedsSixtyCharacters() {
        assertTrue(Review.displayName("A".repeat(80) + " Gómez").length() <= Review.MAX_DISPLAY_NAME);
        assertTrue(Review.displayName("A".repeat(80)).length() <= Review.MAX_DISPLAY_NAME);
    }
}
