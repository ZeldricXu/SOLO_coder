package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.dto.UserCreateRequest;
import com.movie.entity.User;
import com.movie.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping
    public ApiResponse<List<User>> list() {
        return ApiResponse.success(userService.getAllUsers());
    }

    @GetMapping("/{userId}")
    public ApiResponse<User> get(@PathVariable String userId) {
        return ApiResponse.success(userService.getUserOrThrow(userId));
    }

    @PostMapping
    public ApiResponse<User> create(@RequestBody UserCreateRequest request) {
        return ApiResponse.success(userService.createUser(request));
    }

    @PutMapping("/{userId}")
    public ApiResponse<User> update(@PathVariable String userId, @RequestBody UserCreateRequest request) {
        return ApiResponse.success(userService.updateUser(userId, request));
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(@PathVariable String userId) {
        userService.deleteUser(userId);
        return ApiResponse.success(null);
    }
}
