package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.Group;
import com.social.entity.GroupMember;
import com.social.entity.User;
import com.social.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    @Autowired
    private GroupService groupService;

    @PostMapping("/create")
    public ApiResponse<Group> createGroup(@RequestBody Map<String, Object> request) {
        String ownerId = (String) request.get("owner_id");
        String groupName = (String) request.get("group_name");
        String groupDescription = (String) request.get("group_description");
        String groupAvatar = (String) request.get("group_avatar");
        Integer maxMembers = request.get("max_members") != null 
                ? ((Number) request.get("max_members")).intValue() : 500;

        if (ownerId == null || groupName == null || groupName.trim().isEmpty()) {
            return ApiResponse.error(400, "群主ID和群组名称不能为空");
        }

        Group group = groupService.createGroup(ownerId, groupName, groupDescription, groupAvatar, maxMembers);
        return ApiResponse.success(group);
    }

    @GetMapping("/{groupId}")
    public ApiResponse<Group> getGroup(@PathVariable String groupId) {
        Group group = groupService.getGroupById(groupId);
        return ApiResponse.success(group);
    }

    @GetMapping("/owner/{ownerId}")
    public ApiResponse<List<Group>> getGroupsByOwner(@PathVariable String ownerId) {
        List<Group> groups = groupService.getGroupsByOwner(ownerId);
        return ApiResponse.success(groups);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<Group>> getUserGroups(@PathVariable String userId) {
        List<Group> groups = groupService.getUserGroups(userId);
        return ApiResponse.success(groups);
    }

    @PostMapping("/{groupId}/members")
    public ApiResponse<GroupMember> addGroupMember(@PathVariable String groupId, @RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        String role = request.get("role");

        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }

        GroupMember member = groupService.addGroupMember(groupId, userId, role);
        return ApiResponse.success(member);
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ApiResponse<Void> removeGroupMember(@PathVariable String groupId, @PathVariable String userId) {
        groupService.removeGroupMember(groupId, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{groupId}/members")
    public ApiResponse<List<User>> getGroupMembers(@PathVariable String groupId) {
        List<User> members = groupService.getGroupMembers(groupId);
        return ApiResponse.success(members);
    }

    @GetMapping("/{groupId}/check/{userId}")
    public ApiResponse<Map<String, Object>> checkGroupMember(@PathVariable String groupId, @PathVariable String userId) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("is_member", groupService.isGroupMember(groupId, userId));
        return ApiResponse.success(result);
    }

    @PutMapping("/{groupId}")
    public ApiResponse<Group> updateGroupInfo(@PathVariable String groupId, @RequestBody Map<String, Object> request) {
        String groupName = (String) request.get("group_name");
        String groupDescription = (String) request.get("group_description");
        String groupAvatar = (String) request.get("group_avatar");

        Group group = groupService.updateGroupInfo(groupId, groupName, groupDescription, groupAvatar);
        return ApiResponse.success(group);
    }

    @PostMapping("/{groupId}/deactivate")
    public ApiResponse<Group> deactivateGroup(@PathVariable String groupId) {
        Group group = groupService.deactivateGroup(groupId);
        return ApiResponse.success(group);
    }
}
