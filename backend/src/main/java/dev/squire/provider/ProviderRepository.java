package dev.squire.provider;

import dev.squire.api.ApiException;
import dev.squire.persistence.Database;
import dev.squire.security.LocalSecrets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public final class ProviderRepository {
    private final Database database;
    private final LocalSecrets secrets;
    private final Clock clock;

    public ProviderRepository(Database database, LocalSecrets secrets, Clock clock) {
        this.database = database;
        this.secrets = secrets;
        this.clock = clock;
    }

    public List<Provider> list() {
        return database.read(connection -> {
            List<Provider> values = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM providers ORDER BY created_at, id");
                    var result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(map(result));
                }
            }
            return values;
        });
    }

    public Provider find(String id) {
        return database.read(connection -> find(connection, id));
    }

    public Provider find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM providers WHERE id=?")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ApiException(404, "PROVIDER_NOT_FOUND", "Provider not found");
                }
                return map(result);
            }
        }
    }

    public Provider save(String id, ProviderInput input) {
        String baseUrl = input.normalizedBaseUrl();
        if (input.apiKey() != null && (input.apiKey().contains("\n") || input.apiKey().contains("\r"))) {
            throw new ApiException(400, "INVALID_API_KEY", "API key must not contain line breaks");
        }
        return database.transaction(connection -> {
            Provider existing = id == null ? null : find(connection, id);
            String encrypted = input.apiKey() == null || input.apiKey().isBlank()
                    ? (existing == null ? null : existing.encryptedKey()) : secrets.encrypt(input.apiKey().trim());
            if (encrypted == null) {
                throw new ApiException(400, "API_KEY_REQUIRED", "An API key is required for a new provider");
            }
            String targetId = existing == null ? UUID.randomUUID().toString() : id;
            String created = existing == null ? clock.instant().toString() : existing.createdAt();
            try (var statement = connection.prepareStatement("INSERT INTO providers VALUES (?,?,?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT(id) DO UPDATE SET name=excluded.name,base_url=excluded.base_url,"
                    + "encrypted_key=excluded.encrypted_key,enabled=excluded.enabled,"
                    + "daily_request_limit=excluded.daily_request_limit,daily_token_limit=excluded.daily_token_limit,"
                    + "max_concurrency=excluded.max_concurrency,token_reservation=excluded.token_reservation")) {
                statement.setString(1, targetId);
                statement.setString(2, input.name().trim());
                statement.setString(3, baseUrl);
                statement.setString(4, encrypted);
                statement.setBoolean(5, input.enabled());
                statement.setLong(6, input.dailyRequestLimit());
                statement.setLong(7, input.dailyTokenLimit());
                statement.setInt(8, input.maxConcurrency());
                statement.setLong(9, input.tokenReservation());
                statement.setString(10, created);
                statement.executeUpdate();
            }
            return find(connection, targetId);
        });
    }

    public void delete(String id) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("DELETE FROM providers WHERE id=?")) {
                statement.setString(1, id);
                if (statement.executeUpdate() == 0) {
                    throw new ApiException(404, "PROVIDER_NOT_FOUND", "Provider not found");
                }
            }
            return null;
        });
    }

    private Provider map(ResultSet row) throws SQLException {
        return new Provider(row.getString("id"), row.getString("name"), row.getString("base_url"),
                row.getString("encrypted_key"), row.getBoolean("enabled"), row.getLong("daily_request_limit"),
                row.getLong("daily_token_limit"), row.getInt("max_concurrency"),
                row.getLong("token_reservation"), row.getString("created_at"));
    }
}
