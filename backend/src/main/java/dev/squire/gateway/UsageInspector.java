package dev.squire.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.squire.usage.Usage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Observes bounded JSON or SSE events while the original bytes are forwarded unchanged. */
public final class UsageInspector {
    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final boolean streaming;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final StringBuilder eventData = new StringBuilder();
    private boolean overflow;
    private boolean eventOverflow;
    private boolean carriageReturn;
    private volatile Usage usage;
    private volatile String terminalStatus;

    public UsageInspector(ObjectMapper mapper, boolean streaming) {
        this.mapper = mapper;
        this.streaming = streaming;
    }

    public void accept(byte[] bytes) {
        for (byte value : bytes) {
            if (streaming && (value == '\n' || value == '\r')) {
                if (value == '\n' && carriageReturn) {
                    carriageReturn = false;
                    continue;
                }
                consumeLine();
                carriageReturn = value == '\r';
            } else {
                carriageReturn = false;
                if (buffer.size() < (streaming ? MAX_EVENT_BYTES : MAX_JSON_BYTES)) {
                    buffer.write(value);
                } else {
                    overflow = true;
                }
            }
        }
    }

    public void end() {
        if (streaming) {
            if (buffer.size() > 0) {
                consumeLine();
            }
            dispatchEvent();
        } else if (!overflow) {
            parse(buffer.toString(StandardCharsets.UTF_8));
        }
    }

    public Usage usage() {
        return usage;
    }

    public String resultStatus() {
        if (terminalStatus != null) {
            return terminalStatus;
        }
        return "INCOMPLETE";
    }

    public String observedTerminalStatus() {
        return terminalStatus;
    }

    private void consumeLine() {
        String line = buffer.toString(StandardCharsets.UTF_8);
        if (overflow) {
            eventOverflow = true;
        }
        buffer.reset();
        overflow = false;
        if (line.isEmpty()) {
            dispatchEvent();
        } else if (line.startsWith("data:") && !eventOverflow) {
            String data = line.substring(5);
            if (data.startsWith(" ")) {
                data = data.substring(1);
            }
            if (eventData.length() + data.length() > MAX_EVENT_BYTES) {
                eventOverflow = true;
            } else {
                eventData.append(data).append('\n');
            }
        }
    }

    private void dispatchEvent() {
        if (!eventOverflow && !eventData.isEmpty()) {
            parse(eventData.toString());
        }
        eventData.setLength(0);
        eventOverflow = false;
    }

    private void parse(String json) {
        try {
            JsonNode event = mapper.readTree(json);
            if (event == null) {
                return;
            }
            String type = event.path("type").asText();
            JsonNode response = event.has("response") ? event.path("response") : event;
            String status = response.path("status").asText();
            if (type.equals("response.completed") || status.equals("completed")) {
                terminalStatus = "SUCCEEDED";
            } else if (type.equals("response.failed") || type.equals("error") || status.equals("failed")) {
                terminalStatus = "FAILED";
            } else if (type.equals("response.incomplete") || status.equals("incomplete")) {
                terminalStatus = "INCOMPLETE";
            }
            if (streaming && !java.util.Set.of("response.completed", "response.failed", "response.incomplete").contains(type)) {
                return;
            }
            JsonNode value = response.path("usage");
            JsonNode input = value.path("input_tokens");
            JsonNode output = value.path("output_tokens");
            if (input.isIntegralNumber() && input.canConvertToLong() && output.isIntegralNumber() && output.canConvertToLong()) {
                long in = input.longValue();
                long out = output.longValue();
                long cache = value.path("input_tokens_details").path("cached_tokens").asLong(0);
                if (in >= 0 && out >= 0 && cache >= 0 && cache <= in && in <= Long.MAX_VALUE - out) {
                    usage = new Usage(in, out, cache);
                }
            }
        } catch (IOException exception) {
            // Malformed events and [DONE] cannot supply authoritative usage.
        }
    }
}
