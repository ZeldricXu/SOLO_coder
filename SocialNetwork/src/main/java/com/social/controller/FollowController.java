package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.Follow;
import com.social.entity.User;
import com.social.service.FollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/follows")
public class FollowController {

    @Autowired
    private FollowService followService;

    @PostMapping("/follow")
    public ApiResponse<Follow> followUser(@RequestBody Map<String, String> request) {
        String followerId = request.get("follower_id");
        String followingId = request.get("following_id");

        if (followerId == null || followingId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }

        Follow follow = followService.followUser(followerId, followingId);
        return ApiResponse.success(follow);
    }

    @PostMapping("/unfollow")
    public ApiResponse<Void> unfollowUser(@RequestBody Map<String, String> request) {
        String followerId = request.get("follower_id");
        String followingId = request.get("following_id");

        if (followerId == null || followingId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }

        followService.unfollowUser(followerId, followingId);
        return ApiResponse.success(null);
    }

    @GetMapping("/followers/{userId}")
    public ApiResponse<List<User>> getFollowers(@PathVariable String userId) {
        List<User> followers = followService.getFollowers(userId);
        return ApiResponse.success(followers);
    }

    @GetMapping("/following/{userId}")
    public ApiResponse<List<User>> getFollowing(@PathVariable String userId) {
        List<User> following = followService.getFollowing(userId);
        return ApiResponse.success(following);
    }

    @GetMapping("/check/{followerId}/{followingId}")
    public ApiResponse<Map<String, Object>> checkFollowing(@PathVariable String followerId, @PathVariable String followingId) {
        Map<String, Object> result = new HashMap<>();
        result.put("is_following", followService.isFollowing(followerId, followingId));
        return ApiResponse.success(result);
    }

    @GetMapping("/count/{userId}")
    public ApiResponse<Map<String, Object>> getFollowCounts(@PathVariable String userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("follower_count", followService.getFollowerCount(userId));
        result.put("following_count", followService.getFollowingCount(userId));
        return ApiResponse.success(result);
    }
}
