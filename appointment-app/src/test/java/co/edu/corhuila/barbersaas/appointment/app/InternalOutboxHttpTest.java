package co.edu.corhuila.barbersaas.appointment.app;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The outbox relay over HTTP (DEC-APPT-07, ADR-016), with real RS256 tokens. */
class InternalOutboxHttpTest extends HttpTest {

    private static final String OUTBOX = "/internal/v1/outbox-events";
    private final ObjectMapper json = new ObjectMapper();
    private final UUID shop = UUID.randomUUID();
    private final UUID barber = UUID.randomUUID();
    private final UUID service = UUID.randomUUID();
    private final String worker = serviceBearer("barber-saas-worker");

    @BeforeEach
    void catalog() throws Exception {
        OTHER_APIS.services.put(service, new OtherApisStub.Service(shop, 30, 2_500_000));
        OTHER_APIS.barbers.put(barber, shop);
        drainOutbox(json, worker);
    }

    /** Books an appointment and returns the id of its AppointmentCreated event, as the worker sees it. */
    private String bookedEvent() throws Exception {
        String body = http.perform(post("/api/v1/appointments").header("Authorization", bearer("CLIENT", shop))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).header("X-Correlation-Id", "corr-book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barberId\":\"" + barber + "\",\"serviceId\":\"" + service + "\",\"date\":\""
                                + LocalDate.now().plusDays(10) + "\",\"startTime\":\"09:00\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String appointmentId = json.readTree(body).get("id").asText();
        JsonNode pending = json.readTree(http.perform(get(OUTBOX).param("limit", "100").header("Authorization", worker))
                .andReturn().getResponse().getContentAsString()).get("data");
        for (JsonNode e : pending) {
            if (e.get("aggregateId").asText().equals(appointmentId)) {
                return e.get("id").asText();
            }
        }
        throw new AssertionError("the event of " + appointmentId + " is not pending");
    }

    @Test
    void theWorkerReadsTheEnvelopeOfEachPendingEvent() throws Exception {
        String id = bookedEvent();

        http.perform(get(OUTBOX).param("limit", "100").header("Authorization", worker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].type").value("AppointmentCreated"))
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].version").value(1))
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].aggregateType").value("appointment"))
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].barbershopId").value(shop.toString()))
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].correlationId").value("corr-book"))
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].payload.status").value("PENDING"));
        http.perform(get(OUTBOX).param("limit", "1").header("Authorization", worker))
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(1)));
        http.perform(get(OUTBOX).param("limit", "101").header("Authorization", worker))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aConfirmedEventIsNotListedAgainAndConfirmingTwiceIs204() throws Exception {
        String id = bookedEvent();

        http.perform(post(OUTBOX + "/" + id + "/published").header("Authorization", worker))
                .andExpect(status().isNoContent());
        http.perform(post(OUTBOX + "/" + id + "/published").header("Authorization", worker))
                .andExpect(status().isNoContent());
        http.perform(get(OUTBOX).param("limit", "100").header("Authorization", worker))
                .andExpect(jsonPath("$.data[*].id", not(hasItem(id))));
        http.perform(post(OUTBOX + "/" + UUID.randomUUID() + "/published").header("Authorization", worker))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aFailedEventLeavesThePendingListWithItsReason() throws Exception {
        String id = bookedEvent();

        http.perform(post(OUTBOX + "/" + id + "/failed").header("Authorization", worker)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"loyalty-api 422 UNKNOWN_EVENT_TYPE\"}"))
                .andExpect(status().isNoContent());
        http.perform(get(OUTBOX).param("limit", "100").header("Authorization", worker))
                .andExpect(jsonPath("$.data[*].id", not(hasItem(id))));
        http.perform(post(OUTBOX + "/" + id + "/failed").header("Authorization", worker)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        http.perform(post(OUTBOX + "/" + id + "/failed").header("Authorization", worker)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"retry\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheWorkersServiceTokenGetsIn() throws Exception {
        String id = bookedEvent();

        for (String token : new String[] {bearer("ADMIN_BARBERSHOP", shop), serviceBearer("barber-saas-schedule-api")}) {
            http.perform(get(OUTBOX).header("Authorization", token)).andExpect(status().isForbidden());
            http.perform(post(OUTBOX + "/" + id + "/published").header("Authorization", token))
                    .andExpect(status().isForbidden());
            http.perform(post(OUTBOX + "/" + id + "/failed").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        }
        http.perform(get(OUTBOX)).andExpect(status().isUnauthorized());
    }
}
