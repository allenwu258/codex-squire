package dev.squire.provider;

import dev.squire.api.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Locale;

public record ProviderInput(@NotBlank @Size(max = 80) String name,
                            @NotBlank @Size(max = 2048) String baseUrl,
                            @Size(max = 8192) String apiKey,
                            boolean enabled,
                            @Min(0) @Max(1000000000L) long dailyRequestLimit,
                            @Min(0) @Max(1000000000000L) long dailyTokenLimit,
                            @Min(1) @Max(128) int maxConcurrency,
                            @Min(1) @Max(10000000) long tokenReservation) {
    public String normalizedBaseUrl() {
        URI uri = URI.create(baseUrl.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new ApiException(400, "INVALID_BASE_URL", "Base URL must be HTTP(S), without credentials, query or fragment");
        }
        if ("http".equalsIgnoreCase(scheme) && !java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1")
                .contains(host.toLowerCase(Locale.ROOT))) {
            throw new ApiException(400, "HTTPS_REQUIRED", "Remote providers require HTTPS; HTTP is allowed only for loopback");
        }
        String normalized = uri.toString().replaceAll("/+$", "");
        if (normalized.endsWith("/responses")) {
            throw new ApiException(400, "INVALID_BASE_URL", "Enter an API base URL, such as https://host/v1");
        }
        return normalized;
    }
}
