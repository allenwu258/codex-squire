package dev.squire.provider;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record Provider(String id, String name, String baseUrl, @JsonIgnore String encryptedKey,
                       boolean enabled, long dailyRequestLimit, long dailyTokenLimit,
                       int maxConcurrency, long tokenReservation, String createdAt) {
    public boolean hasApiKey() {
        return !encryptedKey.isBlank();
    }
}
