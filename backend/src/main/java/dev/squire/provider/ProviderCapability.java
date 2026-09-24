package dev.squire.provider;

public record ProviderCapability(String providerId, String model, boolean supportsResponses,
                                 boolean supportsSse, String usageSupport, String lastProbeAt, String lastError) { }
