package co.edu.corhuila.barbersaas.appointment.app;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The daily jobs over HTTP (DEC-APPT-07), with real RS256 tokens and the in-memory repository. */
class InternalJobsHttpTest extends HttpTest {

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

    /** A CONFIRMED appointment of tomorrow in Bogotá (the zone used when barbershop-api has no detail). */
    private String confirmedTomorrow() throws Exception {
        String day = LocalDate.now(ZoneId.of("America/Bogota")).plusDays(1).toString();
        String body = http.perform(post("/api/v1/appointments").header("Authorization", bearer("CLIENT", shop))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barberId\":\"" + barber + "\",\"serviceId\":\"" + service + "\",\"date\":\"" + day
                                + "\",\"startTime\":\"09:00\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asText();
        http.perform(post("/api/v1/appointments/" + id + "/confirm").header("Authorization", bearer("BARBER", shop)))
                .andExpect(status().isOk());
        return id;
    }

    private long reminders(String appointmentId) throws Exception {
        JsonNode pending = json.readTree(http.perform(get("/internal/v1/outbox-events").param("limit", "100")
                .header("Authorization", worker)).andReturn().getResponse().getContentAsString()).get("data");
        long count = 0;
        for (JsonNode e : pending) {
            if (e.get("type").asText().equals("AppointmentReminderDue") && e.get("aggregateId").asText().equals(appointmentId)) {
                count++;
            }
        }
        return count;
    }

    @Test
    void theWorkerWritesTomorrowsRemindersOnce() throws Exception {
        String id = confirmedTomorrow();

        http.perform(post("/internal/v1/appointments/reminders-due").param("limit", "100").header("Authorization", worker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.remaining").isBoolean());
        http.perform(post("/internal/v1/appointments/reminders-due").param("limit", "100").header("Authorization", worker))
                .andExpect(status().isOk());

        if (reminders(id) != 1) {
            throw new AssertionError("expected exactly one AppointmentReminderDue for " + id);
        }
    }

    @Test
    void noShowsAnswerAJobResultAndTodaysAppointmentsAreLeftAlone() throws Exception {
        String id = confirmedTomorrow();

        http.perform(post("/internal/v1/appointments/no-shows").header("Authorization", worker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").isNumber())
                .andExpect(jsonPath("$.remaining").isBoolean());
        http.perform(get("/api/v1/appointments/" + id).header("Authorization", bearer("BARBER", shop)))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void onlyTheWorkerRunsTheJobsAndTheLimitIsChecked() throws Exception {
        for (String path : new String[] {"/internal/v1/appointments/reminders-due", "/internal/v1/appointments/no-shows"}) {
            http.perform(post(path).header("Authorization", bearer("ADMIN_BARBERSHOP", shop))).andExpect(status().isForbidden());
            http.perform(post(path).header("Authorization", serviceBearer("barber-saas-schedule-api")))
                    .andExpect(status().isForbidden());
            http.perform(post(path)).andExpect(status().isUnauthorized());
            http.perform(post(path).param("limit", "0").header("Authorization", worker)).andExpect(status().isBadRequest());
        }
    }
}
