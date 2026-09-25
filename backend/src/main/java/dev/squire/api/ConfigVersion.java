package dev.squire.api;

/** Parses the version sent by an editor that loaded a configuration earlier. */
public final class ConfigVersion {
    private ConfigVersion() { }

    public static long require(String ifMatch) {
        if (ifMatch == null) {
            throw new ApiException(428, "CONFIG_VERSION_REQUIRED", "Reload the configuration before saving");
        }
        if (!ifMatch.matches("\"[1-9][0-9]*\"")) {
            throw new ApiException(400, "INVALID_CONFIG_VERSION", "If-Match must contain a quoted version number");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new ApiException(400, "INVALID_CONFIG_VERSION", "If-Match version is invalid");
        }
    }
}
