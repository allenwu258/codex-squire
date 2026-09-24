package dev.squire;

import dev.squire.gateway.OutcomeResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeResolverTest {
    private final OutcomeResolver resolver = new OutcomeResolver();

    @Test
    void preservesCompletedUpstreamWhenDeliveryIsCancelled() {
        OutcomeResolver.FailureOutcome outcome = resolver.cancelled("COMPLETED");

        assertThat(outcome.status()).isEqualTo("CANCELLED");
        assertThat(outcome.upstreamOutcome()).isEqualTo("COMPLETED");
        assertThat(outcome.deliveryOutcome()).isEqualTo("CLIENT_CANCELLED");
    }

    @Test
    void mapsTerminalResponsesWithoutConflatingTransport() {
        assertThat(resolver.response(200, "SUCCEEDED").upstreamOutcome()).isEqualTo("COMPLETED");
        assertThat(resolver.response(200, "INCOMPLETE").status()).isEqualTo("INCOMPLETE");
        assertThat(resolver.response(500, "SUCCEEDED").upstreamOutcome()).isEqualTo("FAILED");
        assertThat(resolver.transport("SUCCEEDED", "COMPLETED", true).status()).isEqualTo("TRANSPORT_ERROR");
    }
}
