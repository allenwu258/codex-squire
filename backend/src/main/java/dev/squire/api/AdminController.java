package dev.squire.api;

import dev.squire.provider.Provider;
import dev.squire.provider.ProviderInput;
import dev.squire.provider.ProviderRepository;
import dev.squire.provider.ProviderRuntimeRepository;
import dev.squire.provider.ProviderRuntimeState;
import dev.squire.persistence.Database;
import dev.squire.scheme.Scheme;
import dev.squire.scheme.SchemeInput;
import dev.squire.scheme.SchemeRepository;
import dev.squire.security.LocalSecrets;
import dev.squire.usage.AttemptRepository;
import dev.squire.usage.QuotaRule;
import dev.squire.usage.QuotaRuleInput;
import dev.squire.usage.QuotaRuleRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final ProviderRepository providers;
    private final SchemeRepository schemes;
    private final AttemptRepository attempts;
    private final Database database;
    private final ProviderRuntimeRepository runtime;
    private final QuotaRuleRepository quotaRules;
    private final LocalSecrets secrets;
    private final Scheduler scheduler;
    private final String dataDirectory;

    public AdminController(ProviderRepository providers, SchemeRepository schemes, AttemptRepository attempts,
                           Database database, LocalSecrets secrets, Scheduler databaseScheduler,
                           @Value("${squire.data-dir}") String dataDirectory) {
        this.providers = providers;
        this.schemes = schemes;
        this.attempts = attempts;
        this.database = database;
        runtime = new ProviderRuntimeRepository();
        quotaRules = new QuotaRuleRepository(database);
        this.secrets = secrets;
        scheduler = databaseScheduler;
        this.dataDirectory = dataDirectory;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "version", "0.1.0");
    }

    @GetMapping("/providers")
    public Mono<List<Provider>> providers() {
        return Mono.fromCallable(providers::list).subscribeOn(scheduler);
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Provider> createProvider(@Valid @RequestBody ProviderInput input) {
        return Mono.fromCallable(() -> providers.save(null, input)).subscribeOn(scheduler);
    }

    @PutMapping("/providers/{id}")
    public Mono<Provider> updateProvider(@PathVariable String id, @Valid @RequestBody ProviderInput input) {
        return Mono.fromCallable(() -> providers.save(id, input)).subscribeOn(scheduler);
    }

    @DeleteMapping("/providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteProvider(@PathVariable String id) {
        return Mono.fromRunnable(() -> providers.delete(id)).subscribeOn(scheduler).then();
    }

    @GetMapping("/providers/{id}/quota-rules")
    public Mono<List<QuotaRule>> quotaRules(@PathVariable String id) {
        return Mono.fromCallable(() -> quotaRules.list(id)).subscribeOn(scheduler);
    }

    @PutMapping("/providers/{id}/quota-rules")
    public Mono<List<QuotaRule>> saveQuotaRules(@PathVariable String id, @Valid @RequestBody List<QuotaRuleInput> input) {
        return Mono.fromCallable(() -> quotaRules.save(id, input)).subscribeOn(scheduler);
    }

    @GetMapping("/schemes")
    public Mono<List<Scheme>> schemes() {
        return Mono.fromCallable(schemes::list).subscribeOn(scheduler);
    }

    @PostMapping("/schemes")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Scheme> createScheme(@Valid @RequestBody SchemeInput input) {
        return Mono.fromCallable(() -> schemes.save(null, input)).subscribeOn(scheduler);
    }

    @PutMapping("/schemes/{id}")
    public Mono<Scheme> updateScheme(@PathVariable String id, @Valid @RequestBody SchemeInput input) {
        return Mono.fromCallable(() -> schemes.save(id, input)).subscribeOn(scheduler);
    }

    @PostMapping("/schemes/{id}/activate")
    public Mono<Scheme> activate(@PathVariable String id) {
        return Mono.fromCallable(() -> schemes.activate(id)).subscribeOn(scheduler);
    }

    @DeleteMapping("/schemes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteScheme(@PathVariable String id) {
        return Mono.fromRunnable(() -> schemes.delete(id)).subscribeOn(scheduler).then();
    }

    @GetMapping("/statistics")
    public Mono<AttemptRepository.Statistics> statistics() {
        return Mono.fromCallable(attempts::statistics).subscribeOn(scheduler);
    }

    @GetMapping("/provider-runtime")
    public Mono<List<ProviderRuntimeState>> providerRuntime() {
        return Mono.fromCallable(() -> database.read(runtime::list)).subscribeOn(scheduler);
    }

    @GetMapping("/attempts")
    public Mono<List<AttemptRepository.Attempt>> attempts(@RequestParam(defaultValue = "50") int limit,
                                                         @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 200 || offset < 0 || offset > 1000000) {
            throw new ApiException(400, "INVALID_PAGE", "Limit must be 1..200 and offset 0..1000000");
        }
        return Mono.fromCallable(() -> attempts.list(limit, offset)).subscribeOn(scheduler);
    }

    @GetMapping("/connection")
    public Map<String, String> connection(ServerWebExchange exchange) {
        int port = exchange.getRequest().getLocalAddress().getPort();
        return Map.of("baseUrl", "http://127.0.0.1:" + port + "/v1", "gatewayToken", secrets.gatewayToken(),
                "dataDirectory", dataDirectory, "quotaTimeZone", "UTC");
    }
}
