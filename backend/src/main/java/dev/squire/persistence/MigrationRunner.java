package dev.squire.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Applies versioned SQLite migrations and records their checksums. */
public final class MigrationRunner {
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "initial_schema", "db/migration/V1__initial_schema.sql"));

    public void migrate(Connection connection) throws IOException, SQLException {
        ensureMigrationsTable(connection);
        int currentVersion = userVersion(connection);
        int latestVersion = MIGRATIONS.get(MIGRATIONS.size() - 1).version();
        if (currentVersion > latestVersion) {
            throw new IllegalStateException("Database schema is newer than this application");
        }

        List<AppliedMigration> applied = appliedMigrations(connection);
        if (currentVersion > 0 && applied.isEmpty()) {
            baselineExistingSchema(connection, currentVersion);
            applied = appliedMigrations(connection);
        }
        if (applied.size() != currentVersion) {
            throw new IllegalStateException("Database migration history is inconsistent");
        }
        verifyAppliedChecksums(applied);
        for (Migration migration : MIGRATIONS) {
            if (migration.version() > currentVersion) {
                apply(connection, migration);
            }
        }
    }

    private void apply(Connection connection, Migration migration) throws IOException, SQLException {
        String sql = readResource(migration.resource());
        String checksum = checksum(sql);
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                for (String command : splitStatements(sql)) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
                statement.execute("PRAGMA user_version = " + migration.version());
                try (var insert = connection.prepareStatement(
                        "INSERT INTO schema_migrations(version,description,checksum,applied_at) "
                                + "VALUES(?,?,?,strftime('%Y-%m-%dT%H:%M:%fZ','now'))")) {
                    insert.setInt(1, migration.version());
                    insert.setString(2, migration.description());
                    insert.setString(3, checksum);
                    insert.executeUpdate();
                }
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException exception) {
                statement.execute("ROLLBACK");
                throw exception;
            }
        }
    }

    private void baselineExistingSchema(Connection connection, int currentVersion) throws SQLException {
        if (currentVersion != 1 || !hasRequiredTables(connection)) {
            throw new IllegalStateException("Existing database schema cannot be safely baselined");
        }
        Migration migration = MIGRATIONS.get(0);
        String checksum;
        try {
            checksum = checksum(readResource(migration.resource()));
        } catch (IOException exception) {
            throw new IllegalStateException("Migration resource cannot be read", exception);
        }
        try (var insert = connection.prepareStatement(
                "INSERT INTO schema_migrations(version,description,checksum,applied_at) "
                        + "VALUES(?,?,?,strftime('%Y-%m-%dT%H:%M:%fZ','now'))")) {
            insert.setInt(1, migration.version());
            insert.setString(2, migration.description());
            insert.setString(3, checksum);
            insert.executeUpdate();
        }
    }

    private void verifyAppliedChecksums(List<AppliedMigration> applied) {
        for (AppliedMigration row : applied) {
            Migration migration = MIGRATIONS.stream()
                    .filter(candidate -> candidate.version() == row.version())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unknown database migration " + row.version()));
            try {
                String expected = checksum(readResource(migration.resource()));
                if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        row.checksum().getBytes(StandardCharsets.US_ASCII))) {
                    throw new IllegalStateException("Database migration checksum mismatch for version " + row.version());
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Migration resource cannot be read", exception);
            }
        }
    }

    private List<AppliedMigration> appliedMigrations(Connection connection) throws SQLException {
        List<AppliedMigration> values = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT version,checksum FROM schema_migrations ORDER BY version");
                var result = query.executeQuery()) {
            while (result.next()) {
                values.add(new AppliedMigration(result.getInt(1), result.getString(2)));
            }
        }
        return values;
    }

    private void ensureMigrationsTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                    + "version INTEGER PRIMARY KEY,description TEXT NOT NULL,checksum TEXT NOT NULL,"
                    + "applied_at TEXT NOT NULL)");
        }
    }

    private int userVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA user_version")) {
            result.next();
            return result.getInt(1);
        }
    }

    private boolean hasRequiredTables(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type='table'"
                + " AND name IN ('providers','schemes','scheme_members','attempts')"); var result = query.executeQuery()) {
            result.next();
            return result.getInt(1) == 4;
        }
    }

    private String readResource(String resource) throws IOException {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String checksum(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char value = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (value == '\n' || value == '\r') {
                    lineComment = false;
                    current.append(value);
                }
                continue;
            }
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!singleQuote && !doubleQuote && value == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!singleQuote && !doubleQuote && value == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (value == '\'' && !doubleQuote) {
                current.append(value);
                if (singleQuote && next == '\'') {
                    current.append(next);
                    index++;
                } else {
                    singleQuote = !singleQuote;
                }
                continue;
            }
            if (value == '"' && !singleQuote) {
                current.append(value);
                if (doubleQuote && next == '"') {
                    current.append(next);
                    index++;
                } else {
                    doubleQuote = !doubleQuote;
                }
                continue;
            }
            if (value == ';' && !singleQuote && !doubleQuote) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private record Migration(int version, String description, String resource) { }

    private record AppliedMigration(int version, String checksum) { }
}
