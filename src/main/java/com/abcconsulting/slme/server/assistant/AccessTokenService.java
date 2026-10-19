package com.abcconsulting.slme.server.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Access tokens that let an AI assistant (Claude, ChatGPT, ...) use a user's shopping lists through /mcp. A token is
 * random, shown once when it is created, and stored only as a SHA-256 hash.
 */
@Service
public class AccessTokenService {

    /** Marks HList tokens, so they are easy to recognise (and to find if pasted somewhere by mistake). */
    static final String PREFIX = "hlist_";
    private static final int MAX_TOKENS_PER_USER = 20;

    private final JdbcTemplate jdbcTemplate;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AccessTokenService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AccessToken(long id, String label, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }

    /** A new token: the secret, shown to the user only this once, and its details. */
    public record CreatedToken(String token, AccessToken details) {
    }

    public CreatedToken create(String username, String label) {
        return create(username, label, null);
    }

    /**
     * A token for an assistant that connected by OAuth; it replaces the token that client got before, so
     * reconnecting doesn't pile up tokens.
     */
    @Transactional
    public CreatedToken createForClient(String username, String label, String clientId) {
        jdbcTemplate.update("DELETE FROM access_token WHERE username = ? AND client_id = ?", username, clientId);
        return create(username, label, clientId);
    }

    private CreatedToken create(String username, String label, String clientId) {
        String name = label == null || label.isBlank() ? "AI assistant" : label.strip();
        if (name.length() > 100) {
            throw new IllegalArgumentException("The name can be at most 100 characters.");
        }
        if (list(username).size() >= MAX_TOKENS_PER_USER) {
            throw new IllegalArgumentException("You have " + MAX_TOKENS_PER_USER
                    + " access tokens already. Remove one you no longer use first.");
        }
        String token = PREFIX + newSecret();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO access_token (username, label, token_hash, client_id) VALUES (?, ?, ?, ?)",
                    new String[]{"token_id"});
            statement.setString(1, username);
            statement.setString(2, name);
            statement.setString(3, hash(token));
            statement.setString(4, clientId);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        return new CreatedToken(token, find(id, username).orElseThrow());
    }

    public List<AccessToken> list(String username) {
        return jdbcTemplate.query("""
                        SELECT token_id, label, created_at, last_used_at FROM access_token
                        WHERE username = ? ORDER BY created_at, token_id
                        """,
                (row, number) -> new AccessToken(row.getLong("token_id"), row.getString("label"),
                        row.getObject("created_at", OffsetDateTime.class), row.getObject("last_used_at", OffsetDateTime.class)),
                username);
    }

    public boolean delete(long id, String username) {
        return jdbcTemplate.update("DELETE FROM access_token WHERE token_id = ? AND username = ?", id, username) > 0;
    }

    /** The username a token belongs to, noting when it was used; empty for an unknown or removed token. */
    public Optional<String> authenticate(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String hash = hash(token);
        List<String> usernames = jdbcTemplate.queryForList(
                "SELECT username FROM access_token WHERE token_hash = ?", String.class, hash);
        if (usernames.isEmpty()) {
            return Optional.empty();
        }
        jdbcTemplate.update("UPDATE access_token SET last_used_at = CURRENT_TIMESTAMP WHERE token_hash = ?", hash);
        return Optional.of(usernames.getFirst());
    }

    private Optional<AccessToken> find(long id, String username) {
        return list(username).stream().filter(token -> token.id() == id).findFirst();
    }

    static String newSecret() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
