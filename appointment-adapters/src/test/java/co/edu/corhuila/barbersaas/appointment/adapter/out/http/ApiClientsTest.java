package co.edu.corhuila.barbersaas.appointment.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog.CatalogService;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog.Policy;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DependencyFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The clients against a stub of barbershop-api and schedule-api on a local port. */
class ApiClientsTest {

    private HttpServer server;
    private String base;
    /** Per path and query: the answers in order, "!503" for a status; the last one repeats. */
    private final Map<String, Deque<String>> answers = new ConcurrentHashMap<>();
    private final Map<String, String> seenHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Caller caller = new Caller(UUID.randomUUID().toString(), Role.CLIENT, UUID.randomUUID(), "the-token");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            seenHeaders.put("Authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            seenHeaders.put("X-Correlation-Id", String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-Id")));
            Deque<String> queue = answers.get(exchange.getRequestURI().toString());
            String body = queue == null ? null : queue.size() > 1 ? queue.poll() : queue.peek();
            int status = body == null ? 404 : body.startsWith("!") ? Integer.parseInt(body.substring(1)) : 200;
            byte[] bytes = (status != 200 ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.clear();
    }

    private void answer(String pathAndQuery, String... bodies) {
        answers.put(pathAndQuery, new ConcurrentLinkedDeque<>(List.of(bodies)));
    }

    @Test
    void theServiceComesFromBarbershopApiWithTheCallersTokenAndCorrelationId() {
        UUID id = UUID.randomUUID();
        answer("/api/v1/services/" + id,
                "{\"id\":\"" + id + "\",\"durationMinutes\":45,\"priceCents\":2500000,\"isActive\":true}");
        MDC.put("correlationId", "corr-1");

        CatalogService service = new BarbershopApiClient(base).service(caller, id).orElseThrow();

        assertEquals(new CatalogService(id, 45, 2_500_000), service);
        assertEquals("Bearer the-token", seenHeaders.get("Authorization"));
        assertEquals("corr-1", seenHeaders.get("X-Correlation-Id"));
    }

    @Test
    void anInactiveOrUnknownServiceIsAbsent() {
        UUID inactive = UUID.randomUUID();
        answer("/api/v1/services/" + inactive,
                "{\"id\":\"" + inactive + "\",\"durationMinutes\":45,\"priceCents\":1,\"isActive\":false}");

        assertTrue(new BarbershopApiClient(base).service(caller, inactive).isEmpty());
        assertTrue(new BarbershopApiClient(base).service(caller, UUID.randomUUID()).isEmpty());
    }

    @Test
    void thePolicyIsTheTimeZoneAndCancellationWindowOfTheTokensBarbershop() {
        answer("/api/v1/barbershops/me", "{\"timezone\":\"America/Bogota\",\"cancellationPolicyHours\":4}");

        assertEquals(new Policy(ZoneId.of("America/Bogota"), 4), new BarbershopApiClient(base).policy(caller));
    }

    @Test
    void theFreeStartsComeFromScheduleApiAndAnUnknownBarberIsAbsent() {
        UUID barber = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 10, 10);
        answer("/api/v1/availability?barberId=" + barber + "&serviceId=" + service + "&date=2026-10-10",
                "{\"slots\":[{\"startTime\":\"09:00\",\"endTime\":\"09:30\"},{\"startTime\":\"10:30\",\"endTime\":\"11:00\"}]}");
        ScheduleApiClient schedule = new ScheduleApiClient(base);

        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(10, 30)),
                schedule.freeStarts(caller, barber, service, date).orElseThrow());
        assertTrue(schedule.freeStarts(caller, UUID.randomUUID(), service, date).isEmpty());
    }

    @Test
    void aBriefUnavailabilityIsRetriedOnce() {
        answer("/api/v1/barbershops/me", "!503", "{\"timezone\":\"UTC\",\"cancellationPolicyHours\":2}");

        assertEquals(2, new BarbershopApiClient(base).policy(caller).cancellationPolicyHours());
        assertEquals(2, calls.get());
    }

    @Test
    void retriesAreBoundedAndAnErrorIsADependencyFailure() {
        answer("/api/v1/barbershops/me", "!503");

        assertThrows(DependencyFailure.class, () -> new BarbershopApiClient(base).policy(caller));
        assertEquals(JsonApi.MAX_ATTEMPTS, calls.get());
    }

    @Test
    void aClientErrorIsNotRetried() {
        answer("/api/v1/barbershops/me", "!403");

        assertThrows(DependencyFailure.class, () -> new BarbershopApiClient(base).policy(caller));
        assertEquals(1, calls.get());
    }

    @Test
    void anUnreachableServiceIsADependencyFailure() {
        server.stop(0);

        assertThrows(DependencyFailure.class, () -> new BarbershopApiClient(base).policy(caller));
    }

    /** DEC-APPT-07: the jobs read the zone from the public detail, with no token, and Bogotá when it is gone. */
    @Test
    void theZoneOfABarbershopComesFromItsPublicDetailWithoutAToken() {
        UUID shop = UUID.randomUUID();
        answer("/api/v1/barbershops/" + shop, "{\"id\":\"" + shop + "\",\"timezone\":\"Pacific/Auckland\"}");

        assertEquals(ZoneId.of("Pacific/Auckland"), new BarbershopZonesClient(base).zoneOf(shop));
        assertEquals("null", seenHeaders.get("Authorization"));
        assertEquals(ZoneId.of("America/Bogota"), new BarbershopZonesClient(base).zoneOf(UUID.randomUUID()));
    }
}
