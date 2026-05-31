package com.parking.platform.gateway.service;

import com.parking.platform.gateway.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_ROLES = "roles";

    private final JwtConfig jwtConfig;
    private final SecretKey secretKey;

    public JwtTokenService(JwtConfig jwtConfig) {
        this.jwtConfig = jwtConfig;
        this.secretKey = Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, List<String> roles) {
        Map<String, Object> claims = Map.of(
                CLAIM_USER_ID, userId,
                CLAIM_ROLES, roles == null ? Collections.emptyList() : roles
        );
        return buildToken(claims, userId);
    }

    private String buildToken(Map<String, Object> claims, String subject) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtConfig.getExpiration());

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired");
            throw new TokenException("Token expired", TokenException.ErrorType.EXPIRED);
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT token: {}", e.getMessage());
            throw new TokenException("Invalid token", TokenException.ErrorType.MALFORMED);
        } catch (SignatureException e) {
            log.warn("Invalid JWT signature: {}", e.getMessage());
            throw new TokenException("Invalid signature", TokenException.ErrorType.INVALID_SIGNATURE);
        } catch (Exception e) {
            log.warn("JWT token parsing failed: {}", e.getMessage());
            throw new TokenException("Token parsing failed: " + e.getMessage(), TokenException.ErrorType.PARSE_FAILED);
        }
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (TokenException e) {
            return false;
        }
    }

    public String getUserIdFromToken(String token) {
        return parseToken(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Claims claims = parseToken(token);
        Object roles = claims.get(CLAIM_ROLES);
        if (roles instanceof List) {
            return (List<String>) roles;
        }
        return Collections.emptyList();
    }

    public Date getExpirationFromToken(String token) {
        return parseToken(token).getExpiration();
    }

    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (TokenException e) {
            return true;
        }
    }

    public static final class TokenException extends RuntimeException {
        public enum ErrorType {
            EXPIRED,
            MALFORMED,
            INVALID_SIGNATURE,
            PARSE_FAILED
        }

        private final ErrorType errorType;

        public TokenException(String message, ErrorType errorType) {
            super(message);
            this.errorType = errorType;
        }

        public ErrorType getErrorType() {
            return errorType;
        }
    }
}
