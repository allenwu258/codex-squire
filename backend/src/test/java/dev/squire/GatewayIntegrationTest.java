package dev.squire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.squire.persistence.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {
    private static final Path DATA = temporaryDirectory();
    private static final Map<String, String> MODES = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, String> BODIES = new ConcurrentHashMap<>();
    private static final String JSON = "{\"id\":\"resp_test\",\"object\":\"response\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"name\":\"tool\",\"arguments\":\"{}\"}],"
            + "\"usage\":{\"input_tokens\":12,\"output_tokens\":8,\"input_tokens_details\":{\"cached_tokens\":3}}}";
    private static final String TERMINAL = "event: response.completed\r\ndata: {\"type\":\"response.completed\",\"response\":" + JSON + "}\r\n\r\n";
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
        String source = request.uri().split("/")[1];
        CALLS.computeIfAbsent(source, key -> new AtomicInteger()).incrementAndGet();
        return request.receive().aggregate().asString().flatMap(body -> {
            BODIES.put(source, body);
            assertThat(request.requestHeaders().get(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-upstream-secret");
            String mode = MODES.getOrDefault(source, "json");
            if (mode.equals("429") || mode.equals("400") || mode.equals("503")) {
                return response.status(Integer.parseInt(mode)).header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"error\":{\"message\":\"upstream rejection\"}}")).then();
            }
            if (mode.equals("redirect")) {
                return response.status(307).header("Location", "http://127.0.0.1:1/steal").send().then();
            }
            if (mode.equals("sse") || mode.equals("large")) {
                String events = mode.equals("large") ? "data: " + "x".repeat(1024 * 1024 + 30) + "\n\n" : "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n";
                byte[] bytes = (events + TERMINAL).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                List<byte[]> pieces = IntStream.range(0, (bytes.length + 6) / 7)
                        .mapToObj(index -> java.util.Arrays.copyOfRange(bytes, index * 7, Math.min(bytes.length, index * 7 + 7))).toList();
                return response.header("Content-Type", "text/event-stream").sendByteArray(Flux.fromIterable(pieces)).then();
            }
            if (mode.equals("slow")) {
                return response.header("Content-Type", "text/event-stream").sendGroups(Flux.concat(
                        Mono.just("data: {\"type\":\"response.created\"}\n\n"),
                        Mono.delay(Duration.ofSeconds(30)).map(ignored -> TERMINAL))
                        .map(value -> Mono.just(io.netty.buffer.Unpooled.copiedBuffer(value, java.nio.charset.StandardCharsets.UTF_8)))).then();
            }
            if (mode.equals("truncated")) {
                return response.header("Content-Type", "text/event-stream")
                        .sendString(Mono.just("data: {\"type\":\"response.created\"}\n\n")).then();
            }
            return response.header("Content-Type", "application/json").header("x-request-id", "upstream-id")
                    .sendString(Mono.just(JSON)).then();
        });
    }).bindNow();

    @LocalServerPort
    private int port;
    @Autowired
    private Database database;
    @Autowired
    private ObjectMapper mapper;
    private WebTestClient admin;
    private WebTestClient gateway;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("squire.data-dir", DATA::toString);
    }

    @BeforeEach
    void setup() throws IOException {
        database.transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM attempts");
                statement.executeUpdate("DELETE FROM scheme_members");
                statement.executeUpdate("DELETE FROM schemes");
                statement.executeUpdate("DELETE FROM providers");
            }
            return null;
        });
        MODES.clear();
        CALLS.clear();
        BODIES.clear();
        admin = client(Files.readString(DATA.resolve("admin.token")));
        gateway = client(Files.readString(DATA.resolve("gateway.token")));
    }

    @AfterAll
    static void closeServer() {
        UPSTREAM.disposeNow();
    }

    @Test
    void forwardsJsonExactlyOnceAndSettlesReportedUsage() {
        String a = provider("a", 0, 0, 4);
        String b = provider("b", 0, 0, 4);
        scheme("ORDERED", List.of(a, b));
        String request = "{\"model\":\"test-model\",\"input\":[],\"tools\":[{\"type\":\"function\",\"name\":\"tool\"}],\"store\":false}";
        gateway.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("x-request-id", "upstream-id")
                .expectBody(String.class).isEqualTo(JSON);
        assertThat(CALLS.get("a").get()).isEqualTo(1);
        assertThat(CALLS).doesNotContainKey("b");
        assertThat(BODIES.get("a")).isEqualTo(request);
        settled().expectBody().jsonPath("$.inputTokens").isEqualTo(12).jsonPath("$.outputTokens").isEqualTo(8)
                .jsonPath("$.cachedTokens").isEqualTo(3).jsonPath("$.chargedTokens").isEqualTo(20);
    }

    @Test
    void streamsFragmentedSseWithNestedUsageAndUtf8() {
        MODES.put("a", "sse");
        scheme("ORDERED", List.of(provider("a", 0, 0, 4)));
        String text = response(true).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(text).contains("你好", "response.completed");
        settled().expectBody().jsonPath("$.chargedTokens").isEqualTo(20).jsonPath("$.successes").isEqualTo(1);
    }

    @Test
    void onlyRateRejectionFailsOver() {
        MODES.put("a", "429");
        scheme("ORDERED", List.of(provider("a", 0, 0, 4), provider("b", 0, 0, 4)));
        response(false).expectStatus().isOk();
        settled().expectBody().jsonPath("$.requests").isEqualTo(2).jsonPath("$.chargedTokens").isEqualTo(20);
        assertThat(CALLS.get("a").get()).isEqualTo(1);
        assertThat(CALLS.get("b").get()).isEqualTo(1);
    }

    @Test
    void doesNotReplayClientErrorsOrServerErrors() {
        scheme("ORDERED", List.of(provider("a", 0, 0, 4), provider("b", 0, 0, 4)));
        for (String code : List.of("400", "503")) {
            MODES.put("a", code);
            response(false).expectStatus().isEqualTo(Integer.parseInt(code)).expectBody()
                    .jsonPath("$.error.message").isEqualTo("upstream rejection");
        }
        assertThat(CALLS).doesNotContainKey("b");
    }

    @Test
    void rejectsRedirectWithoutSendingCredentialsToAnotherHost() {
        MODES.put("a", "redirect");
        scheme("ORDERED", List.of(provider("a", 0, 0, 4)));
        response(false).expectStatus().isEqualTo(502);
        settled().expectBody().jsonPath("$.failures").isEqualTo(1);
    }

    @Test
    void enforcesDailyRequestLimitAndSkipsLimitedProvider() {
        scheme("ORDERED", List.of(provider("a", 1, 0, 4), provider("b", 0, 0, 4)));
        response(false).expectStatus().isOk();
        settled();
        response(false).expectStatus().isOk();
        settled();
        assertThat(CALLS.get("a").get()).isEqualTo(1);
        assertThat(CALLS.get("b").get()).isEqualTo(1);
    }

    @Test
    void rejectsTokenReservationExceedingRemainingLimit() {
        scheme("ORDERED", List.of(provider("a", 0, 99, 4)));
        response(false).expectStatus().isEqualTo(503);
        assertThat(CALLS).isEmpty();
    }

    @Test
    void balancesWithConfiguredWeights() {
        String a = provider("a", 0, 0, 4);
        String b = provider("b", 0, 0, 4);
        JsonNode result = admin.post().uri("/api/schemes").bodyValue(Map.of("name", "weighted", "strategy", "BALANCED",
                "members", List.of(Map.of("providerId", a, "weight", 2), Map.of("providerId", b, "weight", 1))))
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        activate(result.path("id").asText());
        for (int index = 0; index < 6; index++) {
            response(false).expectStatus().isOk();
            settled();
        }
        assertThat(CALLS.get("a").get()).isEqualTo(4);
        assertThat(CALLS.get("b").get()).isEqualTo(2);
    }

    @Test
    void incompleteStreamKeepsReservationWithoutInventingTokens() {
        MODES.put("a", "truncated");
        scheme("ORDERED", List.of(provider("a", 0, 0, 4)));
        response(true).expectStatus().isOk().expectBody(String.class);
        settled().expectBody().jsonPath("$.chargedTokens").isEqualTo(100)
                .jsonPath("$.inputTokens").isEqualTo(0).jsonPath("$.uncertainRequests").isEqualTo(1);
        admin.get().uri("/api/attempts").exchange().expectBody().jsonPath("$[0].status").isEqualTo("INCOMPLETE");
    }

    @Test
    void cancellationSettlesAndReleasesConcurrency() throws InterruptedException {
        MODES.put("a", "slow");
        scheme("ORDERED", List.of(provider("a", 0, 0, 1)));
        var running = gateway.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"test-model\",\"input\":[],\"stream\":true}").exchange()
                .expectStatus().isOk().returnResult(String.class);
        response(false).expectStatus().isEqualTo(503);
        running.getResponseBody().take(1).blockLast(Duration.ofSeconds(5));
        settled().expectBody().jsonPath("$.uncertainRequests").isEqualTo(1);
        MODES.put("a", "json");
        response(false).expectStatus().isOk();
    }

    @Test
    void separatesAdminAndGatewayCredentialsAndRejectsForeignOrigins() {
        gateway.get().uri("/api/providers").exchange().expectStatus().isUnauthorized();
        admin.post().uri("/v1/responses").bodyValue(Map.of("input", List.of(), "model", "test"))
                .exchange().expectStatus().isUnauthorized();
        admin.get().uri("/api/providers").header(HttpHeaders.ORIGIN, "https://evil.example")
                .exchange().expectStatus().isForbidden();
        client("wrong").get().uri("/api/health").exchange().expectStatus().isOk();
    }

    @Test
    void preservesKeyOnUpdateAndProtectsSchemeReferences() {
        String id = provider("a", 0, 0, 4);
        admin.get().uri("/api/providers").exchange().expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("test-upstream-secret", "encryptedKey"));
        admin.put().uri("/api/providers/" + id).bodyValue(providerInput("renamed", 0, 0, 4, ""))
                .exchange().expectStatus().isOk();
        String plan = scheme("ORDERED", List.of(id));
        admin.delete().uri("/api/providers/" + id).exchange().expectStatus().isEqualTo(409);
        admin.delete().uri("/api/schemes/" + plan).exchange().expectStatus().isEqualTo(409);
        response(false).expectStatus().isOk();
    }

    @Test
    void validatesInputsAndRollsBackInvalidSchemeMutation() {
        String id = provider("a", 0, 0, 4);
        String scheme = scheme("ORDERED", List.of(id));
        admin.put().uri("/api/schemes/" + scheme).bodyValue(Map.of("name", "broken", "strategy", "ORDERED",
                "members", List.of(Map.of("providerId", "missing", "weight", 1))))
                .exchange().expectStatus().isEqualTo(409);
        admin.get().uri("/api/schemes").exchange().expectBody().jsonPath("$[0].members[0].providerId").isEqualTo(id);
        admin.post().uri("/api/providers").bodyValue(Map.of("name", "invalid")).exchange().expectStatus().isBadRequest();
        gateway.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"test\",\"input\":[],\"previous_response_id\":\"external\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void rejectsOversizedRequestBeforeContactingUpstream() {
        gateway.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"test\",\"input\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}")
                .exchange().expectStatus().isEqualTo(413);
        assertThat(CALLS).isEmpty();
    }

    @Test
    void preservesFrameworkUnsupportedMediaTypeStatus() {
        gateway.post().uri("/v1/responses").contentType(MediaType.TEXT_PLAIN).bodyValue("not JSON")
                .exchange().expectStatus().isEqualTo(415);
    }

    private String provider(String name, long requests, long tokens, int concurrency) {
        JsonNode value = admin.post().uri("/api/providers").bodyValue(providerInput(name, requests, tokens, concurrency, "test-upstream-secret"))
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        return value.path("id").asText();
    }

    private Map<String, Object> providerInput(String name, long requests, long tokens, int concurrency, String key) {
        return Map.of("name", name, "baseUrl", "http://127.0.0.1:" + UPSTREAM.port() + "/" + name + "/v1", "apiKey", key,
                "enabled", true, "dailyRequestLimit", requests, "dailyTokenLimit", tokens,
                "maxConcurrency", concurrency, "tokenReservation", 100);
    }

    private String scheme(String strategy, List<String> providers) {
        JsonNode result = admin.post().uri("/api/schemes").bodyValue(Map.of("name", "plan", "strategy", strategy,
                "members", providers.stream().map(id -> Map.of("providerId", id, "weight", 1)).toList()))
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        String id = result.path("id").asText();
        activate(id);
        return id;
    }

    private void activate(String id) {
        admin.post().uri("/api/schemes/" + id + "/activate").exchange().expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec response(boolean stream) {
        return gateway.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "test-model", "input", List.of(), "stream", stream)).exchange();
    }

    private WebTestClient.ResponseSpec settled() {
        for (int index = 0; index < 100; index++) {
            JsonNode stats = admin.get().uri("/api/statistics").exchange().expectBody(JsonNode.class).returnResult().getResponseBody();
            if (stats.path("inFlight").asLong() == 0) {
                return admin.get().uri("/api/statistics").exchange().expectStatus().isOk();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("Usage did not settle");
    }

    private WebTestClient client(String token) {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(10))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("squire-integration-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
