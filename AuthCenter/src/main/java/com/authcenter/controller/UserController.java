package com.authcenter.controller;

import com.authcenter.dto.ApiResponse;
import com.authcenter.dto.CreateUserRequest;
import com.authcenter.entity.User;
import com.authcenter.service.PermissionService;
import com.authcenter.service.TokenService;
import com.authcenter.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private TokenService tokenService;
    
    @Autowired
    private PermissionService permissionService;
    
    @PostMapping
    public ApiResponse<User> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.createUser(request);
        user.setPasswordHash(null);
        return ApiResponse.success(user);
    }
    
    @GetMapping("/{userId}")
    public ApiResponse<User> getUser(@PathVariable String userId,
                                     @RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        permissionService.checkPermission(token, "USER");
        
        String requesterId = tokenService.getUserIdFromToken(token);
        if (!requesterId.equals(userId) && !permissionService.isAdmin(requesterId)) {
            return ApiResponse.error(403, "没有权限查看其他用户信息");
        }
        
        User user = userService.getUserById(userId);
        user.setPasswordHash(null);
        return ApiResponse.success(user);
    }
    
    @PutMapping("/{userId}/mfa")
    public ApiResponse<User> updateMfa(@PathVariable String userId,
                                       @RequestParam boolean enabled,
                                       @RequestParam(required = false) String mfaType,
                                       @RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        permissionService.checkPermission(token, "USER");
        
        String requesterId = tokenService.getUserIdFromToken(token);
        if (!requesterId.equals(userId) && !permissionService.isAdmin(requesterId)) {
            return ApiResponse.error(403, "没有权限修改其他用户的MFA设置");
        }
        
        User user = userService.updateUserMfa(userId, enabled, mfaType);
        user.setPasswordHash(null);
        return ApiResponse.success(user);
    }
}