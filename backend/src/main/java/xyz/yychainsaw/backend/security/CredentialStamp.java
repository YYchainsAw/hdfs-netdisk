package xyz.yychainsaw.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A non-credential session marker that changes when the stored password hash changes. */
final class CredentialStamp {
    private CredentialStamp() {}
    static String of(String hash) {
        if (hash == null) return "";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(hash.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
