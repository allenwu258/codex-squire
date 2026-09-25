package dev.squire.usage;

import dev.squire.persistence.Database;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/** Stores one row for the client request that may contain multiple provider attempts. */
@Repository
public final class GatewayRequestRepository {
    private final Database database;
    private final Clock clock;

    public GatewayRequestRepository(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public void start(String requestId, String model, boolean stream, String schemeId) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO gateway_requests "
                    + "(id,received_at,model,stream,scheme_id) VALUES(?,?,?,?,?)")) {
                statement.setString(1, requestId);
                statement.setString(2, clock.instant().toString());
                statement.setString(3, model);
                statement.setBoolean(4, stream);
                statement.setString(5, schemeId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void finish(String requestId, String outcome, String providerId) {
        database.transaction(connection -> {
            Instant completed = clock.instant();
            try (var statement = connection.prepareStatement("UPDATE gateway_requests SET completed_at=?,final_outcome=?,"
                    + "final_provider_id=COALESCE(?,(SELECT provider_id FROM attempts WHERE request_id=? "
                    + "ORDER BY attempt_index DESC LIMIT 1)),attempt_count=(SELECT COUNT(*) FROM attempts WHERE request_id=?),"
                    + "total_latency_ms=COALESCE((julianday(?) - julianday(received_at))*86400000,0) "
                    + "WHERE id=? AND completed_at IS NULL")) {
                statement.setString(1, completed.toString());
                statement.setString(2, outcome);
                statement.setString(3, providerId);
                statement.setString(4, requestId);
                statement.setString(5, requestId);
                statement.setString(6, completed.toString());
                statement.setString(7, requestId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public long logicalRequests(String day) {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM gateway_requests WHERE substr(received_at,1,10)=?")) {
                statement.setString(1, day);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
    }
}
