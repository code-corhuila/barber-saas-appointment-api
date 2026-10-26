package co.edu.corhuila.barbersaas.appointment.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A stand-in for barbershop-api, schedule-api and loyalty-api on a local port, answering like their
 * contracts: it reads the tenant from the forwarded token, so another barbershop's barber or service
 * answers 404 and only that barbershop's coupons are listed.
 */
final class OtherApisStub {

    record Service(UUID barbershopId, int durationMinutes, long priceCents) { }

    final Map<UUID, Service> services = new ConcurrentHashMap<>();
    /** Barber → its barbershop. Every barber offers the same starts every day. */
    final Map<UUID, UUID> barbers = new ConcurrentHashMap<>();
    /** Client → their ACTIVE reward coupon, and the coupon → its barbershop (DEC-APPT-09). */
    final Map<UUID, UUID> coupons = new ConcurrentHashMap<>();
    final Map<UUID, UUID> couponShops = new ConcurrentHashMap<>();
    volatile List<String> starts = List.of("09:00", "09:30", "10:00", "10:30");
    /** User → full name, for identity-auth's internal user read; only appointment-api's service token. */
    final Map<UUID, String> users = new ConcurrentHashMap<>();
    volatile boolean identityDown;
    /** Barbershop → its status, when it is not ACTIVE (DEC-APPT-10). */
    final Map<UUID, String> statuses = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    OtherApisStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        UUID tenant = tenant(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String body = null;
        if (path.startsWith("/internal/v1/users/")) {
            String name = users.get(UUID.fromString(path.substring("/internal/v1/users/".length())));
            int status = identityDown ? 503 : !"barber-saas-appointment-api".equals(subject(exchange)) ? 403
                    : name == null ? 404 : 200;
            byte[] bytes = (status == 200 ? "{\"fullName\":\"" + name + "\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            return;
        }
        if (path.equals("/api/v1/barbershops/me") && tenant != null) {
            body = "{\"timezone\":\"America/Bogota\",\"cancellationPolicyHours\":4,\"status\":\""
                    + statuses.getOrDefault(tenant, "ACTIVE") + "\"}";
        } else if (path.startsWith("/api/v1/services/")) {
            UUID id = UUID.fromString(path.substring("/api/v1/services/".length()));
            Service s = services.get(id);
            if (s != null && s.barbershopId().equals(tenant)) {
                body = "{\"id\":\"" + id + "\",\"durationMinutes\":" + s.durationMinutes() + ",\"priceCents\":"
                        + s.priceCents() + ",\"isActive\":true}";
            }
        } else if (path.equals("/api/v1/loyalty/coupons") && tenant != null) {
            UUID coupon = coupons.get(UUID.fromString(query.get("clientId")));
            boolean listed = coupon != null && tenant.equals(couponShops.get(coupon)) && "ACTIVE".equals(query.get("status"));
            body = "{\"data\":[" + (listed ? "{\"id\":\"" + coupon + "\",\"status\":\"ACTIVE\"}" : "")
                    + "],\"meta\":{\"page\":1,\"limit\":1,\"total\":" + (listed ? 1 : 0) + ",\"totalPages\":1}}";
        } else if (path.equals("/api/v1/availability")) {
            UUID barber = UUID.fromString(query.get("barberId"));
            if (tenant != null && tenant.equals(barbers.get(barber))) {
                body = "{\"slots\":[" + starts.stream().map(t -> "{\"startTime\":\"" + t + "\"}")
                        .collect(Collectors.joining(",")) + "]}";
            }
        }
        byte[] bytes = (body == null ? "{\"error\":\"NOT_FOUND\"}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String subject(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return json.readTree(Base64.getUrlDecoder().decode(authorization.substring(7).split("\\.")[1])).path("sub").asText();
    }

    private UUID tenant(String authorization) throws IOException {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(authorization.substring(7).split("\\.")[1]));
        return claims.path("barbershopId").isTextual() ? UUID.fromString(claims.get("barbershopId").asText()) : null;
    }

    private static Map<String, String> query(String raw) {
        if (raw == null) {
            return Map.of();
        }
        return java.util.Arrays.stream(raw.split("&")).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> p.length > 1 ? p[1] : ""));
    }
}
