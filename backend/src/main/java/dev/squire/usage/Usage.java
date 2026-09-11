package dev.squire.usage;

public record Usage(long inputTokens, long outputTokens, long cachedTokens) {
    public long totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
