package dev.squire.gateway;

import org.springframework.stereotype.Component;

/** Resolves protocol observations into durable upstream and delivery outcomes. */
@Component
public final class OutcomeResolver {
    public ResponseOutcome response(int httpStatus, String inspectorStatus) {
        if (httpStatus >= 400) {
            return new ResponseOutcome("UPSTREAM_ERROR", "FAILED");
        }
        return switch (inspectorStatus) {
            case "SUCCEEDED" -> new ResponseOutcome("SUCCEEDED", "COMPLETED");
            case "INCOMPLETE" -> new ResponseOutcome("INCOMPLETE", "INCOMPLETE");
            case "FAILED" -> new ResponseOutcome("FAILED", "FAILED");
            default -> new ResponseOutcome("INCOMPLETE", "UNKNOWN");
        };
    }

    public ResponseOutcome rateLimited() {
        return new ResponseOutcome("RATE_LIMITED", "REJECTED");
    }

    public ResponseOutcome redirect() {
        return new ResponseOutcome("REDIRECT_REJECTED", "REJECTED");
    }

    public FailureOutcome transport(String currentStatus, String currentUpstreamOutcome, boolean responseStarted) {
        String upstream = "UNKNOWN".equals(currentUpstreamOutcome) ? "FAILED" : currentUpstreamOutcome;
        String status = "SUCCEEDED".equals(currentStatus) ? "TRANSPORT_ERROR" : currentStatus;
        return new FailureOutcome(status, upstream, responseStarted ? "WRITE_FAILED" : "PENDING");
    }

    public FailureOutcome cancelled(String currentUpstreamOutcome) {
        return new FailureOutcome("CANCELLED", currentUpstreamOutcome, "CLIENT_CANCELLED");
    }

    public record ResponseOutcome(String status, String upstreamOutcome) { }

    public record FailureOutcome(String status, String upstreamOutcome, String deliveryOutcome) { }
}
