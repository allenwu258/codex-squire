package dev.squire.provider;

import dev.squire.persistence.Database;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public final class CapabilityRepository {
    private final Database database;

    public CapabilityRepository(Database database) {
        this.database = database;
    }

    public List<ProviderCapability> list(String providerId) {
        return database.read(connection -> list(connection, providerId));
    }

    public void save(ProviderCapability capability) {
        database.transaction(connection -> {
            save(connection, capability);
            return null;
        });
    }

    public List<ProviderCapability> list(Connection connection, String providerId) throws SQLException {
        List<ProviderCapability> values = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM provider_capabilities WHERE provider_id=? ORDER BY model")) {
            statement.setString(1, providerId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(map(rows));
                }
            }
        }
        return List.copyOf(values);
    }

    private void save(Connection connection, ProviderCapability capability) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO provider_capabilities "
                + "(provider_id,model,supports_responses,supports_sse,usage_support,last_probe_at,last_error) "
                + "VALUES(?,?,?,?,?,?,?) ON CONFLICT(provider_id,model) DO UPDATE SET "
                + "supports_responses=excluded.supports_responses,supports_sse=excluded.supports_sse,"
                + "usage_support=excluded.usage_support,last_probe_at=excluded.last_probe_at,last_error=excluded.last_error")) {
            statement.setString(1, capability.providerId());
            statement.setString(2, capability.model());
            statement.setBoolean(3, capability.supportsResponses());
            statement.setBoolean(4, capability.supportsSse());
            statement.setString(5, capability.usageSupport());
            statement.setString(6, capability.lastProbeAt());
            statement.setString(7, capability.lastError());
            statement.executeUpdate();
        }
    }

    private ProviderCapability map(ResultSet row) throws SQLException {
        return new ProviderCapability(row.getString("provider_id"), row.getString("model"),
                row.getBoolean("supports_responses"), row.getBoolean("supports_sse"), row.getString("usage_support"),
                row.getString("last_probe_at"), row.getString("last_error"));
    }
}
