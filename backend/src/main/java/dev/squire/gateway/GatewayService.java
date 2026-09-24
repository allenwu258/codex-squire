package dev.squire.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.squire.api.ApiException;
import dev.squire.security.LocalSecrets;
import dev.squire.usage.AttemptRepository;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.netty.http.client.HttpClient;

@Service
public final class GatewayService {
    private static final List<String> REQUEST_HEADERS = List.of("OpenAI-Beta", "originator", "version",
            "session_id", "conversation_id", "x-codex-turn-state", "x-codex-turn-metadata");
    private static final List<String> RESPONSE_HEADERS = List.of("x-request-id", "openai-processing-ms", "retry-after", "x-codex-turn-state");
    private final RoutingService routing;
    private final AttemptRepository attempts;
    private final LocalSecrets secrets;
    private final ObjectMapper mapper;
    private final Scheduler databaseScheduler;
    private final WebClient client;
    private final Duration totalTimeout;

    public GatewayService(RoutingService routing, AttemptRepository attempts, LocalSecrets secrets, ObjectMapper mapper,
                          Scheduler databaseScheduler, @Value("${squire.upstream-idle-seconds}") int idleSeconds,
                          @Value("${squire.upstream-total-seconds}") int totalSeconds) {
        this.routing = routing;
        this.attempts = attempts;
        this.secrets = secrets;
        this.mapper = mapper;
        this.databaseScheduler = databaseScheduler;
        totalTimeout = Duration.ofSeconds(totalSeconds);
        HttpClient transport = HttpClient.create().followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .responseTimeout(Duration.ofSeconds(idleSeconds));
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport)).build();
    }

    public Mono<Void> forward(byte[] body, ServerWebExchange exchange) {
        String model = validate(body);
        String requestId = UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("x-squire-request-id", requestId);
        return Mono.fromCallable(routing::route).subscribeOn(databaseScheduler)
                .flatMap(route -> next(route, Set.of(), requestId, model, body, exchange)).then()
                .onErrorMap(error -> !(error instanceof ApiException) && !exchange.getResponse().isCommitted(),
                        error -> new ApiException(502, "UPSTREAM_FAILURE", "Upstream connection failed; the request was not replayed"));
    }

    private Mono<Outcome> next(RoutingService.Route route, Set<String> attempted, String requestId, String model,
                               byte[] body, ServerWebExchange exchange) {
        String providerId = routing.nextProvider(route, attempted);
        if (providerId == null) {
            return Mono.error(new ApiException(503, "NO_AVAILABLE_PROVIDER", "All providers are disabled, limited or temporarily unavailable"));
        }
        Set<String> nextAttempted = new HashSet<>(attempted);
        nextAttempted.add(providerId);
        Mono<AttemptContext> resource = Mono.fromCallable(() -> {
            AttemptRepository.Admission admission = routing.admit(route, providerId, requestId, model);
            return admission == null ? null : new AttemptContext(admission);
        }).subscribeOn(databaseScheduler).doOnDiscard(AttemptContext.class, context ->
                databaseScheduler.schedule(() -> attempts.finish(context.admission, "CANCELLED", null, null, false)));
        return Mono.usingWhen(resource,
                context -> send(context, body, exchange, nextAttempted.size() < route.members().size()),
                context -> settle(context, context.status),
                (context, error) -> settle(context, "TRANSPORT_ERROR"),
                context -> settle(context, "CANCELLED"))
                .defaultIfEmpty(Outcome.RETRY)
                .flatMap(outcome -> outcome == Outcome.RETRY
                        ? next(route, nextAttempted, requestId, model, body, exchange) : Mono.just(outcome));
    }

    private Mono<Outcome> send(AttemptContext context, byte[] body, ServerWebExchange exchange, boolean hasNext) {
        String baseUrl = context.admission.provider().baseUrl();
        String endpoint = baseUrl + (URI.create(baseUrl).getPath().isEmpty() ? "/v1/responses" : "/responses");
        return client.post().uri(URI.create(endpoint))
                .headers(headers -> {
                    headers.setBearerAuth(secrets.decrypt(context.admission.provider().encryptedKey()));
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
                    for (String name : REQUEST_HEADERS) {
                        String value = exchange.getRequest().getHeaders().getFirst(name);
                        if (value != null) {
                            headers.set(name, value);
                        }
                    }
                })
                .bodyValue(body)
                .exchangeToMono(response -> relay(context, response, exchange, hasNext))
                .timeout(totalTimeout);
    }

    private Mono<Outcome> relay(AttemptContext context, ClientResponse upstream, ServerWebExchange exchange, boolean hasNext) {
        int status = upstream.statusCode().value();
        context.httpStatus = status;
        context.rejected = status >= 400 && status < 500;
        // Only explicit rate rejection is replayable by default. Other failures may have executed work.
        if (status == 429 && hasNext) {
            context.status = "RATE_LIMITED";
            return upstream.releaseBody().thenReturn(Outcome.RETRY);
        }
        if (status >= 300 && status < 400) {
            context.status = "REDIRECT_REJECTED";
            return upstream.releaseBody().then(Mono.error(new ApiException(502, "UPSTREAM_REDIRECT", "Upstream redirect refused")));
        }
        context.status = status >= 400 ? "UPSTREAM_ERROR" : "SUCCEEDED";
        MediaType contentType = upstream.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        context.inspector = new UsageInspector(mapper, MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType));
        exchange.getResponse().setStatusCode(upstream.statusCode());
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.setContentType(contentType);
        headers.set("x-squire-provider-id", context.admission.provider().id());
        headers.set("X-Accel-Buffering", "no");
        for (String name : RESPONSE_HEADERS) {
            String value = upstream.headers().asHttpHeaders().getFirst(name);
            if (value != null) {
                headers.set(name, value);
            }
        }
        Flux<DataBuffer> content = upstream.bodyToFlux(DataBuffer.class).doOnNext(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            int position = buffer.readPosition();
            buffer.read(bytes);
            buffer.readPosition(position);
            context.inspector.accept(bytes);
        }).doOnComplete(() -> {
            context.inspector.end();
            if (status < 400) {
                context.status = context.inspector.resultStatus();
            }
        });
        // Flush each upstream chunk; buffering the whole Flux breaks interactive SSE delivery.
        return exchange.getResponse().writeAndFlushWith(content.map(Mono::just)).thenReturn(Outcome.DELIVERED);
    }

    private Mono<Void> settle(AttemptContext context, String status) {
        return Mono.fromRunnable(() -> attempts.finish(context.admission, status, context.httpStatus,
                context.inspector == null ? null : context.inspector.usage(), context.rejected))
                .subscribeOn(databaseScheduler).then();
    }

    private String validate(byte[] body) {
        try {
            JsonNode json = mapper.readTree(body);
            if (json == null || !json.isObject() || !json.path("model").isTextual() || json.path("model").asText().isBlank()
                    || json.path("model").asText().length() > 200 || !json.has("input")) {
                throw new ApiException(400, "INVALID_RESPONSES_REQUEST", "A model and input are required");
            }
            if (json.path("background").asBoolean(false) || json.hasNonNull("previous_response_id")) {
                throw new ApiException(400, "UNSUPPORTED_RESPONSE_MODE", "Use foreground responses with inline conversation input");
            }
            return json.path("model").asText();
        } catch (IOException exception) {
            throw new ApiException(400, "INVALID_JSON", "Request body must be valid JSON");
        }
    }

    private enum Outcome { DELIVERED, RETRY }

    private static final class AttemptContext {
        private final AttemptRepository.Admission admission;
        private UsageInspector inspector;
        private String status = "TRANSPORT_ERROR";
        private Integer httpStatus;
        private boolean rejected;

        private AttemptContext(AttemptRepository.Admission admission) {
            this.admission = admission;
        }
    }
}
