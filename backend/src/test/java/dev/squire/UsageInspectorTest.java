package dev.squire;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.squire.gateway.UsageInspector;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageInspectorTest {
    @Test
    void readsTerminalUsageAfterOversizedEventWithoutGrowingUnbounded() {
        UsageInspector inspector = new UsageInspector(new ObjectMapper(), true);
        inspector.accept(("data: " + "x".repeat(1024 * 1024 + 10) + "\n\n").getBytes(StandardCharsets.UTF_8));
        String terminal = "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":4}}}\r\n\r\n";
        for (byte value : terminal.getBytes(StandardCharsets.UTF_8)) {
            inspector.accept(new byte[]{value});
        }
        inspector.end();
        assertThat(inspector.usage().totalTokens()).isEqualTo(14);
        assertThat(inspector.resultStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void handlesMultilineEventsAndIgnoresDoneMarker() {
        UsageInspector inspector = new UsageInspector(new ObjectMapper(), true);
        inspector.accept(("data: {\"type\":\"response.incomplete\",\n"
                + "data: \"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        inspector.end();
        assertThat(inspector.usage().totalTokens()).isEqualTo(5);
        assertThat(inspector.resultStatus()).isEqualTo("INCOMPLETE");
    }

    @Test
    void doesNotTreatMalformedOrNegativeUsageAsActualTokens() {
        UsageInspector inspector = new UsageInspector(new ObjectMapper(), false);
        inspector.accept("{\"usage\":{\"input_tokens\":-1,\"output_tokens\":4}}".getBytes(StandardCharsets.UTF_8));
        inspector.end();
        assertThat(inspector.usage()).isNull();
    }

    @Test
    void ignoresInterimStreamUsageAndDoesNotMarkMalformedJsonSuccessful() {
        UsageInspector stream = new UsageInspector(new ObjectMapper(), true);
        stream.accept(("data: {\"type\":\"response.in_progress\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        stream.end();
        assertThat(stream.usage()).isNull();
        assertThat(stream.resultStatus()).isEqualTo("INCOMPLETE");
        UsageInspector json = new UsageInspector(new ObjectMapper(), false);
        json.accept("broken".getBytes(StandardCharsets.UTF_8));
        json.end();
        assertThat(json.resultStatus()).isEqualTo("INCOMPLETE");
    }
}
