package co.edu.corhuila.barbersaas.appointment.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** GET /internal/v1/busy-slots over HTTP (DEC-APPT-05, ADR-015), with real RS256 tokens. */
class InternalBusySlotsHttpTest extends HttpTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID shop = UUID.randomUUID();
    private final UUID barber = UUID.randomUUID();
    private final UUID service = UUID.randomUUID();
    private final String day = LocalDate.now().plusDays(10).toString();
    private final String schedule = serviceBearer("barber-saas-schedule-api");
    private String staff;

    @BeforeEach
    void catalog() {
        OTHER_APIS.services.put(service, new OtherApisStub.Service(shop, 30, 2_500_000));
        OTHER_APIS.barbers.put(barber, shop);
        staff = bearer("BARBER", shop);
    }

    private String book(String startTime) throws Exception {
        String body = http.perform(post("/api/v1/appointments").header("Authorization", bearer("CLIENT", shop))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barberId\":\"" + barber + "\",\"serviceId\":\"" + service + "\",\"date\":\"" + day
                                + "\",\"startTime\":\"" + startTime + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private ResultActions busy(String token, String query) throws Exception {
        var request = get("/internal/v1/busy-slots?" + query);
        return http.perform(token == null ? request : request.header("Authorization", token));
    }

    private String query(UUID barbershopId) {
        return "barbershopId=" + barbershopId + "&barberId=" + barber + "&date=" + day;
    }

    @Test
    void scheduleGetsOnlyTheTakenTimesInStartOrder() throws Exception {
        book("10:00");
        book("09:00");
        String cancelled = book("10:30");
        http.perform(post("/api/v1/appointments/" + cancelled + "/cancel").header("Authorization", staff))
                .andExpect(status().isOk());

        busy(schedule, query(shop))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":[{\"startTime\":\"09:00\",\"endTime\":\"09:30\"},"
                        + "{\"startTime\":\"10:00\",\"endTime\":\"10:30\"}]}", true));
    }

    @Test
    void completedAppointmentsTakeNoTime() throws Exception {
        String id = book("09:30");
        for (String step : new String[] {"confirm", "start", "complete"}) {
            http.perform(post("/api/v1/appointments/" + id + "/" + step).header("Authorization", staff))
                    .andExpect(status().isOk());
        }

        busy(schedule, query(shop)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void anotherBarbershopOrAnUnknownBarberIsAnEmptyList() throws Exception {
        book("09:00");

        busy(schedule, query(UUID.randomUUID())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        busy(schedule, "barbershopId=" + shop + "&barberId=" + UUID.randomUUID() + "&date=" + day)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void anyOtherTokenIs403AndNoTokenIs401() throws Exception {
        busy(serviceBearer("barber-saas-workflow"), query(shop)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        busy(bearer("CLIENT", shop), query(shop)).andExpect(status().isForbidden());
        busy(staff, query(shop)).andExpect(status().isForbidden());
        busy(bearer("ADMIN_BARBERSHOP", shop), query(shop)).andExpect(status().isForbidden());
        busy(null, query(shop)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void everyParameterIsRequiredAndValid() throws Exception {
        busy(schedule, "barberId=" + barber + "&date=" + day).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("barbershopId"));
        busy(schedule, "barbershopId=" + shop + "&barberId=nope&date=" + day).andExpect(status().isBadRequest());
        busy(schedule, "barbershopId=" + shop + "&barberId=" + barber + "&date=tomorrow")
                .andExpect(status().isBadRequest());
        busy(schedule, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.length()").value(3));
    }
}
