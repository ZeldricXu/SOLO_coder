package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.User;
import com.social.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public ApiResponse<User> registerUser(@RequestBody Map<String, Object> request) {
        String userName = (String) request.get("user_name");
        String userPhone = (String) request.get("user_phone");
        String userAvatar = (String) request.get("user_avatar");

        if (userName == null || userName.trim().isEmpty()) {
            return ApiResponse.error(400, "用户名不能为空");
        }

        User user = userService.registerUser(userName, userPhone, userAvatar);
        return ApiResponse.success(user);
    }

    @GetMapping("/{userId}")
    public ApiResponse<User> getUser(@PathVariable String userId) {
        User user = userService.getUserById(userId);
        return ApiResponse.success(user);
    }

    @GetMapping("/list")
    public ApiResponse<List<User>> listActiveUsers() {
        List<User> users = userService.getAllActiveUsers();
        return ApiResponse.success(users);
    }

    @PutMapping("/{userId}")
    public ApiResponse<User> updateUser(@PathVariable String userId, @RequestBody Map<String, Object> request) {
        String userName = (String) request.get("user_name");
        String userPhone = (String) request.get("user_phone");
        String userAvatar = (String) request.get("user_avatar");

        User user = userService.updateUserInfo(userId, userName, userPhone, userAvatar);
        return ApiResponse.success(user);
    }

    @PutMapping("/{userId}/status")
    public ApiResponse<User> updateUserStatus(@PathVariable String userId, @RequestBody Map<String, String> request) {
        String status = request.get("user_status");
        if (status == null) {
            return ApiResponse.error(400, "状态不能为空");
        }
        User user = userService.updateUserStatus(userId, status);
        return ApiResponse.success(user);
    }

    @PutMapping("/{userId}/online")
    public ApiResponse<User> setUserOnline(@PathVariable String userId, @RequestBody Map<String, Boolean> request) {
        Boolean online = request.get("online");
        if (online == null) {
            return ApiResponse.error(400, "在线状态不能为空");
        }
        User user = userService.setUserOnline(userId, online);
        return ApiResponse.success(user);
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> getActiveUserCount() {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("active_users", userService.countActiveUsers());
        return ApiResponse.success(result);
    }
}
