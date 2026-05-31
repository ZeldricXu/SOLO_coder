package com.parking.platform.gateway.service;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.context.RequestContext;
import com.parking.platform.common.exception.ForbiddenException;
import com.parking.platform.common.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private static final String CLAIM_ROLES = "roles";

    private final JwtTokenService jwtTokenService;

    public AuthenticationService(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    public void authenticate(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        Claims claims = jwtTokenService.parseToken(token);
        String userId = claims.getSubject();
        List<String> roles = extractRoles(claims);

        RequestContext context = RequestContext.current();
        context.setUserId(userId);
        context.setUserRoles(roles);

        log.debug("Authenticated user: {}, roles: {}", userId, roles);
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(Constants.AUTH_BEARER)) {
            throw new UnauthorizedException("Missing or invalid authorization header");
        }
        String token = authorizationHeader.substring(Constants.AUTH_BEARER.length());
        if (token.isEmpty()) {
            throw new UnauthorizedException("Empty token");
        }
        return token;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Claims claims) {
        Object roles = claims.get(CLAIM_ROLES);
        if (roles instanceof List) {
            return (List<String>) roles;
        }
        return Collections.emptyList();
    }

    public void authorize(String requiredRole) {
        ensureAuthenticated();
        if (!hasRequiredRole(requiredRole)) {
            throwForbidden(requiredRole, RequestContext.current().getUserId());
        }
    }

    public void authorizeAny(List<String> requiredRoles) {
        ensureAuthenticated();
        boolean hasPermission = requiredRoles.stream().anyMatch(this::hasRequiredRole);
        if (!hasPermission) {
            throwForbidden("any of " + requiredRoles, RequestContext.current().getUserId());
        }
    }

    public void authorizeAll(List<String> requiredRoles) {
        ensureAuthenticated();
        boolean hasAllPermissions = requiredRoles.stream().allMatch(this::hasRequiredRole);
        if (!hasAllPermissions) {
            throwForbidden("all of " + requiredRoles, RequestContext.current().getUserId());
        }
    }

    public boolean hasRole(String role) {
        RequestContext ctx = RequestContext.current();
        List<String> userRoles = ctx.getUserRoles();
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        return userRoles.contains(role) || userRoles.contains(Constants.ROLE_ADMIN);
    }

    private boolean hasRequiredRole(String requiredRole) {
        return hasRole(requiredRole);
    }

    private void ensureAuthenticated() {
        if (RequestContext.current().getUserId() == null) {
            throw new UnauthorizedException("Not authenticated");
        }
    }

    private void throwForbidden(String requirement, String userId) {
        log.warn("User {} does not have required role: {}", userId, requirement);
        throw new ForbiddenException("Insufficient permissions");
    }

    public String login(String username, String password) {
        List<String> roles = resolveUserRoles(username, password);
        String token = jwtTokenService.generateToken(username, roles);
        log.info("User {} logged in successfully", username);
        return token;
    }

    private List<String> resolveUserRoles(String username, String password) {
        if (isAdmin(username, password)) {
            return List.of(Constants.ROLE_ADMIN, Constants.ROLE_USER);
        } else if (isUser(username, password)) {
            return List.of(Constants.ROLE_USER);
        } else if (isService(username, password)) {
            return List.of(Constants.ROLE_SERVICE);
        } else {
            throw new UnauthorizedException("Invalid credentials");
        }
    }

    private boolean isAdmin(String username, String password) {
        return "admin".equals(username) && "admin123".equals(password);
    }

    private boolean isUser(String username, String password) {
        return "user".equals(username) && "user123".equals(password);
    }

    private boolean isService(String username, String password) {
        return "service".equals(username) && "service123".equals(password);
    }
}
