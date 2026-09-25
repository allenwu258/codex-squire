package dev.squire.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.squire.api.ApiException;
import dev.squire.security.LocalSecrets;
import dev.squire.usage.AttemptRepository;
import dev.squire.usage.GatewayRequestRepository;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;
import reactor.netty.http.client.HttpClient;

@Service
public final class GatewayService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayService.class);
    private static final List<String> REQUEST_HEADERS = List.of("OpenAI-Beta", "originator", "version",
            "session_id", "conversation_id", "x-codex-turn-state", "x-codex-turn-metadata");
    private static final List<String> RESPONSE_HEADERS = List.of("x-request-id", "openai-processing-ms", "retry-after", "x-codex-turn-state");
    private final RoutingService routing;
    private final OutcomeResolver outcomes;
    private final AttemptRepository attempts;
    private final GatewayRequestRepository requests;
    private final LocalSecrets secrets;
    private final ObjectMapper mapper;
    private final Scheduler databaseScheduler;
    private final WebClient client;
    private final Duration totalTimeout;

    public GatewayService(RoutingService routing, OutcomeResolver outcomes, AttemptRepository attempts,
                          GatewayRequestRepository requests, LocalSecrets secrets, ObjectMapper mapper,
                          Scheduler databaseScheduler, @Value("${squire.upstream-idle-seconds}") int idleSeconds,
                          @Value("${squire.upstream-total-seconds}") int totalSeconds) {
        this.routing = routing;
        this.outcomes = outcomes;
        this.attempts = attempts;
        this.requests = requests;
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
                .flatMap(route -> Mono.fromRunnable(() -> requests.start(requestId, model, streaming(body), route.schemeId()))
                        .subscribeOn(databaseScheduler)
                        .then(next(route, Set.of(), requestId, model, body, exchange)
                                .flatMap(outcome -> finishRequest(requestId, outcome.name()).thenReturn(outcome))
                                .onErrorResume(error -> finishRequest(requestId, errorCode(error))
                                        .then(Mono.error(error)))))
                .then()
                .onErrorMap(UpstreamTransportException.class, error -> error.apiException())
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        databaseScheduler.schedule(() -> {
                            try {
                                requests.finish(requestId, "CLIENT_CANCELLED", null);
                            } catch (RuntimeException error) {
                                LOGGER.error("Unable to finish cancelled gateway request {}", requestId, error);
                            }
                        });
                    }
                });
    }

    private Mono<Void> finishRequest(String requestId, String outcome) {
        return Mono.fromRunnable(() -> requests.finish(requestId, outcome, null)).subscribeOn(databaseScheduler)
                .onErrorResume(error -> {
                    LOGGER.error("Unable to finish gateway request {}: {}", requestId, error.getMessage());
                    return Mono.empty();
                }).then();
    }

    private String errorCode(Throwable error) {
        return error instanceof ApiException api ? api.code() : "FAILED";
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
            AttemptRepository.AdmissionDecision decision = routing.admit(route, providerId, requestId, model, body.length);
            if (decision.admission() == null && "QUOTA_REJECTED".equals(decision.rejectionCode())) {
                throw new ApiException(429, "QUOTA_EXCEEDED", "A quota rule rejected this request");
            }
            return decision.admission() == null ? null : new AttemptContext(decision.admission());
        }).subscribeOn(databaseScheduler).doOnDiscard(AttemptContext.class, context ->
                databaseScheduler.schedule(() -> {
                    OutcomeResolver.FailureOutcome outcome = outcomes.cancelled(context.upstreamOutcome);
                    attempts.finish(context.admission, new AttemptRepository.Settlement(outcome.status(), null, null, false,
                            outcome.upstreamOutcome(), outcome.deliveryOutcome()));
                }));
        return Mono.usingWhen(resource,
                context -> send(context, body, exchange, nextAttempted.size() < route.members().size()),
                this::settle,
                this::settleOnError,
                this::settleOnCancel)
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
                .timeout(totalTimeout)
                .onErrorMap(error -> !(error instanceof ApiException) && !(error instanceof UpstreamTransportException),
                        error -> new UpstreamTransportException(error));
    }

    private Mono<Outcome> relay(AttemptContext context, ClientResponse upstream, ServerWebExchange exchange, boolean hasNext) {
        int status = upstream.statusCode().value();
        context.httpStatus = status;
        context.upstreamRequestId = upstream.headers().asHttpHeaders().getFirst("x-request-id");
        context.rejected = status >= 300 && status < 500;
        // Only explicit rate rejection is replayable by default. Other failures may have executed work.
        if (status == 429 && hasNext) {
            OutcomeResolver.ResponseOutcome outcome = outcomes.rateLimited();
            context.status = outcome.status();
            context.upstreamOutcome = outcome.upstreamOutcome();
            context.retryAfterSeconds = parseRetryAfter(upstream.headers().asHttpHeaders().getFirst("Retry-After"));
            return upstream.releaseBody().thenReturn(Outcome.RETRY);
        }
        if (status >= 300 && status < 400) {
            OutcomeResolver.ResponseOutcome outcome = outcomes.redirect();
            context.status = outcome.status();
            context.upstreamOutcome = outcome.upstreamOutcome();
            context.errorCode = "UPSTREAM_REDIRECT";
            context.failureStage = "RESPONSE_HEADERS";
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
            if (context.firstByteAt == null) {
                context.firstByteAt = Instant.now().toString();
            }
            context.responseBytes += buffer.readableByteCount();
            byte[] bytes = new byte[buffer.readableByteCount()];
            int position = buffer.readPosition();
            buffer.read(bytes);
            buffer.readPosition(position);
            context.inspector.accept(bytes);
            if (context.inspector.observedTerminalStatus() != null) {
                OutcomeResolver.ResponseOutcome observed = outcomes.response(status, context.inspector.observedTerminalStatus());
                context.status = observed.status();
                context.upstreamOutcome = observed.upstreamOutcome();
            }
        }).doOnComplete(() -> {
            context.inspector.end();
            OutcomeResolver.ResponseOutcome outcome = outcomes.response(status, context.inspector.resultStatus());
            context.upstreamOutcome = outcome.upstreamOutcome();
            context.status = outcome.status();
        });
        // Flush each upstream chunk; buffering the whole Flux breaks interactive SSE delivery.
        return exchange.getResponse().writeAndFlushWith(content.map(Mono::just))
                .then(Mono.fromSupplier(() -> {
                    context.deliveryOutcome = "DELIVERED";
                    return Outcome.DELIVERED;
                }))
                .doOnError(error -> context.deliveryOutcome = "WRITE_FAILED");
    }

    private Mono<Void> settle(AttemptContext context) {
        return settleWithRetry(context).onErrorMap(error -> new ApiException(500, "DATABASE_SETTLEMENT_ERROR",
                "Provider attempt could not be settled in local storage"));
    }

    private Mono<Void> settleOnError(AttemptContext context, Throwable error) {
        if (context.errorCode == null) {
            context.errorCode = error instanceof ApiException api ? api.code() : "UPSTREAM_CONNECTION_ERROR";
            context.failureStage = context.httpStatus == null ? "CONNECT" : "RESPONSE_BODY";
        }
        OutcomeResolver.FailureOutcome outcome = outcomes.transport(context.status, context.upstreamOutcome,
                !context.deliveryOutcome.equals("PENDING"));
        context.status = outcome.status();
        context.upstreamOutcome = outcome.upstreamOutcome();
        context.deliveryOutcome = outcome.deliveryOutcome();
        return settleWithRetry(context).onErrorResume(settlementError -> {
            LOGGER.error("Unable to settle failed attempt {}", context.admission.id(), settlementError);
            error.addSuppressed(settlementError);
            return Mono.empty();
        });
    }

    private Mono<Void> settleOnCancel(AttemptContext context) {
        context.errorCode = "CLIENT_CANCELLED";
        context.failureStage = context.httpStatus == null ? "CONNECT" : "DELIVERY";
        OutcomeResolver.FailureOutcome outcome = outcomes.cancelled(context.upstreamOutcome);
        context.status = outcome.status();
        context.upstreamOutcome = outcome.upstreamOutcome();
        context.deliveryOutcome = outcome.deliveryOutcome();
        return settleWithRetry(context).onErrorResume(error -> {
            LOGGER.error("Unable to settle cancelled attempt {}", context.admission.id(), error);
            return Mono.empty();
        });
    }

    private Mono<Void> settleWith(AttemptContext context, String status) {
        context.status = status;
        return Mono.fromRunnable(() -> attempts.finish(context.admission,
                        new AttemptRepository.Settlement(context.status, context.httpStatus,
                                context.inspector == null ? null : context.inspector.usage(), context.rejected,
                                context.upstreamOutcome, context.deliveryOutcome, context.retryAfterSeconds,
                                context.firstByteAt, context.responseBytes, context.errorCode, context.failureStage,
                                context.upstreamRequestId)))
                .subscribeOn(databaseScheduler).then();
    }

    private Long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Math.min(3600, Math.max(1, Long.parseLong(value.trim())));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Mono<Void> settleWithRetry(AttemptContext context) {
        return settleWith(context, context.status).retryWhen(Retry.backoff(3, Duration.ofMillis(50))
                .filter(error -> error instanceof ApiException api && "DATABASE_BUSY".equals(api.code())));
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

    private boolean streaming(byte[] body) {
        try {
            return mapper.readTree(body).path("stream").asBoolean(false);
        } catch (IOException exception) {
            return false;
        }
    }

    private enum Outcome { DELIVERED, RETRY }

    private static final class AttemptContext {
        private final AttemptRepository.Admission admission;
        private UsageInspector inspector;
        private String status = "TRANSPORT_ERROR";
        private Integer httpStatus;
        private boolean rejected;
        private String upstreamOutcome = "UNKNOWN";
        private String deliveryOutcome = "PENDING";
        private Long retryAfterSeconds;
        private String firstByteAt;
        private long responseBytes;
        private String errorCode;
        private String failureStage;
        private String upstreamRequestId;

        private AttemptContext(AttemptRepository.Admission admission) {
            this.admission = admission;
        }
    }

    private static final class UpstreamTransportException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UpstreamTransportException(Throwable cause) {
            super(cause);
        }

        private ApiException apiException() {
            boolean timeout = getCause() instanceof TimeoutException;
            return new ApiException(502, timeout ? "UPSTREAM_TIMEOUT" : "UPSTREAM_CONNECTION_ERROR",
                    timeout ? "The upstream provider did not respond before the deadline"
                            : "Upstream connection failed; the request was not replayed");
        }
    }
}

