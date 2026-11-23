package xyz.yychainsaw.backend.security;

import java.io.Serializable;

/** Only non-secret identity is kept in the HTTP session. */
public record SessionUser(String id, String username, String credentialStamp) implements Serializable {}
