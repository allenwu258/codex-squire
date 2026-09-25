package dev.squire.provider;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Persists provider health independently from user managed provider configuration. */
public final class ProviderRuntimeRepository {
    public ProviderRuntimeState find(Connection connection, String providerId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM provider_runtime WHERE provider_id=?")) {
            statement.setString(1, providerId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    return new ProviderRuntimeState(providerId, "UNKNOWN", 0, null, null, null, null,
                            null, 0, null);
                }
                return new ProviderRuntimeState(providerId, row.getString("health"), row.getInt("consecutive_failures"),
                        row.getString("last_success_at"), row.getString("last_failure_at"), row.getString("last_error_code"),
                        row.getString("last_error_message"), row.getString("cooldown_until"), row.getLong("average_latency_ms"),
                        (Integer) row.getObject("last_response_status"));
            }
        }
    }

    public List<ProviderRuntimeState> list(Connection connection) throws SQLException {
        List<ProviderRuntimeState> values = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT p.id,r.health,r.consecutive_failures,r.last_success_at,"
                + "r.last_failure_at,r.last_error_code,r.last_error_message,r.cooldown_until,r.average_latency_ms,"
                + "r.last_response_status FROM providers p LEFT JOIN provider_runtime r ON r.provider_id=p.id ORDER BY p.created_at,p.id");
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(new ProviderRuntimeState(rows.getString(1),
                        rows.getString(2) == null ? "UNKNOWN" : rows.getString(2), rows.getInt(3), rows.getString(4),
                        rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getLong(9),
                        (Integer) rows.getObject(10)));
            }
        }
        return List.copyOf(values);
    }

    public void markSuccess(Connection connection, String providerId, Instant now, long latencyMs, Integer status)
            throws SQLException {
        upsert(connection, providerId, "READY", 0, now.toString(), null, null, null, null, latencyMs, status);
    }

    public void markFailure(Connection connection, String providerId, Instant now, String errorCode,
                            String message, Integer status, Long retryAfterSeconds, int previousFailures)
            throws SQLException {
        int failures = Math.min(previousFailures + 1, 30);
        long backoff = retryAfterSeconds == null
                ? Math.min(300, 5L * (1L << Math.min(failures - 1, 5))) : Math.min(3600, Math.max(1, retryAfterSeconds));
        String health = "RATE_LIMITED".equals(errorCode) ? "RATE_LIMITED"
                : "AUTH_FAILED".equals(errorCode) ? "AUTH_FAILED" : "UNHEALTHY";
        String cooldown = "AUTH_FAILED".equals(errorCode) ? null : now.plusSeconds(backoff).toString();
        upsert(connection, providerId, health, failures, null, now.toString(), errorCode, message,
                cooldown, 0, status);
    }

    public void clearCooldown(Connection connection, String providerId) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE provider_runtime SET health='READY',"
                + "consecutive_failures=0,cooldown_until=NULL,last_error_code=NULL,last_error_message=NULL "
                + "WHERE provider_id=?")) {
            statement.setString(1, providerId);
            statement.executeUpdate();
        }
    }

    public void enterCooldown(Connection connection, String providerId, Instant now, String reason) throws SQLException {
        upsert(connection, providerId, "QUOTA_EXHAUSTED", 0, null, now.toString(),
                "QUOTA_COOLDOWN", reason, now.plusSeconds(60).toString(), 0, null);
    }

    private void upsert(Connection connection, String providerId, String health, int failures, String successAt,
                        String failureAt, String errorCode, String message, String cooldown, long latency, Integer status)
            throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO provider_runtime "
                + "(provider_id,health,consecutive_failures,last_success_at,last_failure_at,last_error_code," 
                + "last_error_message,cooldown_until,average_latency_ms,last_response_status) VALUES(?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(provider_id) DO UPDATE SET health=excluded.health,consecutive_failures=excluded.consecutive_failures,"
                + "last_success_at=COALESCE(excluded.last_success_at,provider_runtime.last_success_at),"
                + "last_failure_at=COALESCE(excluded.last_failure_at,provider_runtime.last_failure_at),"
                + "last_error_code=excluded.last_error_code,last_error_message=excluded.last_error_message,"
                + "cooldown_until=excluded.cooldown_until,average_latency_ms=excluded.average_latency_ms,"
                + "last_response_status=excluded.last_response_status")) {
            statement.setString(1, providerId);
            statement.setString(2, health);
            statement.setInt(3, failures);
            statement.setString(4, successAt);
            statement.setString(5, failureAt);
            statement.setString(6, errorCode);
            statement.setString(7, message);
            statement.setString(8, cooldown);
            statement.setLong(9, latency);
            statement.setObject(10, status);
            statement.executeUpdate();
        }
    }
}
