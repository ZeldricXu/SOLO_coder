package com.datapipeline.tee.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Slf4j
public class SecureAuthService {

    private final SecretKey jwtSecret;
    private final long tokenExpirySeconds;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();

    public SecureAuthService(String secret) {
        this(secret, 3600);
    }

    public SecureAuthService(String secret, long tokenExpirySeconds) {
        this.jwtSecret = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenExpirySeconds = tokenExpirySeconds;
    }

    public String generateToken(String subject, Map<String, Object> claims) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(tokenExpirySeconds);

        String token = Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(jwtSecret)
                .compact();

        SessionInfo session = SessionInfo.builder()
                .token(token)
                .subject(subject)
                .claims(new HashMap<>(claims))
                .createdAt(now)
                .expiresAt(expiry)
                .build();

        sessions.put(token, session);
        log.debug("Token generated for subject: {}", subject);
        return token;
    }

    public Optional<Claims> validateToken(String token) {
        if (revokedTokens.contains(token)) {
            log.warn("Token is revoked");
            return Optional.empty();
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(jwtSecret)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(claims);
        } catch (Exception e) {
            log.warn("Token validation failed", e);
            return Optional.empty();
        }
    }

    public void revokeToken(String token) {
        revokedTokens.add(token);
        sessions.remove(token);
        log.info("Token revoked");
    }

    public void revokeAllSessions(String subject) {
        sessions.entrySet().removeIf(entry -> {
            if (subject.equals(entry.getValue().getSubject())) {
                revokedTokens.add(entry.getKey());
                return true;
            }
            return false;
        });
        log.info("All sessions revoked for subject: {}", subject);
    }

    public Optional<SessionInfo> getSession(String token) {
        return Optional.ofNullable(sessions.get(token));
    }

    public String generateNonce() {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

}
