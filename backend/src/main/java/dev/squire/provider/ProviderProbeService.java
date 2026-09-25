package dev.squire.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.squire.api.ApiException;
import dev.squire.gateway.UsageInspector;
import dev.squire.security.LocalSecrets;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public final class ProviderProbeService {
    private final ProviderRepository providers;
    private final LocalSecrets secrets;
    private final CapabilityRepository capabilities;
    private final ObjectMapper mapper;
    private final WebClient client = WebClient.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024)).build();
    private final Semaphore probeSlots = new Semaphore(2);

    public ProviderProbeService(ProviderRepository providers, LocalSecrets secrets, CapabilityRepository capabilities,
                                ObjectMapper mapper) {
        this.providers = providers;
        this.secrets = secrets;
        this.capabilities = capabilities;
        this.mapper = mapper;
    }

    public Mono<ProbeResult> probe(String providerId, String model) {
        if (model == null || model.isBlank() || model.length() > 200) {
            return Mono.error(new ApiException(400, "INVALID_MODEL", "A model is required for a provider probe"));
        }
        return Mono.defer(() -> {
            if (!probeSlots.tryAcquire()) {
                return Mono.error(new ApiException(429, "PROBE_BUSY", "Provider probe capacity is currently full"));
            }
            return Mono.fromCallable(() -> providers.find(providerId)).flatMap(provider -> {
            String endpoint = provider.baseUrl() + (URI.create(provider.baseUrl()).getPath().isEmpty()
                    ? "/v1/responses" : "/responses");
            return request(provider, endpoint, model, false)
                    .flatMap(json -> request(provider, endpoint, model, true)
                            .map(sse -> persist(provider.id(), model, json, sse)))
                    .onErrorResume(error -> {
                        ProbeResult failed = new ProbeResult(provider.id(), model, false, false, "UNKNOWN",
                                Instant.now().toString(), error.getMessage());
                        capabilities.save(new ProviderCapability(provider.id(), model, false, false, "UNKNOWN",
                                failed.lastProbeAt(), failed.error()));
                        return Mono.just(failed);
                    });
            }).doFinally(signal -> probeSlots.release());
        });
    }

    private Mono<Observation> request(Provider provider, String endpoint, String model, boolean stream) {
        Map<String, Object> body = Map.of("model", model, "input", "Reply with OK.", "stream", stream,
                "store", false, "max_output_tokens", 1);
        return client.post().uri(endpoint).headers(headers -> {
            headers.setBearerAuth(secrets.decrypt(provider.encryptedKey()));
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
            headers.set("X-Squire-Probe", "true");
        }).bodyValue(body).exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                .map(value -> {
                    boolean eventStream = response.headers().contentType()
                            .map(type -> MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)).orElse(false);
                    UsageInspector inspector = new UsageInspector(mapper, eventStream);
                    inspector.accept(value.getBytes(StandardCharsets.UTF_8));
                    inspector.end();
                    return new Observation(response.statusCode().value(), inspector.resultStatus(),
                            inspector.usage() != null, eventStream);
                })).timeout(Duration.ofSeconds(30));
    }

    private ProbeResult persist(String providerId, String model, Observation json, Observation sse) {
        ProbeResult result = new ProbeResult(providerId, model, json.status() < 400 && !json.eventStream()
                && "SUCCEEDED".equals(json.terminalStatus()), sse.status() < 400 && sse.eventStream()
                && "SUCCEEDED".equals(sse.terminalStatus()),
                json.hasUsage() || sse.hasUsage() ? "REPORTED" : "NONE", Instant.now().toString(),
                json.status() >= 400 ? "Non-success HTTP status from non-stream probe" : null);
        capabilities.save(new ProviderCapability(result.providerId(), result.model(), result.supportsResponses(),
                result.supportsSse(), result.usageSupport(), result.lastProbeAt(), result.error()));
        return result;
    }

    private record Observation(int status, String terminalStatus, boolean hasUsage, boolean eventStream) { }

    public record ProbeResult(String providerId, String model, boolean supportsResponses, boolean supportsSse,
                              String usageSupport, String lastProbeAt, String error) { }
}
