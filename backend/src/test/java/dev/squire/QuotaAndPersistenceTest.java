package dev.squire;

import dev.squire.persistence.Database;
import dev.squire.provider.ProviderInput;
import dev.squire.provider.ProviderRepository;
import dev.squire.scheme.Scheme;
import dev.squire.scheme.SchemeInput;
import dev.squire.scheme.SchemeRepository;
import dev.squire.security.LocalSecrets;
import dev.squire.usage.AttemptRepository;
import dev.squire.usage.Usage;
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
            LocalSecrets secrets = new LocalSecrets(directory.toString());
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
            ProviderRepository providers = new ProviderRepository(database, new LocalSecrets(directory.toString()), CLOCK);
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
        LocalSecrets secrets = new LocalSecrets(directory.toString());
        ProviderRepository providers = new ProviderRepository(first, secrets, CLOCK);
        String id;
        String schemeId;
        try {
            var provider = providers.save(null, input(0, 0));
            id = provider.id();
            assertThat(provider.encryptedKey()).doesNotContain("upstream-key");
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
            LocalSecrets restoredSecrets = new LocalSecrets(directory.toString());
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

    private ProviderInput input(long requests, long tokens) {
        return new ProviderInput("provider", "https://api.example.com/v1", "upstream-key", true, requests, tokens, 128, 100);
    }
}
