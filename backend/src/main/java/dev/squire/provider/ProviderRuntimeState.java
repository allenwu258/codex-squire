package dev.squire.provider;

public record ProviderRuntimeState(String providerId, String health, int consecutiveFailures,
                                   String lastSuccessAt, String lastFailureAt, String lastErrorCode,
                                   String lastErrorMessage, String cooldownUntil, long averageLatencyMs,
                                   Integer lastResponseStatus) {
    public boolean inCooldown(String now) {
        return cooldownUntil != null && cooldownUntil.compareTo(now) > 0;
    }
}
