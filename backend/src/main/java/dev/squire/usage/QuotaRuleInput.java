package dev.squire.usage;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.DateTimeException;
import java.time.ZoneId;
import dev.squire.api.ApiException;

public record QuotaRuleInput(@NotNull QuotaRule.Metric metric, @NotNull QuotaRule.WindowType windowType,
                             @Min(1) int windowSize, String timezone, @Min(1) long threshold,
                             @NotNull QuotaRule.Action action, boolean enabled, int priority) {
    public String normalizedTimezone() {
        String value = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
        try {
            ZoneId.of(value);
            return value;
        } catch (DateTimeException exception) {
            throw new ApiException(400, "INVALID_TIMEZONE", "Quota rule timezone is invalid");
        }
    }
}
