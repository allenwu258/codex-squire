package dev.squire.persistence;

import dev.squire.api.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class Database {
    private final String url;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public Database(@Value("${squire.data-dir}") String dataDirectory) throws IOException, SQLException {
        Path directory = Path.of(dataDirectory).toAbsolutePath();
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquiredLock;
        try {
            acquiredLock = lockChannel.tryLock();
        } catch (IOException | RuntimeException exception) {
            lockChannel.close();
            throw exception;
        }
        lock = acquiredLock;
        if (lock == null) {
            lockChannel.close();
            throw new IllegalStateException("Another backend owns this data directory");
        }
        url = "jdbc:sqlite:" + directory.resolve("squire.db");
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
            }
            new MigrationRunner().migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE attempts SET status='INTERRUPTED', usage_state='RESERVED', "
                        + "ended_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE status='IN_FLIGHT'");
                statement.executeUpdate("UPDATE gateway_requests SET final_outcome='INTERRUPTED',"
                        + "completed_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),"
                        + "attempt_count=(SELECT COUNT(*) FROM attempts WHERE request_id=gateway_requests.id) "
                        + "WHERE completed_at IS NULL");
            }
        } catch (IOException | SQLException | RuntimeException exception) {
            lock.release();
            lockChannel.close();
            throw exception;
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    public <T> T read(SqlWork<T> work) {
        try (Connection connection = open()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    public <T> T transaction(SqlWork<T> work) {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                T value = work.execute(connection);
                statement.execute("COMMIT");
                return value;
            } catch (Exception exception) {
                statement.execute("ROLLBACK");
                throw exception;
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    public boolean hasEncryptedCredentials() {
        return read(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM providers WHERE encrypted_key IS NOT NULL AND encrypted_key <> '')");
                    var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        });
    }

    public java.util.List<String> encryptedCredentials() {
        return read(connection -> {
            java.util.List<String> credentials = new java.util.ArrayList<>();
            try (var statement = connection.prepareStatement(
                    "SELECT encrypted_key FROM providers WHERE encrypted_key IS NOT NULL AND encrypted_key <> ''");
                    var result = statement.executeQuery()) {
                while (result.next()) {
                    credentials.add(result.getString(1));
                }
            }
            return credentials;
        });
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private RuntimeException translate(SQLException exception) {
        if (exception.getErrorCode() == 19) {
            return new ApiException(409, "CONFLICT", "Name already exists or resource is referenced by a scheme");
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (exception.getErrorCode() == 5 || exception.getErrorCode() == 6
                || normalized.contains("busy") || normalized.contains("locked")) {
            return new ApiException(503, "DATABASE_BUSY", "Local storage is busy; retry the operation");
        }
        return new ApiException(500, "STORAGE_ERROR", "Local storage operation failed");
    }

    @PreDestroy
    public void close() throws IOException {
        lock.release();
        lockChannel.close();
    }
}
