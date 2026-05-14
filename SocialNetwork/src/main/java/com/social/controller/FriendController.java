package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.FriendRequest;
import com.social.entity.Friendship;
import com.social.entity.User;
import com.social.service.FriendRequestCheckService;
import com.social.service.FriendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendController {

    @Autowired
    private FriendService friendService;

    @PostMapping("/request")
    public ApiResponse<Map<String, Object>> sendFriendRequest(@RequestBody Map<String, Object> request) {
        String fromUser = (String) request.get("from_user");
        String toUser = (String) request.get("to_user");

        FriendRequest friendRequest = friendService.sendFriendRequest(fromUser, toUser);
        
        Map<String, Object> data = new HashMap<>();
        data.put("request_id", friendRequest.getRequestId());
        data.put("status", friendRequest.getRequestStatus());

        return ApiResponse.success(data);
    }

    @PostMapping("/request/{requestId}/accept")
    public ApiResponse<Friendship> acceptFriendRequest(@PathVariable String requestId, @RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }
        Friendship friendship = friendService.acceptFriendRequest(requestId, userId);
        return ApiResponse.success(friendship);
    }

    @PostMapping("/request/{requestId}/reject")
    public ApiResponse<Void> rejectFriendRequest(@PathVariable String requestId, @RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }
        friendService.rejectFriendRequest(requestId, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{userId}")
    public ApiResponse<List<User>> getFriends(@PathVariable String userId) {
        List<User> friends = friendService.getFriends(userId);
        return ApiResponse.success(friends);
    }

    @GetMapping("/{userId}/pending")
    public ApiResponse<List<FriendRequest>> getPendingRequests(@PathVariable String userId) {
        List<FriendRequest> requests = friendService.getPendingRequests(userId);
        return ApiResponse.success(requests);
    }

    @GetMapping("/{userId}/sent")
    public ApiResponse<List<FriendRequest>> getSentRequests(@PathVariable String userId) {
        List<FriendRequest> requests = friendService.getSentRequests(userId);
        return ApiResponse.success(requests);
    }

    @DeleteMapping("/{userId}/friends/{friendId}")
    public ApiResponse<Void> removeFriend(@PathVariable String userId, @PathVariable String friendId) {
        friendService.removeFriend(userId, friendId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{userId1}/check/{userId2}")
    public ApiResponse<Map<String, Object>> checkFriendship(@PathVariable String userId1, @PathVariable String userId2) {
        Map<String, Object> result = new HashMap<>();
        result.put("is_friend", friendService.isFriend(userId1, userId2));
        return ApiResponse.success(result);
    }

    @GetMapping("/check")
    public ApiResponse<FriendRequestCheckService.FriendRequestCheckResult> checkFriendRequestStatus(
            @RequestParam String from_user, 
            @RequestParam String to_user) {
        FriendRequestCheckService.FriendRequestCheckResult result = 
                friendService.checkFriendRequestStatus(from_user, to_user);
        return ApiResponse.success(result);
    }
}
