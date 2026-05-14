package com.authcenter.service;

import com.authcenter.dto.TokenVerifyResponse;
import com.authcenter.entity.User;
import com.authcenter.exception.AuthException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class TokenService {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private SessionService sessionService;
    
    @Value("${jwt.secret}")
    private String jwtSecret;
    
    @Value("${jwt.expiration:7200000}")
    private long jwtExpiration;
    
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
    
    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id", user.getUserId());
        claims.put("username", user.getUsername());
        claims.put("email", user.getEmail());
        
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);
        
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(user.getUserId())
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }
    
    public TokenVerifyResponse verifyToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            
            if (!sessionService.isSessionValid(token)) {
                return new TokenVerifyResponse(false, null, null, null);
            }
            
            return new TokenVerifyResponse(true, userId, username, 
                    sessionService.getSessionIdByToken(token));
            
        } catch (ExpiredJwtException e) {
            return new TokenVerifyResponse(false, null, null, null);
        } catch (JwtException e) {
            throw new AuthException(401, "无效的令牌");
        }
    }
    
    public String getUserIdFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.getSubject();
        } catch (JwtException e) {
            throw new AuthException(401, "无效的令牌");
        }
    }
    
    public boolean isTokenValid(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}