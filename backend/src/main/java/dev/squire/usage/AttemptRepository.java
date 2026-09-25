package dev.squire.usage;

import dev.squire.persistence.Database;
import dev.squire.provider.Provider;
import dev.squire.provider.ProviderRepository;
import dev.squire.provider.ProviderRuntimeRepository;
import dev.squire.provider.ProviderRuntimeState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public final class AttemptRepository {
    private final Database database;
    private final ProviderRepository providers;
    private final ProviderRuntimeRepository runtime;
    private final QuotaRuleRepository quotas;
    private final Clock clock;

    public AttemptRepository(Database database, ProviderRepository providers, Clock clock) {
        this.database = database;
        this.providers = providers;
        this.runtime = new ProviderRuntimeRepository();
        this.quotas = new QuotaRuleRepository(database);
        this.clock = clock;
    }

    public record Admission(String id, Provider provider, Instant startedAt) { }

    public record AdmissionDecision(Admission admission, String rejectionCode) { }

    public record Attempt(String id, String requestId, String providerId, String providerName, String model,
                          String startedAt, String endedAt, String status, Integer httpStatus,
                          long inputTokens, long outputTokens, long cachedTokens, long chargedTokens,
                          String usageState, String upstreamOutcome, String deliveryOutcome, long latencyMs,
                          int attemptIndex, String firstByteAt, long requestBytes, long responseBytes,
                          String errorCode, String failureStage, String skipReason, String upstreamRequestId) { }

    public record Settlement(String status, Integer httpStatus, Usage usage, boolean rejected,
                             String upstreamOutcome, String deliveryOutcome, Long retryAfterSeconds,
                             String firstByteAt, long responseBytes, String errorCode, String failureStage,
                             String upstreamRequestId) {
        public Settlement(String status, Integer httpStatus, Usage usage, boolean rejected,
                          String upstreamOutcome, String deliveryOutcome) {
            this(status, httpStatus, usage, rejected, upstreamOutcome, deliveryOutcome, null,
                    null, 0, null, null, null);
        }

        public Settlement(String status, Integer httpStatus, Usage usage, boolean rejected,
                          String upstreamOutcome, String deliveryOutcome, Long retryAfterSeconds) {
            this(status, httpStatus, usage, rejected, upstreamOutcome, deliveryOutcome, retryAfterSeconds,
                    null, 0, null, null, null);
        }
    }

    public record ProviderUsage(String providerId, long requests, long inputTokens, long outputTokens,
                                long cachedTokens, long chargedTokens, long inFlight, long uncertainRequests) { }

    public record Statistics(String day, long requests, long attempts, long successes, long failures, long inFlight,
                             long inputTokens, long outputTokens, long cachedTokens, long chargedTokens,
                             long uncertainRequests, List<ProviderUsage> providers) { }

    public Admission admit(String providerId, String schemeId, String requestId, String model) {
        return admit(providerId, schemeId, requestId, model, 0);
    }

    public Admission admit(String providerId, String schemeId, String requestId, String model, long requestBytes) {
        return tryAdmit(providerId, schemeId, requestId, model, requestBytes).admission();
    }

    public AdmissionDecision tryAdmit(String providerId, String schemeId, String requestId, String model, long requestBytes) {
        return database.transaction(connection -> {
            Provider provider = providers.find(connection, providerId);
            if (!provider.enabled()) {
                return new AdmissionDecision(null, "PROVIDER_DISABLED");
            }
            String day = LocalDate.now(clock).toString();
            Instant now = clock.instant();
            ProviderRuntimeState runtimeState = runtime.find(connection, providerId);
            if (runtimeState.inCooldown(now.toString())) {
                return new AdmissionDecision(null, "PROVIDER_COOLDOWN");
            }
            QuotaRuleRepository.Decision quota = quotas.evaluate(connection, providerId, now);
            if (!quota.allowed()) {
                if ("ENTER_COOLDOWN".equals(quota.action())) {
                    runtime.enterCooldown(connection, providerId, now, quota.reason());
                }
                String code = "REJECT_REQUEST".equals(quota.action()) ? "QUOTA_REJECTED" : "QUOTA_EXHAUSTED";
                return new AdmissionDecision(null, code);
            }
            // Admission and reservation share the same SQLite write transaction.
            try (var statement = connection.prepareStatement("SELECT COUNT(*),COALESCE(SUM(charged_tokens),0),"
                    + "(SELECT COUNT(*) FROM attempts WHERE provider_id=? AND status='IN_FLIGHT') "
                    + "FROM attempts WHERE provider_id=? AND day=?")) {
                statement.setString(1, providerId);
                statement.setString(2, providerId);
                statement.setString(3, day);
                try (var result = statement.executeQuery()) {
                    result.next();
                    long requests = result.getLong(1);
                    long charged = result.getLong(2);
                    long inFlight = result.getLong(3);
                    if (inFlight >= provider.maxConcurrency()
                            || (provider.dailyRequestLimit() > 0 && requests >= provider.dailyRequestLimit())
                            || (provider.dailyTokenLimit() > 0 && charged + provider.tokenReservation() > provider.dailyTokenLimit())) {
                        return new AdmissionDecision(null, "PROVIDER_QUOTA_EXHAUSTED");
                    }
                }
            }
            int attemptIndex;
            try (var statement = connection.prepareStatement("SELECT COALESCE(MAX(attempt_index),0)+1 FROM attempts WHERE request_id=?")) {
                statement.setString(1, requestId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    attemptIndex = result.getInt(1);
                }
            }
            String id = UUID.randomUUID().toString();
            try (var statement = connection.prepareStatement("INSERT INTO attempts "
                    + "(id,request_id,provider_id,provider_name,scheme_id,model,started_at,day,status,reserved_tokens,"
                    + "charged_tokens,usage_state,attempt_index,request_bytes) "
                    + "VALUES(?,?,?,?,?,?,?,?,'IN_FLIGHT',?,?,'RESERVED',?,?)")) {
                statement.setString(1, id);
                statement.setString(2, requestId);
                statement.setString(3, providerId);
                statement.setString(4, provider.name());
                statement.setString(5, schemeId);
                statement.setString(6, model);
                statement.setString(7, now.toString());
                statement.setString(8, day);
                statement.setLong(9, provider.tokenReservation());
                statement.setLong(10, provider.tokenReservation());
                statement.setInt(11, attemptIndex);
                statement.setLong(12, Math.max(0, requestBytes));
                statement.executeUpdate();
            }
            return new AdmissionDecision(new Admission(id, provider, now), null);
        });
    }

    public void finish(Admission admission, String status, Integer httpStatus, Usage usage, boolean rejected) {
        String upstream = switch (status) {
            case "SUCCEEDED" -> "COMPLETED";
            case "INCOMPLETE" -> "INCOMPLETE";
            case "FAILED", "UPSTREAM_ERROR", "TRANSPORT_ERROR", "REDIRECT_REJECTED" -> "FAILED";
            case "RATE_LIMITED" -> "REJECTED";
            default -> "UNKNOWN";
        };
        String delivery = switch (status) {
            case "CANCELLED" -> "CLIENT_CANCELLED";
            case "SUCCEEDED", "INCOMPLETE", "FAILED", "UPSTREAM_ERROR" -> "DELIVERED";
            default -> "PENDING";
        };
        finish(admission, new Settlement(status, httpStatus, usage, rejected, upstream, delivery));
    }

    public void finish(Admission admission, Settlement settlement) {
        database.transaction(connection -> {
            Usage usage = settlement.usage();
            long charged = usage != null ? usage.totalTokens()
                    : (settlement.rejected() ? 0 : admission.provider().tokenReservation());
            String state = usage != null ? "REPORTED" : (settlement.rejected() ? "NOT_APPLICABLE" : "RESERVED");
            Instant endedAt = clock.instant();
            try (var statement = connection.prepareStatement("UPDATE attempts SET status=?,http_status=?,ended_at=?,"
                    + "charged_tokens=?,input_tokens=?,output_tokens=?,cached_tokens=?,usage_state=?,"
                    + "upstream_outcome=?,delivery_outcome=?,latency_ms=?,first_byte_at=?,response_bytes=?,error_code=?,"
                    + "failure_stage=?,upstream_request_id=? "
                    + "WHERE id=? AND status='IN_FLIGHT'")) {
                statement.setString(1, settlement.status());
                statement.setObject(2, settlement.httpStatus());
                statement.setString(3, endedAt.toString());
                statement.setLong(4, charged);
                statement.setLong(5, usage == null ? 0 : usage.inputTokens());
                statement.setLong(6, usage == null ? 0 : usage.outputTokens());
                statement.setLong(7, usage == null ? 0 : usage.cachedTokens());
                statement.setString(8, state);
                statement.setString(9, settlement.upstreamOutcome());
                statement.setString(10, settlement.deliveryOutcome());
                statement.setLong(11, Math.max(0, Duration.between(admission.startedAt(), endedAt).toMillis()));
                statement.setString(12, settlement.firstByteAt());
                statement.setLong(13, Math.max(0, settlement.responseBytes()));
                statement.setString(14, settlement.errorCode());
                statement.setString(15, settlement.failureStage());
                statement.setString(16, settlement.upstreamRequestId());
                statement.setString(17, admission.id());
                if (statement.executeUpdate() > 0) {
                    if ("COMPLETED".equals(settlement.upstreamOutcome())
                            || "INCOMPLETE".equals(settlement.upstreamOutcome()) && settlement.errorCode() == null) {
                        runtime.markSuccess(connection, admission.provider().id(), endedAt,
                                Math.max(0, Duration.between(admission.startedAt(), endedAt).toMillis()), settlement.httpStatus());
                    } else if (runtimeFailure(settlement)) {
                        ProviderRuntimeState current = runtime.find(connection, admission.provider().id());
                        runtime.markFailure(connection, admission.provider().id(), endedAt, runtimeErrorCode(settlement),
                                settlement.status(), settlement.httpStatus(), settlement.retryAfterSeconds(),
                                current.consecutiveFailures());
                    }
                }
            }
            return null;
        });
    }

    private boolean runtimeFailure(Settlement settlement) {
        return "RATE_LIMITED".equals(settlement.status()) || "TRANSPORT_ERROR".equals(settlement.status())
                || "INCOMPLETE".equals(settlement.status()) && settlement.errorCode() != null
                || "FAILED".equals(settlement.status())
                || settlement.httpStatus() != null && (settlement.httpStatus() == 401
                        || settlement.httpStatus() == 403 || settlement.httpStatus() == 429)
                || settlement.httpStatus() != null && settlement.httpStatus() >= 500;
    }

    private String runtimeErrorCode(Settlement settlement) {
        if ("RATE_LIMITED".equals(settlement.status()) || Integer.valueOf(429).equals(settlement.httpStatus())) {
            return "RATE_LIMITED";
        }
        if (settlement.httpStatus() != null && (settlement.httpStatus() == 401 || settlement.httpStatus() == 403)) {
            return "AUTH_FAILED";
        }
        if (settlement.httpStatus() != null && settlement.httpStatus() >= 500) {
            return "UPSTREAM_5XX";
        }
        if ("INCOMPLETE".equals(settlement.status())) {
            return "UPSTREAM_INCOMPLETE";
        }
        return "UPSTREAM_CONNECTION_ERROR";
    }

    public List<Attempt> list(int limit, int offset) {
        return database.read(connection -> {
            List<Attempt> attempts = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM attempts ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?")) {
                statement.setInt(1, limit);
                statement.setInt(2, offset);
                try (var row = statement.executeQuery()) {
                    while (row.next()) {
                        attempts.add(map(row));
                    }
                }
            }
            return attempts;
        });
    }

    public Statistics statistics() {
        String day = LocalDate.now(clock).toString();
        return database.read(connection -> {
            List<ProviderUsage> rows = new ArrayList<>();
            Map<String, Long> activeByProvider = new HashMap<>();
            try (var active = connection.prepareStatement("SELECT provider_id,COUNT(*) FROM attempts "
                    + "WHERE status='IN_FLIGHT' GROUP BY provider_id")) {
                try (var result = active.executeQuery()) {
                    while (result.next()) {
                        activeByProvider.put(result.getString(1), result.getLong(2));
                    }
                }
            }
            long attemptsCount = 0;
            long input = 0;
            long output = 0;
            long cached = 0;
            long charged = 0;
            long inFlight = 0;
            long uncertain = 0;
            long successes = 0;
            long failures = 0;
            var providersWithTodayRows = new HashSet<String>();
            try (var statement = connection.prepareStatement("SELECT a.provider_id,COUNT(*),SUM(a.input_tokens),SUM(a.output_tokens),"
                    + "SUM(a.cached_tokens),SUM(a.charged_tokens),"
                    + "SUM(a.usage_state='RESERVED' AND a.status!='IN_FLIGHT'),SUM(a.status='SUCCEEDED'),"
                    + "SUM(a.status NOT IN ('IN_FLIGHT','SUCCEEDED')) FROM attempts a WHERE a.day=? GROUP BY a.provider_id")) {
                statement.setString(1, day);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        ProviderUsage row = new ProviderUsage(result.getString(1), result.getLong(2), result.getLong(3),
                                result.getLong(4), result.getLong(5), result.getLong(6),
                                activeByProvider.getOrDefault(result.getString(1), 0L), result.getLong(7));
                        rows.add(row);
                        providersWithTodayRows.add(row.providerId());
                        attemptsCount += row.requests();
                        input += row.inputTokens();
                        output += row.outputTokens();
                        cached += row.cachedTokens();
                        charged += row.chargedTokens();
                        uncertain += row.uncertainRequests();
                        successes += result.getLong(8);
                        failures += result.getLong(9);
                    }
                }
            }
            activeByProvider.forEach((providerId, activeCount) -> {
                if (!providersWithTodayRows.contains(providerId)) {
                    rows.add(new ProviderUsage(providerId, 0, 0, 0, 0, 0, activeCount, 0));
                }
            });
            inFlight = activeByProvider.values().stream().mapToLong(Long::longValue).sum();
            long logicalRequests;
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM gateway_requests WHERE substr(received_at,1,10)=?")) {
                statement.setString(1, day);
                try (var result = statement.executeQuery()) {
                    result.next();
                    logicalRequests = result.getLong(1);
                }
            }
            return new Statistics(day, logicalRequests, attemptsCount, successes, failures, inFlight,
                    input, output, cached, charged, uncertain, List.copyOf(rows));
        });
    }

    private Attempt map(ResultSet row) throws SQLException {
        return new Attempt(row.getString("id"), row.getString("request_id"), row.getString("provider_id"),
                row.getString("provider_name"), row.getString("model"), row.getString("started_at"), row.getString("ended_at"),
                row.getString("status"), (Integer) row.getObject("http_status"), row.getLong("input_tokens"),
                row.getLong("output_tokens"), row.getLong("cached_tokens"), row.getLong("charged_tokens"),
                row.getString("usage_state"), row.getString("upstream_outcome"), row.getString("delivery_outcome"),
                row.getLong("latency_ms"), row.getInt("attempt_index"), row.getString("first_byte_at"),
                row.getLong("request_bytes"), row.getLong("response_bytes"), row.getString("error_code"),
                row.getString("failure_stage"), row.getString("skip_reason"), row.getString("upstream_request_id"));
    }
}
