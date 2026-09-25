package dev.squire;

import dev.squire.persistence.Database;
import dev.squire.provider.ProviderInput;
import dev.squire.provider.ProviderRepository;
import dev.squire.scheme.Scheme;
import dev.squire.scheme.SchemeInput;
import dev.squire.scheme.SchemeRepository;
import dev.squire.security.LocalSecrets;
import dev.squire.usage.AttemptRepository;
import dev.squire.usage.QuotaRule;
import dev.squire.usage.QuotaRuleInput;
import dev.squire.usage.QuotaRuleRepository;
import dev.squire.usage.Usage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaAndPersistenceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T23:59:59Z"), ZoneOffset.UTC);
    @TempDir
    private Path directory;

    @Test
    void concurrentAdmissionsCannotOversubscribeTokens() throws Exception {
        Database database = new Database(directory.toString());
        try {
            LocalSecrets secrets = new LocalSecrets(directory.toString(), database);
            ProviderRepository providers = new ProviderRepository(database, secrets, CLOCK);
            String id = providers.save(null, input(0, 100)).id();
            AttemptRepository attempts = new AttemptRepository(database, providers, CLOCK);
            var executor = Executors.newFixedThreadPool(16);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<AttemptRepository.Admission>> futures = new ArrayList<>();
                for (int index = 0; index < 32; index++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return attempts.admit(id, "scheme", "request", "model");
                    }));
                }
                start.countDown();
                int admitted = 0;
                for (var future : futures) {
                    if (future.get() != null) {
                        admitted++;
                    }
                }
                assertThat(admitted).isEqualTo(1);
                assertThat(attempts.statistics().chargedTokens()).isEqualTo(100);
            } finally {
                executor.shutdownNow();
            }
        } finally {
            database.close();
        }
    }

    @Test
    void settlementIsIdempotentAndUtcDayRollsOver() throws Exception {
        Database database = new Database(directory.toString());
        try {
            ProviderRepository providers = new ProviderRepository(database,
                    new LocalSecrets(directory.toString(), database), CLOCK);
            String id = providers.save(null, input(1, 100)).id();
            AttemptRepository attempts = new AttemptRepository(database, providers, CLOCK);
            var admission = attempts.admit(id, "scheme", "request", "model");
            attempts.finish(admission, "SUCCEEDED", 200, new Usage(80, 40, 0), false);
            attempts.finish(admission, "CANCELLED", null, null, false);
            assertThat(attempts.statistics().chargedTokens()).isEqualTo(120);
            assertThat(attempts.statistics().successes()).isEqualTo(1);
            assertThat(attempts.admit(id, "scheme", "second", "model")).isNull();
            Clock nextDay = Clock.fixed(Instant.parse("2026-09-11T00:00:01Z"), ZoneOffset.UTC);
            AttemptRepository tomorrow = new AttemptRepository(database, providers, nextDay);
            assertThat(tomorrow.statistics().requests()).isZero();
            assertThat(tomorrow.admit(id, "scheme", "third", "model")).isNotNull();
        } finally {
            database.close();
        }
    }

    @Test
    void restartPreservesCredentialsAndActiveSchemeAndRecoversInflight() throws Exception {
        Database first = new Database(directory.toString());
        LocalSecrets secrets = new LocalSecrets(directory.toString(), first);
        ProviderRepository providers = new ProviderRepository(first, secrets, CLOCK);
        String id;
        String schemeId;
        try {
            var provider = providers.save(null, input(0, 0));
            id = provider.id();
            assertThat(provider.encryptedKey()).doesNotContain("upstream-key");
            assertThat(provider.encryptedKey()).startsWith("v1:");
            assertThat(Files.readString(directory.resolve("master.key"))).startsWith("v1:");
            assertThat(secrets.encrypt("same")).isNotEqualTo(secrets.encrypt("same"));
            SchemeRepository schemes = new SchemeRepository(first);
            var scheme = schemes.save(null, new SchemeInput("persistent", Scheme.Strategy.ORDERED,
                    List.of(new SchemeInput.MemberInput(id, 1))));
            schemeId = scheme.id();
            schemes.activate(schemeId);
            new AttemptRepository(first, providers, CLOCK).admit(id, schemeId, "request", "model");
            assertThatThrownBy(() -> new Database(directory.toString())).isInstanceOf(RuntimeException.class);
        } finally {
            first.close();
        }
        Database reopened = new Database(directory.toString());
        try {
            LocalSecrets restoredSecrets = new LocalSecrets(directory.toString(), reopened);
            ProviderRepository restoredProviders = new ProviderRepository(reopened, restoredSecrets, CLOCK);
            assertThat(restoredSecrets.decrypt(restoredProviders.find(id).encryptedKey())).isEqualTo("upstream-key");
            assertThat(new SchemeRepository(reopened).active().id()).isEqualTo(schemeId);
            var stats = new AttemptRepository(reopened, restoredProviders, CLOCK).statistics();
            assertThat(stats.inFlight()).isZero();
            assertThat(stats.chargedTokens()).isEqualTo(100);
            assertThat(stats.uncertainRequests()).isEqualTo(1);
        } finally {
            reopened.close();
        }
    }

    @Test
    void missingMasterKeyIsRejectedWhenEncryptedCredentialsExist() throws Exception {
        Database database = new Database(directory.toString());
        try {
            LocalSecrets secrets = new LocalSecrets(directory.toString(), database);
            ProviderRepository providers = new ProviderRepository(database, secrets, CLOCK);
            providers.save(null, input(0, 0));
        } finally {
            database.close();
        }
        Files.delete(directory.resolve("master.key"));
        Database reopened = new Database(directory.toString());
        try {
            assertThatThrownBy(() -> new LocalSecrets(directory.toString(), reopened))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Master key is missing");
        } finally {
            reopened.close();
        }
    }

    @Test
    void migrationRecordIsIdempotentAndChecksumProtected() throws Exception {
        Database database = new Database(directory.toString());
        try {
            int migrationCount = database.read(connection -> {
                try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM schema_migrations");
                        var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            });
            assertThat(migrationCount).isEqualTo(7);
            database.transaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE schema_migrations SET checksum='invalid'");
                }
                return null;
            });
        } finally {
            database.close();
        }
        assertThatThrownBy(() -> new Database(directory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void storesUpstreamAndDeliveryOutcomesIndependentlyAndSettlesOnce() throws Exception {
        Database database = new Database(directory.toString());
        try {
            ProviderRepository providers = new ProviderRepository(database,
                    new LocalSecrets(directory.toString(), database), CLOCK);
            String id = providers.save(null, input(0, 0)).id();
            AttemptRepository attempts = new AttemptRepository(database, providers, CLOCK);
            var admission = attempts.admit(id, "scheme", "request", "model");
            attempts.finish(admission, new AttemptRepository.Settlement("CANCELLED", 200,
                    new Usage(10, 5, 0), false, "COMPLETED", "CLIENT_CANCELLED"));
            attempts.finish(admission, new AttemptRepository.Settlement("FAILED", 500,
                    new Usage(100, 100, 0), false, "FAILED", "WRITE_FAILED"));
            AttemptRepository.Attempt attempt = attempts.list(10, 0).get(0);
            assertThat(attempt.status()).isEqualTo("CANCELLED");
            assertThat(attempt.upstreamOutcome()).isEqualTo("COMPLETED");
            assertThat(attempt.deliveryOutcome()).isEqualTo("CLIENT_CANCELLED");
            assertThat(attempt.chargedTokens()).isEqualTo(15);
        } finally {
            database.close();
        }
    }

    @Test
    void rateLimitCreatesCooldownAndSuccessfulRecoveryClearsIt() throws Exception {
        Database database = new Database(directory.toString());
        try {
            ProviderRepository providers = new ProviderRepository(database,
                    new LocalSecrets(directory.toString(), database), CLOCK);
            String id = providers.save(null, input(0, 0)).id();
            AttemptRepository attempts = new AttemptRepository(database, providers, CLOCK);
            var admission = attempts.admit(id, "scheme", "request", "model");
            attempts.finish(admission, new AttemptRepository.Settlement("RATE_LIMITED", 429, null, true,
                    "REJECTED", "PENDING", 60L));
            assertThat(attempts.admit(id, "scheme", "second", "model")).isNull();

            Clock afterCooldown = Clock.fixed(Instant.parse("2026-09-11T00:02:00Z"), ZoneOffset.UTC);
            AttemptRepository recovered = new AttemptRepository(database, providers, afterCooldown);
            var recoveredAdmission = recovered.admit(id, "scheme", "third", "model");
            assertThat(recoveredAdmission).isNotNull();
            recovered.finish(recoveredAdmission, new AttemptRepository.Settlement("SUCCEEDED", 200,
                    new Usage(2, 3, 0), false, "COMPLETED", "DELIVERED"));
            String health = database.read(connection -> new dev.squire.provider.ProviderRuntimeRepository()
                    .find(connection, id).health());
            assertThat(health).isEqualTo("READY");
        } finally {
            database.close();
        }
    }

    @Test
    void wrongMasterKeyIsRejectedBeforeServingRequests() throws Exception {
        Database database = new Database(directory.toString());
        try {
            LocalSecrets secrets = new LocalSecrets(directory.toString(), database);
            new ProviderRepository(database, secrets, CLOCK).save(null, input(0, 0));
        } finally {
            database.close();
        }
        byte[] replacement = new byte[32];
        java.util.Arrays.fill(replacement, (byte) 7);
        Files.writeString(directory.resolve("master.key"),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(replacement));
        Database reopened = new Database(directory.toString());
        try {
            assertThatThrownBy(() -> new LocalSecrets(directory.toString(), reopened))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot decrypt");
        } finally {
            reopened.close();
        }
    }

    @Test
    void activeAttemptsRemainVisibleAfterUtcDayRollsOver() throws Exception {
        Database database = new Database(directory.toString());
        try {
            LocalSecrets secrets = new LocalSecrets(directory.toString(), database);
            ProviderRepository providers = new ProviderRepository(database, secrets, CLOCK);
            String id = providers.save(null, input(0, 0)).id();
            AttemptRepository previousDay = new AttemptRepository(database, providers, CLOCK);
            previousDay.admit(id, "scheme", "previous-day", "model");
            Clock nextDay = Clock.fixed(Instant.parse("2026-09-11T00:00:01Z"), ZoneOffset.UTC);
            AttemptRepository currentDay = new AttemptRepository(database, providers, nextDay);
            assertThat(currentDay.statistics().inFlight()).isEqualTo(1);
            assertThat(currentDay.statistics().providers()).singleElement()
                    .extracting(AttemptRepository.ProviderUsage::inFlight).isEqualTo(1L);
        } finally {
            database.close();
        }
    }

    @Test
    void evaluatesQuotaRulesInsideAdmissionTransaction() throws Exception {
        Database database = new Database(directory.toString());
        try {
            LocalSecrets secrets = new LocalSecrets(directory.toString(), database);
            ProviderRepository providers = new ProviderRepository(database, secrets, CLOCK);
            String id = providers.save(null, input(0, 0)).id();
            new QuotaRuleRepository(database).save(id, List.of(new QuotaRuleInput(QuotaRule.Metric.TOTAL_TOKENS,
                    QuotaRule.WindowType.DAY, 1, "UTC", 100, QuotaRule.Action.SKIP_PROVIDER, true, 0)));
            AttemptRepository attempts = new AttemptRepository(database, providers, CLOCK);
            var admission = attempts.admit(id, "scheme", "request", "model");
            assertThat(admission).isNotNull();
            assertThat(attempts.admit(id, "scheme", "second", "model")).isNull();
            new QuotaRuleRepository(database).save(id, List.of(new QuotaRuleInput(QuotaRule.Metric.TOTAL_TOKENS,
                    QuotaRule.WindowType.DAY, 1, "UTC", 100, QuotaRule.Action.REJECT_REQUEST, true, 0)));
            attempts.finish(admission, new AttemptRepository.Settlement("SUCCEEDED", 200,
                    new Usage(2, 98, 0), false, "COMPLETED", "DELIVERED"));
            assertThat(attempts.tryAdmit(id, "scheme", "third", "model", 0).rejectionCode())
                    .isEqualTo("QUOTA_REJECTED");
        } finally {
            database.close();
        }
    }

    private ProviderInput input(long requests, long tokens) {
        return new ProviderInput("provider", "https://api.example.com/v1", "upstream-key", true, requests, tokens, 128, 100);
    }
}



