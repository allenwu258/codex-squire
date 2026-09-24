package dev.squire.usage;

import dev.squire.persistence.Database;
import dev.squire.provider.Provider;
import dev.squire.provider.ProviderRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public final class AttemptRepository {
    private final Database database;
    private final ProviderRepository providers;
    private final Clock clock;

    public AttemptRepository(Database database, ProviderRepository providers, Clock clock) {
        this.database = database;
        this.providers = providers;
        this.clock = clock;
    }

    public record Admission(String id, Provider provider, Instant startedAt) { }

    public record Attempt(String id, String requestId, String providerId, String providerName, String model,
                          String startedAt, String endedAt, String status, Integer httpStatus,
                          long inputTokens, long outputTokens, long cachedTokens, long chargedTokens,
                          String usageState, String upstreamOutcome, String deliveryOutcome, long latencyMs) { }

    public record Settlement(String status, Integer httpStatus, Usage usage, boolean rejected,
                             String upstreamOutcome, String deliveryOutcome) { }

    public record ProviderUsage(String providerId, long requests, long inputTokens, long outputTokens,
                                long cachedTokens, long chargedTokens, long inFlight, long uncertainRequests) { }

    public record Statistics(String day, long requests, long successes, long failures, long inFlight,
                             long inputTokens, long outputTokens, long cachedTokens, long chargedTokens,
                             long uncertainRequests, List<ProviderUsage> providers) { }

    public Admission admit(String providerId, String schemeId, String requestId, String model) {
        return database.transaction(connection -> {
            Provider provider = providers.find(connection, providerId);
            if (!provider.enabled()) {
                return null;
            }
            String day = LocalDate.now(clock).toString();
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
                        return null;
                    }
                }
            }
            String id = UUID.randomUUID().toString();
            Instant now = clock.instant();
            try (var statement = connection.prepareStatement("INSERT INTO attempts "
                    + "(id,request_id,provider_id,provider_name,scheme_id,model,started_at,day,status,reserved_tokens,charged_tokens,usage_state) "
                    + "VALUES(?,?,?,?,?,?,?,?,'IN_FLIGHT',?,?,'RESERVED')")) {
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
                statement.executeUpdate();
            }
            return new Admission(id, provider, now);
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
            try (var statement = connection.prepareStatement("UPDATE attempts SET status=?,http_status=?,ended_at=?,"
                    + "charged_tokens=?,input_tokens=?,output_tokens=?,cached_tokens=?,usage_state=?,"
                    + "upstream_outcome=?,delivery_outcome=?,latency_ms=? "
                    + "WHERE id=? AND status='IN_FLIGHT'")) {
                statement.setString(1, settlement.status());
                statement.setObject(2, settlement.httpStatus());
                statement.setString(3, clock.instant().toString());
                statement.setLong(4, charged);
                statement.setLong(5, usage == null ? 0 : usage.inputTokens());
                statement.setLong(6, usage == null ? 0 : usage.outputTokens());
                statement.setLong(7, usage == null ? 0 : usage.cachedTokens());
                statement.setString(8, state);
                statement.setString(9, settlement.upstreamOutcome());
                statement.setString(10, settlement.deliveryOutcome());
                statement.setLong(11, Math.max(0, Duration.between(admission.startedAt(), clock.instant()).toMillis()));
                statement.setString(12, admission.id());
                statement.executeUpdate();
            }
            return null;
        });
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
            long requests = 0;
            long input = 0;
            long output = 0;
            long cached = 0;
            long charged = 0;
            long inFlight = 0;
            long uncertain = 0;
            long successes = 0;
            long failures = 0;
            try (var statement = connection.prepareStatement("SELECT provider_id,COUNT(*),SUM(input_tokens),SUM(output_tokens),"
                    + "SUM(cached_tokens),SUM(charged_tokens),SUM(status='IN_FLIGHT'),"
                    + "SUM(usage_state='RESERVED' AND status!='IN_FLIGHT'),SUM(status='SUCCEEDED'),"
                    + "SUM(status NOT IN ('IN_FLIGHT','SUCCEEDED')) FROM attempts WHERE day=? GROUP BY provider_id")) {
                statement.setString(1, day);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        ProviderUsage row = new ProviderUsage(result.getString(1), result.getLong(2), result.getLong(3),
                                result.getLong(4), result.getLong(5), result.getLong(6), result.getLong(7), result.getLong(8));
                        rows.add(row);
                        requests += row.requests();
                        input += row.inputTokens();
                        output += row.outputTokens();
                        cached += row.cachedTokens();
                        charged += row.chargedTokens();
                        inFlight += row.inFlight();
                        uncertain += row.uncertainRequests();
                        successes += result.getLong(9);
                        failures += result.getLong(10);
                    }
                }
            }
            return new Statistics(day, requests, successes, failures, inFlight, input, output, cached, charged, uncertain, List.copyOf(rows));
        });
    }

    private Attempt map(ResultSet row) throws SQLException {
        return new Attempt(row.getString("id"), row.getString("request_id"), row.getString("provider_id"),
                row.getString("provider_name"), row.getString("model"), row.getString("started_at"), row.getString("ended_at"),
                row.getString("status"), (Integer) row.getObject("http_status"), row.getLong("input_tokens"),
                row.getLong("output_tokens"), row.getLong("cached_tokens"), row.getLong("charged_tokens"),
                row.getString("usage_state"), row.getString("upstream_outcome"), row.getString("delivery_outcome"),
                row.getLong("latency_ms"));
    }
}
