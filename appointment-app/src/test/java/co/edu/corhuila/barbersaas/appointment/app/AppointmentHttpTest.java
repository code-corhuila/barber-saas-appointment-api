package co.edu.corhuila.barbersaas.appointment.app;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** appointment-service.yaml over HTTP, with the in-memory repository and stubs of the other APIs. */
class AppointmentHttpTest extends HttpTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID shop = UUID.randomUUID();
    private final UUID barber = UUID.randomUUID();
    private final UUID service = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final String day = LocalDate.now().plusDays(10).toString();
    private String client;
    private String staff;

    @BeforeEach
    void catalog() {
        OTHER_APIS.services.put(service, new OtherApisStub.Service(shop, 30, 2_500_000));
        OTHER_APIS.barbers.put(barber, shop);
        client = bearer(clientId, "CLIENT", shop);
        staff = bearer("BARBER", shop);
    }

    private String booking(String startTime) {
        return "{\"barberId\":\"" + barber + "\",\"serviceId\":\"" + service + "\",\"date\":\"" + day
                + "\",\"startTime\":\"" + startTime + "\"}";
    }

    private ResultActions book(String token, String key, String body) throws Exception {
        return http.perform(post("/api/v1/appointments").header("Authorization", token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String bookedId(String startTime) throws Exception {
        String body = book(client, "key-" + UUID.randomUUID(), booking(startTime))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private ResultActions action(String token, String id, String action) throws Exception {
        return http.perform(post("/api/v1/appointments/" + id + "/" + action).header("Authorization", token));
    }

    @Test
    void aClientBooksAndGetsTheAppointmentWithItsLocation() throws Exception {
        ResultActions created = book(client, "key-00000001", booking("09:30")).andExpect(status().isCreated());
        String id = json.readTree(created.andReturn().getResponse().getContentAsString()).get("id").asText();

        created.andExpect(header().string("Location", endsWith("/api/v1/appointments/" + id)))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.startTime").value("09:30"))
                .andExpect(jsonPath("$.endTime").value("10:00"))
                .andExpect(jsonPath("$.priceAtBookingCents").value(2_500_000))
                .andExpect(jsonPath("$.barbershopId").value(shop.toString()))
                .andExpect(jsonPath("$.couponId").doesNotExist());
    }

    @Test
    void theClientsActiveRewardCouponPaysTheBooking() throws Exception {
        UUID coupon = UUID.randomUUID();
        OTHER_APIS.coupons.put(clientId, coupon);
        OTHER_APIS.couponShops.put(coupon, shop);

        book(client, "key-coupon-" + UUID.randomUUID(), booking("10:30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceAtBookingCents").value(0))
                .andExpect(jsonPath("$.couponId").value(coupon.toString()));
    }

    @Test
    void aCouponOfAnotherBarbershopIsNotApplied() throws Exception {
        UUID coupon = UUID.randomUUID();
        OTHER_APIS.coupons.put(clientId, coupon);
        OTHER_APIS.couponShops.put(coupon, UUID.randomUUID());

        book(client, "key-coupon-" + UUID.randomUUID(), booking("10:30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceAtBookingCents").value(2_500_000))
                .andExpect(jsonPath("$.couponId").doesNotExist());
    }

    @Test
    void retryingWithTheSameKeyAnswers200WithTheSameAppointment() throws Exception {
        String first = book(client, "key-00000002", booking("09:00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        book(client, "key-00000002", booking("09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(json.readTree(first).get("id").asText()));
        book(client, "key-00000002", booking("10:30"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void aBookingWithoutAnIdempotencyKeyIs400() throws Exception {
        http.perform(post("/api/v1/appointments").header("Authorization", client)
                        .contentType(MediaType.APPLICATION_JSON).content(booking("09:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
    }

    @Test
    void thePriceAndTheStatusAreRejectedNotIgnored() throws Exception {
        String withPrice = booking("09:00").replace("}", ",\"priceAtBookingCents\":1,\"status\":\"COMPLETED\"}");

        book(client, "key-00000003", withPrice)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedFieldsAreReportedTogether() throws Exception {
        book(client, "key-00000004", "{\"barberId\":\"nope\",\"date\":\"tomorrow\",\"startTime\":\"9am\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(4));
    }

    @Test
    void aDateInThePastIs400() throws Exception {
        book(client, "key-00000005", booking("09:00").replace(day, "2020-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aClientWhoSendsAClientIdIs403() throws Exception {
        book(client, "key-00000006", booking("09:00").replace("}", ",\"clientId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffBooksAWalkIn() throws Exception {
        book(staff, "key-00000007", booking("10:00").replace("}", ",\"clientId\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").isEmpty());
    }

    @Test
    void anOverlappingBookingIs422() throws Exception {
        bookedId("09:00");

        book(bearer("CLIENT", shop), "key-00000008", booking("09:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void aTimeTheBarberDoesNotOfferIs422() throws Exception {
        book(client, "key-00000009", booking("15:00")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aClientListsOnlyTheirOwnAndStaffSeesTheBarbershop() throws Exception {
        String mine = bookedId("09:00");
        book(bearer("CLIENT", shop), "key-00000010", booking("10:00")).andExpect(status().isCreated());

        http.perform(get("/api/v1/appointments").header("Authorization", client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(mine))
                .andExpect(jsonPath("$.meta.total").value(1));
        http.perform(get("/api/v1/appointments").param("barberId", barber.toString()).param("date", day)
                        .header("Authorization", staff))
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[0].startTime").value("10:00"));
    }

    /** DEC-APPT-06: barbershopId is output only, so a booking that carries it is refused like any unknown field. */
    @Test
    void aBookingThatCarriesABarbershopIdIsRejectedNotApplied() throws Exception {
        UUID otherShop = UUID.randomUUID();

        book(client, "key-00000012", booking("09:00").replace("}", ",\"barbershopId\":\"" + otherShop + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("barbershopId"));
        http.perform(get("/api/v1/appointments").header("Authorization", client))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    /** DEC-APPT-06: the login token of a client, with no barbershop, lists their own of every barbershop. */
    @Test
    void aClientWithoutABarbershopListsTheirOwnOfEveryBarbershop() throws Exception {
        UUID otherShop = UUID.randomUUID();
        UUID otherBarber = UUID.randomUUID();
        UUID otherService = UUID.randomUUID();
        OTHER_APIS.services.put(otherService, new OtherApisStub.Service(otherShop, 30, 3_000_000));
        OTHER_APIS.barbers.put(otherBarber, otherShop);
        String here = bookedId("10:00");
        String elsewhere = "{\"barberId\":\"" + otherBarber + "\",\"serviceId\":\"" + otherService + "\",\"date\":\""
                + day + "\",\"startTime\":\"09:00\"}";
        book(bearer(clientId, "CLIENT", otherShop), "key-" + UUID.randomUUID(), elsewhere)
                .andExpect(status().isCreated());
        book(bearer("CLIENT", shop), "key-" + UUID.randomUUID(), booking("09:30")).andExpect(status().isCreated());
        String withoutShop = bearer(clientId, "CLIENT", null);

        http.perform(get("/api/v1/appointments").header("Authorization", withoutShop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[0].id").value(here))
                .andExpect(jsonPath("$.data[0].barbershopId").value(shop.toString()))
                .andExpect(jsonPath("$.data[1].barbershopId").value(otherShop.toString()));
        http.perform(get("/api/v1/appointments").param("barbershopId", otherShop.toString())
                        .param("barberId", barber.toString()).header("Authorization", withoutShop))
                .andExpect(jsonPath("$.meta.total").value(2));
        http.perform(get("/api/v1/appointments").header("Authorization", staff))
                .andExpect(jsonPath("$.meta.total").value(2));
        http.perform(get("/api/v1/appointments").header("Authorization", client))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(here));
        http.perform(get("/api/v1/appointments/" + here).header("Authorization", withoutShop))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStatusOrAPageTooLargeIs400() throws Exception {
        http.perform(get("/api/v1/appointments").param("status", "DONE").header("Authorization", staff))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/v1/appointments").param("limit", "101").header("Authorization", staff))
                .andExpect(status().isBadRequest());
    }

    /** HU-TENANT-001: a token of another barbershop never sees, moves or cancels this appointment. */
    @Test
    void anotherBarbershopGets404EvenWithAValidId() throws Exception {
        String id = bookedId("09:30");
        String intruder = bearer("ADMIN_BARBERSHOP", UUID.randomUUID());

        http.perform(get("/api/v1/appointments/" + id).header("Authorization", intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        action(intruder, id, "confirm").andExpect(status().isNotFound());
        action(intruder, id, "cancel").andExpect(status().isNotFound());
        book(intruder, "key-00000011", booking("10:30")).andExpect(status().isNotFound());
    }

    @Test
    void staffTakesTheAppointmentThroughItsLifecycle() throws Exception {
        String id = bookedId("10:00");

        action(client, id, "confirm").andExpect(status().isForbidden());
        action(staff, id, "complete").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
        action(staff, id, "confirm").andExpect(jsonPath("$.status").value("CONFIRMED"));
        action(staff, id, "start").andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        action(staff, id, "complete").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        action(staff, id, "cancel").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void aClientCancelsWithAReasonAndTheSlotIsFreeAgain() throws Exception {
        String id = bookedId("10:30");

        String body = http.perform(post("/api/v1/appointments/" + id + "/cancel").header("Authorization", client)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Something came up\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode cancelled = json.readTree(body);

        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("Something came up", cancelled.get("cancelledReason").asText());
        book(bearer("CLIENT", shop), "key-00000012", booking("10:30")).andExpect(status().isCreated());
    }

    @Test
    void aSuperAdminCanDoNothingHere() throws Exception {
        http.perform(get("/api/v1/appointments").header("Authorization", bearer("SUPER_ADMIN", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anIdThatIsNotAUuidIs400() throws Exception {
        http.perform(get("/api/v1/appointments/123").header("Authorization", staff))
                .andExpect(status().isBadRequest());
    }
}
