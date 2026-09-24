package dev.squire.usage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public record QuotaRule(String id, String providerId, Metric metric, WindowType windowType, int windowSize,
                        String timezone, long threshold, Action action, boolean enabled, int priority) {
    public enum Metric { REQUEST_COUNT, INPUT_TOKENS, OUTPUT_TOKENS, TOTAL_TOKENS, CONCURRENT_REQUESTS }
    public enum WindowType { MINUTE, HOUR, DAY, WEEK, MONTH }
    public enum Action { SKIP_PROVIDER, REJECT_REQUEST, ENTER_COOLDOWN, DEGRADE }

    public Instant windowStart(Instant now) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime local = now.atZone(zone);
        return switch (windowType) {
            case MINUTE -> now.minus(Duration.ofMinutes((long) windowSize));
            case HOUR -> now.minus(Duration.ofHours((long) windowSize));
            case DAY -> local.toLocalDate().minusDays(windowSize - 1L).atStartOfDay(zone).toInstant();
            case WEEK -> local.toLocalDate().minusWeeks(windowSize - 1L)
                    .with(java.time.DayOfWeek.MONDAY).atStartOfDay(zone).toInstant();
            case MONTH -> local.withDayOfMonth(1).minusMonths(windowSize - 1L).toLocalDate()
                    .atStartOfDay(zone).toInstant();
        };
    }
}
