package com.social.controller;

import com.social.config.PrivacyLevelConfig.PrivacyLevelDefinition;
import com.social.dto.ApiResponse;
import com.social.entity.PrivacySetting;
import com.social.service.PrivacyLevelManager;
import com.social.service.PrivacyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/privacy")
public class PrivacyController {

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private PrivacyLevelManager privacyLevelManager;

    @GetMapping("/{userId}")
    public ApiResponse<PrivacySetting> getPrivacySetting(@PathVariable String userId) {
        PrivacySetting setting = privacyService.getPrivacySetting(userId);
        return ApiResponse.success(setting);
    }

    @PutMapping("/{userId}")
    public ApiResponse<PrivacySetting> updatePrivacySetting(@PathVariable String userId, @RequestBody Map<String, String> request) {
        String friendRequestPolicy = request.get("friend_request_policy");
        String messagePolicy = request.get("message_policy");
        String postVisibility = request.get("post_visibility");
        String profileVisibility = request.get("profile_visibility");

        PrivacySetting setting = privacyService.updatePrivacySetting(
                userId, friendRequestPolicy, messagePolicy, postVisibility, profileVisibility);
        return ApiResponse.success(setting);
    }

    @GetMapping("/check/friend-request/{targetUserId}")
    public ApiResponse<Map<String, Object>> checkFriendRequestPermission(@PathVariable String targetUserId) {
        Map<String, Object> result = new HashMap<>();
        result.put("can_receive_request", privacyService.canReceiveFriendRequests(targetUserId));
        result.put("current_policy", privacyService.getFriendRequestPolicy(targetUserId));
        return ApiResponse.success(result);
    }

    @PostMapping("/check/message")
    public ApiResponse<Map<String, Object>> checkMessagePermission(@RequestBody Map<String, Object> request) {
        String fromUserId = (String) request.get("from_user");
        String toUserId = (String) request.get("to_user");
        Boolean isFriend = (Boolean) request.get("is_friend");

        Map<String, Object> result = new HashMap<>();
        result.put("can_send_message", privacyService.canReceiveMessage(
                fromUserId, toUserId, isFriend != null && isFriend));
        result.put("current_policy", privacyService.getMessagePolicy(toUserId));
        return ApiResponse.success(result);
    }

    @PostMapping("/check/post")
    public ApiResponse<Map<String, Object>> checkPostVisibility(@RequestBody Map<String, Object> request) {
        String viewerId = (String) request.get("viewer_id");
        String authorId = (String) request.get("author_id");

        Map<String, Object> result = new HashMap<>();
        result.put("can_view_post", privacyService.canViewPost(viewerId, authorId));
        result.put("current_visibility", privacyService.getPostVisibility(authorId));
        return ApiResponse.success(result);
    }

    @PostMapping("/check/profile")
    public ApiResponse<Map<String, Object>> checkProfileVisibility(@RequestBody Map<String, Object> request) {
        String viewerId = (String) request.get("viewer_id");
        String targetUserId = (String) request.get("target_user_id");

        Map<String, Object> result = new HashMap<>();
        result.put("can_view_profile", privacyService.canViewProfile(viewerId, targetUserId));
        result.put("current_visibility", privacyService.getProfileVisibility(targetUserId));
        return ApiResponse.success(result);
    }

    @GetMapping("/post-visibility/{userId}")
    public ApiResponse<Map<String, Object>> getPostVisibility(@PathVariable String userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("visibility", privacyService.getPostVisibility(userId));
        return ApiResponse.success(result);
    }

    @GetMapping("/levels/friend-request-policies")
    public ApiResponse<List<PrivacyLevelDefinition>> getAllFriendRequestPolicies() {
        List<PrivacyLevelDefinition> policies = privacyLevelManager.getAllEnabledFriendRequestPolicies();
        return ApiResponse.success(policies);
    }

    @GetMapping("/levels/message-policies")
    public ApiResponse<List<PrivacyLevelDefinition>> getAllMessagePolicies() {
        List<PrivacyLevelDefinition> policies = privacyLevelManager.getAllEnabledMessagePolicies();
        return ApiResponse.success(policies);
    }

    @GetMapping("/levels/post-visibilities")
    public ApiResponse<List<PrivacyLevelDefinition>> getAllPostVisibilities() {
        List<PrivacyLevelDefinition> visibilities = privacyLevelManager.getAllEnabledPostVisibilities();
        return ApiResponse.success(visibilities);
    }

    @GetMapping("/levels/profile-visibilities")
    public ApiResponse<List<PrivacyLevelDefinition>> getAllProfileVisibilities() {
        List<PrivacyLevelDefinition> visibilities = privacyLevelManager.getAllEnabledProfileVisibilities();
        return ApiResponse.success(visibilities);
    }

    @GetMapping("/levels/defaults")
    public ApiResponse<Map<String, Object>> getDefaultPrivacyLevels() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("friend_request_policy", privacyLevelManager.getDefaultFriendRequestPolicy());
        defaults.put("message_policy", privacyLevelManager.getDefaultMessagePolicy());
        defaults.put("post_visibility", privacyLevelManager.getDefaultPostVisibility());
        defaults.put("profile_visibility", privacyLevelManager.getDefaultProfileVisibility());
        return ApiResponse.success(defaults);
    }

    @GetMapping("/levels/all")
    public ApiResponse<Map<String, Object>> getAllPrivacyLevels() {
        Map<String, Object> allLevels = new HashMap<>();
        allLevels.put("friend_request_policies", privacyLevelManager.getAllFriendRequestPolicies());
        allLevels.put("message_policies", privacyLevelManager.getAllMessagePolicies());
        allLevels.put("post_visibilities", privacyLevelManager.getAllPostVisibilities());
        allLevels.put("profile_visibilities", privacyLevelManager.getAllProfileVisibilities());
        return ApiResponse.success(allLevels);
    }
}
