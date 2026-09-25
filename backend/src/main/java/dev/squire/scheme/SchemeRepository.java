package dev.squire.scheme;

import dev.squire.api.ApiException;
import dev.squire.persistence.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public final class SchemeRepository {
    private final Database database;

    public SchemeRepository(Database database) {
        this.database = database;
    }

    public List<Scheme> list() {
        return database.read(connection -> {
            List<Scheme> schemes = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT id FROM schemes ORDER BY name");
                    var result = statement.executeQuery()) {
                while (result.next()) {
                    schemes.add(find(connection, result.getString(1)));
                }
            }
            return schemes;
        });
    }

    public Scheme active() {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT id FROM schemes WHERE active=1");
                    var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ApiException(503, "NO_ACTIVE_SCHEME", "Activate a routing scheme first");
                }
                return find(connection, result.getString(1));
            }
        });
    }

    public Scheme save(String id, SchemeInput input) {
        return save(id, input, null);
    }

    public Scheme save(String id, SchemeInput input, Long expectedVersion) {
        if (new HashSet<>(input.members().stream().map(SchemeInput.MemberInput::providerId).toList()).size() != input.members().size()) {
            throw new ApiException(400, "DUPLICATE_MEMBER", "A provider may appear only once in a scheme");
        }
        return database.transaction(connection -> {
            Scheme existing = id == null ? null : find(connection, id);
            if (existing != null && expectedVersion != null && existing.version() != expectedVersion) {
                throw new ApiException(412, "CONFIG_VERSION_CONFLICT", "Scheme changed since it was loaded");
            }
            if (existing != null && existing.active() && input.members().isEmpty()) {
                throw new ApiException(409, "EMPTY_ACTIVE_SCHEME", "The active scheme must have at least one member");
            }
            String targetId = existing == null ? UUID.randomUUID().toString() : id;
            try (var statement = connection.prepareStatement("INSERT INTO schemes(id,name,strategy) VALUES(?,?,?) "
                    + "ON CONFLICT(id) DO UPDATE SET name=excluded.name,strategy=excluded.strategy,"
                    + "version=schemes.version+1")) {
                statement.setString(1, targetId);
                statement.setString(2, input.name().trim());
                statement.setString(3, input.strategy().name());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("DELETE FROM scheme_members WHERE scheme_id=?")) {
                statement.setString(1, targetId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO scheme_members(scheme_id,provider_id,position,weight) VALUES(?,?,?,?)")) {
                for (int index = 0; index < input.members().size(); index++) {
                    SchemeInput.MemberInput member = input.members().get(index);
                    statement.setString(1, targetId);
                    statement.setString(2, member.providerId());
                    statement.setInt(3, index);
                    statement.setInt(4, member.weight());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return find(connection, targetId);
        });
    }

    public Scheme activate(String id) {
        return database.transaction(connection -> {
            Scheme scheme = find(connection, id);
            if (scheme.members().isEmpty()) {
                throw new ApiException(409, "EMPTY_SCHEME", "Add a provider before activating the scheme");
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE schemes SET active=0 WHERE active=1");
            }
            try (var statement = connection.prepareStatement("UPDATE schemes SET active=1 WHERE id=?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return find(connection, id);
        });
    }

    public void delete(String id) {
        database.transaction(connection -> {
            if (find(connection, id).active()) {
                throw new ApiException(409, "ACTIVE_SCHEME", "Activate a different scheme before deleting this one");
            }
            try (var statement = connection.prepareStatement("DELETE FROM schemes WHERE id=?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Scheme find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM schemes WHERE id=?")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ApiException(404, "SCHEME_NOT_FOUND", "Scheme not found");
                }
                List<Scheme.Member> members = new ArrayList<>();
                try (var query = connection.prepareStatement("SELECT provider_id,weight FROM scheme_members WHERE scheme_id=? ORDER BY position")) {
                    query.setString(1, id);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            members.add(new Scheme.Member(rows.getString(1), rows.getInt(2)));
                        }
                    }
                }
                return new Scheme(id, result.getString("name"), Scheme.Strategy.valueOf(result.getString("strategy")),
                        result.getBoolean("active"), List.copyOf(members), result.getLong("version"));
            }
        }
    }
}
