package dev.squire.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class LocalSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] masterKey;
    private final String adminToken;
    private final String gatewayToken;

    public LocalSecrets(@Value("${squire.data-dir}") String dataDirectory) throws IOException {
        Path directory = Path.of(dataDirectory).toAbsolutePath();
        Files.createDirectories(directory);
        restrict(directory);
        masterKey = Base64.getUrlDecoder().decode(readOrCreate(directory.resolve("master.key")));
        adminToken = readOrCreate(directory.resolve("admin.token"));
        gatewayToken = readOrCreate(directory.resolve("gateway.token"));
    }

    public boolean isAuthorized(String value, boolean admin) {
        String expected = "Bearer " + (admin ? adminToken : gatewayToken);
        return value != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8));
    }

    public String gatewayToken() {
        return gatewayToken;
    }

    public String encrypt(String plainText) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential encryption failed", exception);
        }
    }

    public String decrypt(String encrypted) {
        try {
            String[] parts = encrypted.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential decryption failed", exception);
        }
    }

    private static String readOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            byte[] value = new byte[32];
            RANDOM.nextBytes(value);
            Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(value),
                    StandardOpenOption.CREATE_NEW);
        }
        restrict(file);
        return Files.readString(file).trim();
    }

    private static void restrict(Path path) throws IOException {
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view != null) {
                AclEntry.Builder builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class));
                if (Files.isDirectory(path)) {
                    builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
                }
                AclEntry entry = builder.build();
                view.setAcl(List.of(entry));
            }
        }
    }
}
