package com.cicd.server.security;

import com.cicd.common.enums.RoleType;
import com.cicd.server.entity.User;
import com.cicd.server.entity.UserRole;
import com.cicd.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.io.Serializable;

@Component
@RequiredArgsConstructor
public class RbacPermissionEvaluator {

    private final UserService userService;

    public boolean hasPermission(Authentication authentication, String targetType, String permission) {
        String username = authentication.getName();
        User user = userService.findByUsername(username);
        if (user == null) return false;

        for (UserRole role : user.getRoles()) {
            if (hasPermission(role.getRole(), targetType, permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasProjectPermission(Authentication authentication, Long projectId, String permission) {
        String username = authentication.getName();
        User user = userService.findByUsername(username);
        if (user == null) return false;

        for (UserRole role : user.getRoles()) {
            if (role.getRole() == RoleType.PLATFORM_ADMIN) {
                return true;
            }
            if (role.getProjects() != null && role.getProjects().stream().anyMatch(p -> p.getId().equals(projectId))) {
                if (hasPermission(role.getRole(), "project", permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasPermission(RoleType role, String targetType, String permission) {
        return switch (role) {
            case PLATFORM_ADMIN -> true;
            case PROJECT_OWNER -> switch (targetType) {
                case "pipeline" -> true;
                case "deployment" -> true;
                case "approval" -> true;
                case "project" -> true;
                default -> false;
            };
            case DEVELOPER -> switch (targetType) {
                case "pipeline" -> permission.equals("view") || permission.equals("trigger");
                case "deployment" -> permission.equals("view");
                case "approval" -> permission.equals("view");
                default -> false;
            };
            case VIEWER -> permission.equals("view");
        };
    }
}
