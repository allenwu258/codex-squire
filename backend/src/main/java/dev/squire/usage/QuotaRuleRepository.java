package dev.squire.usage;

import dev.squire.api.ApiException;
import dev.squire.persistence.Database;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public final class QuotaRuleRepository {
    private final Database database;

    public QuotaRuleRepository(Database database) {
        this.database = database;
    }

    public List<QuotaRule> list(String providerId) {
        return database.read(connection -> list(connection, providerId));
    }

    public List<QuotaRule> save(String providerId, List<QuotaRuleInput> inputs) {
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("SELECT 1 FROM providers WHERE id=?")) {
                statement.setString(1, providerId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new ApiException(404, "PROVIDER_NOT_FOUND", "Provider not found");
                    }
                }
            }
            try (var delete = connection.prepareStatement("DELETE FROM quota_rules WHERE provider_id=?")) {
                delete.setString(1, providerId);
                delete.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO quota_rules "
                    + "(id,provider_id,metric,window_type,window_size,timezone,threshold,action,enabled,priority) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                for (QuotaRuleInput input : inputs) {
                    if (input.windowSize() > 100000 || input.threshold() > 1000000000000L) {
                        throw new ApiException(400, "INVALID_QUOTA_RULE", "Quota rule values are outside the supported range");
                    }
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, providerId);
                    insert.setString(3, input.metric().name());
                    insert.setString(4, input.windowType().name());
                    insert.setInt(5, input.windowSize());
                    insert.setString(6, input.normalizedTimezone());
                    insert.setLong(7, input.threshold());
                    insert.setString(8, input.action().name());
                    insert.setBoolean(9, input.enabled());
                    insert.setInt(10, input.priority());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return list(connection, providerId);
        });
    }

    public Decision evaluate(Connection connection, String providerId, Instant now) throws SQLException {
        for (QuotaRule rule : list(connection, providerId)) {
            long current = usage(connection, rule, now);
            if (current >= rule.threshold()) {
                return new Decision(false, rule.action().name(), rule.action().name() + "_" + rule.metric().name());
            }
        }
        return new Decision(true, null, null);
    }

    private long usage(Connection connection, QuotaRule rule, Instant now) throws SQLException {
        if (rule.metric() == QuotaRule.Metric.CONCURRENT_REQUESTS) {
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM attempts WHERE provider_id=? AND status='IN_FLIGHT'")) {
                statement.setString(1, rule.providerId());
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        }
        String expression = switch (rule.metric()) {
            case REQUEST_COUNT -> "COUNT(*)";
            case INPUT_TOKENS -> "COALESCE(SUM(input_tokens),0)";
            case OUTPUT_TOKENS -> "COALESCE(SUM(output_tokens),0)";
            case TOTAL_TOKENS -> "COALESCE(SUM(charged_tokens),0)";
            case CONCURRENT_REQUESTS -> throw new IllegalStateException("Concurrent usage is handled separately");
        };
        try (var statement = connection.prepareStatement("SELECT " + expression + " FROM attempts "
                + "WHERE provider_id=? AND started_at>=?")) {
            statement.setString(1, rule.providerId());
            statement.setString(2, rule.windowStart(now).toString());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private List<QuotaRule> list(Connection connection, String providerId) throws SQLException {
        List<QuotaRule> rules = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM quota_rules WHERE provider_id=? AND enabled=1 "
                + "ORDER BY priority DESC,id")) {
            statement.setString(1, providerId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    rules.add(map(result));
                }
            }
        }
        return List.copyOf(rules);
    }

    private QuotaRule map(ResultSet row) throws SQLException {
        return new QuotaRule(row.getString("id"), row.getString("provider_id"),
                QuotaRule.Metric.valueOf(row.getString("metric")), QuotaRule.WindowType.valueOf(row.getString("window_type")),
                row.getInt("window_size"), row.getString("timezone"), row.getLong("threshold"),
                QuotaRule.Action.valueOf(row.getString("action")), row.getBoolean("enabled"), row.getInt("priority"));
    }

    public record Decision(boolean allowed, String action, String reason) { }
}
