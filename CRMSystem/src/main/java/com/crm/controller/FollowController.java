package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.dto.FollowRequest;
import com.crm.entity.Follow;
import com.crm.service.FollowService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/follows")
public class FollowController {

    @Autowired
    private FollowService followService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createFollow(@Valid @RequestBody FollowRequest request) {
        Map<String, Object> result = followService.createFollow(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/{followId}")
    public ApiResponse<Follow> getFollowById(@PathVariable String followId) {
        Follow follow = followService.getFollowById(followId);
        return ApiResponse.success(follow);
    }

    @GetMapping
    public ApiResponse<List<Follow>> getAllFollows() {
        List<Follow> follows = followService.getAllFollows();
        return ApiResponse.success(follows);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<Follow>> getCustomerFollows(@PathVariable String customerId) {
        List<Follow> follows = followService.getCustomerFollows(customerId);
        return ApiResponse.success(follows);
    }

    @GetMapping("/sales/{salesId}")
    public ApiResponse<List<Follow>> getSalesFollows(@PathVariable String salesId) {
        List<Follow> follows = followService.getSalesFollows(salesId);
        return ApiResponse.success(follows);
    }
}
