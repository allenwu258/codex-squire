package dev.squire.persistence;

import dev.squire.api.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
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
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                result.next();
                int version = result.getInt(1);
                if (version > 1) {
                    throw new IllegalStateException("Database schema is newer than this application");
                }
                if (version == 0) {
                    migrate(connection);
                }
            }
            statement.executeUpdate("UPDATE attempts SET status='INTERRUPTED', usage_state='RESERVED', "
                    + "ended_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE status='IN_FLIGHT'");
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

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void migrate(Connection connection) throws IOException, SQLException {
        String schema;
        try (var input = new ClassPathResource("db/V1.sql").getInputStream()) {
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
                statement.execute("COMMIT");
            } catch (SQLException exception) {
                statement.execute("ROLLBACK");
                throw exception;
            }
        }
    }

    private RuntimeException translate(SQLException exception) {
        if (exception.getErrorCode() == 19) {
            return new ApiException(409, "CONFLICT", "Name already exists or resource is referenced by a scheme");
        }
        return new IllegalStateException("SQLite operation failed", exception);
    }

    @PreDestroy
    public void close() throws IOException {
        lock.release();
        lockChannel.close();
    }
}
