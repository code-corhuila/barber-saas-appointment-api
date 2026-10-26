package co.edu.corhuila.barbersaas.appointment.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The tag Reviews of appointment-service.yaml over HTTP (DEC-APPT-11, HU-APPT-005 #130). */
class ReviewHttpTest extends HttpTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID shop = UUID.randomUUID();
    private final UUID barber = UUID.randomUUID();
    private final UUID otherBarber = UUID.randomUUID();
    private final UUID service = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final String day = LocalDate.now().plusDays(12).toString();
    private String client;
    private String staff;

    @BeforeEach
    void catalog() {
        OTHER_APIS.services.put(service, new OtherApisStub.Service(shop, 30, 2_500_000));
        OTHER_APIS.barbers.put(barber, shop);
        OTHER_APIS.barbers.put(otherBarber, shop);
        OTHER_APIS.users.put(clientId, "María Gómez");
        OTHER_APIS.identityDown = false;
        client = bearer(clientId, "CLIENT", shop);
        staff = bearer("ADMIN_BARBERSHOP", shop);
    }

    private String booked(UUID barberId, String start) throws Exception {
        String body = http.perform(post("/api/v1/appointments").header("Authorization", client)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barberId\":\"" + barberId + "\",\"serviceId\":\"" + service + "\",\"date\":\"" + day
                                + "\",\"startTime\":\"" + start + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private String completed(UUID barberId, String start) throws Exception {
        String id = booked(barberId, start);
        for (String step : new String[] {"confirm", "start", "complete"}) {
            http.perform(post("/api/v1/appointments/" + id + "/" + step).header("Authorization", staff))
                    .andExpect(status().isOk());
        }
        return id;
    }

    private ResultActions review(String token, String id, String body) throws Exception {
        return http.perform(post("/api/v1/appointments/" + id + "/review").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void theClientReviewsTheirCompletedAppointmentOnce() throws Exception {
        String id = completed(barber, "09:00");

        review(client, id, "{\"rating\":5,\"comment\":\"Excelente corte, muy puntual\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId").value(id))
                .andExpect(jsonPath("$.barbershopId").value(shop.toString()))
                .andExpect(jsonPath("$.barberId").value(barber.toString()))
                .andExpect(jsonPath("$.clientDisplayName").value("María G."))
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Excelente corte, muy puntual"))
                .andExpect(jsonPath("$.clientId").doesNotExist());

        review(client, id, "{\"rating\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("Esta cita ya fue calificada"));
    }

    @Test
    void anAppointmentThatIsNotCompletedIs422AndAnotherClientsIs404() throws Exception {
        String pending = booked(barber, "09:30");
        String done = completed(barber, "10:00");

        review(client, pending, "{\"rating\":4}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Solo se puede calificar una cita completada"));
        review(bearer("CLIENT", shop), done, "{\"rating\":4}").andExpect(status().isNotFound());
        review(staff, done, "{\"rating\":4}").andExpect(status().isForbidden());
        http.perform(post("/api/v1/appointments/" + done + "/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":4}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBodyOutsideTheContractIs400() throws Exception {
        String id = completed(barber, "10:30");

        for (String body : new String[] {"{}", "{\"rating\":0}", "{\"rating\":6}", "{\"rating\":\"5\"}",
                "{\"rating\":4.5}", "{\"rating\":5,\"comment\":\"" + "a".repeat(501) + "\"}",
                "{\"rating\":5,\"clientDisplayName\":\"Otro\"}"}) {
            review(client, id, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void whenIdentityAuthDoesNotAnswerItIs503AndNothingIsStored() throws Exception {
        String id = completed(barber, "09:00");
        OTHER_APIS.identityDown = true;

        review(client, id, "{\"rating\":5}").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(header().exists("Retry-After"));

        OTHER_APIS.identityDown = false;
        review(client, id, "{\"rating\":5}").andExpect(status().isCreated());
    }

    @Test
    void thePublicListNeedsNoTokenAndCarriesTheSummary() throws Exception {
        review(client, completed(barber, "09:00"), "{\"rating\":5}").andExpect(status().isCreated());
        review(client, completed(barber, "09:30"), "{\"rating\":4}").andExpect(status().isCreated());
        review(client, completed(otherBarber, "09:00"), "{\"rating\":4}").andExpect(status().isCreated());

        http.perform(get("/api/v1/reviews").param("barbershopId", shop.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].barberId").value(otherBarber.toString()))
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.summary.averageRating").value(4.3))
                .andExpect(jsonPath("$.summary.reviewCount").value(3));
        http.perform(get("/api/v1/reviews").param("barbershopId", shop.toString()).param("barberId", barber.toString()))
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.summary.averageRating").value(4.5));
        http.perform(get("/api/v1/reviews").param("barbershopId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.averageRating").isEmpty())
                .andExpect(jsonPath("$.summary.reviewCount").value(0));
        http.perform(get("/api/v1/reviews")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/reviews").param("barbershopId", "not-a-uuid")).andExpect(status().isBadRequest());
    }
}
