package com.social.service;

import com.social.entity.Group;
import com.social.entity.GroupMember;
import com.social.entity.User;
import com.social.exception.SocialNetworkException;
import com.social.repository.GroupMemberRepository;
import com.social.repository.GroupRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class GroupService {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private UserService userService;

    @Transactional
    public Group createGroup(String ownerId, String groupName, String groupDescription, String groupAvatar, int maxMembers) {
        if (ownerId == null) {
            throw new SocialNetworkException(400, "群主ID不能为空");
        }
        
        if (groupName == null || groupName.trim().isEmpty()) {
            throw new SocialNetworkException(400, "群组名称不能为空");
        }

        User owner = userService.getUserById(ownerId);
        
        if (!"active".equals(owner.getUserStatus())) {
            throw new SocialNetworkException(400, "用户状态不可用");
        }

        Group group = new Group();
        group.setGroupId(IdGenerator.generateGroupId());
        group.setGroupName(groupName);
        group.setGroupDescription(groupDescription);
        group.setGroupAvatar(groupAvatar);
        group.setOwnerId(ownerId);
        group.setGroupStatus("active");
        group.setMaxMembers(maxMembers > 0 ? maxMembers : 500);
        group.setCurrentMembers(0);

        Group savedGroup = groupRepository.save(group);
        
        addGroupMember(savedGroup.getGroupId(), ownerId, "owner");

        return savedGroup;
    }

    @Transactional
    public GroupMember addGroupMember(String groupId, String userId, String role) {
        Group group = getGroupById(groupId);
        User user = userService.getUserById(userId);

        if (!"active".equals(group.getGroupStatus())) {
            throw new SocialNetworkException(400, "群组状态不可用");
        }

        if (!"active".equals(user.getUserStatus())) {
            throw new SocialNetworkException(400, "用户状态不可用");
        }

        if (group.getCurrentMembers() >= group.getMaxMembers()) {
            throw new SocialNetworkException(400, "群组人数已满");
        }

        if (isGroupMember(groupId, userId)) {
            throw new SocialNetworkException(400, "已经是群组成员");
        }

        GroupMember member = new GroupMember();
        member.setMemberId(IdGenerator.generateGroupMemberId());
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setMemberRole(role != null ? role : "member");
        member.setMemberStatus("active");

        GroupMember savedMember = groupMemberRepository.save(member);

        group.setCurrentMembers(group.getCurrentMembers() + 1);
        groupRepository.save(group);

        return savedMember;
    }

    @Transactional
    public void removeGroupMember(String groupId, String userId) {
        GroupMember member = groupMemberRepository.findByGroupIdAndUserIdAndMemberStatus(
                groupId, userId, "active")
                .orElseThrow(() -> new SocialNetworkException(404, "成员不存在"));

        member.setMemberStatus("removed");
        groupMemberRepository.save(member);

        Group group = getGroupById(groupId);
        group.setCurrentMembers(group.getCurrentMembers() - 1);
        groupRepository.save(group);
    }

    public boolean isGroupMember(String groupId, String userId) {
        return groupMemberRepository.existsByGroupIdAndUserIdAndMemberStatus(groupId, userId, "active");
    }

    public Group getGroupById(String groupId) {
        return groupRepository.findByGroupId(groupId)
                .orElseThrow(() -> new SocialNetworkException(404, "群组不存在: " + groupId));
    }

    public List<Group> getGroupsByOwner(String ownerId) {
        return groupRepository.findByOwnerIdAndGroupStatus(ownerId, "active");
    }

    public List<Group> getUserGroups(String userId) {
        List<GroupMember> memberships = groupMemberRepository.findByUserIdAndMemberStatus(userId, "active");
        List<Group> groups = new ArrayList<>();
        for (GroupMember m : memberships) {
            try {
                groups.add(getGroupById(m.getGroupId()));
            } catch (Exception e) {
            }
        }
        return groups;
    }

    public List<User> getGroupMembers(String groupId) {
        List<GroupMember> members = groupMemberRepository.findByGroupIdAndMemberStatus(groupId, "active");
        List<User> users = new ArrayList<>();
        for (GroupMember m : members) {
            try {
                users.add(userService.getUserById(m.getUserId()));
            } catch (Exception e) {
            }
        }
        return users;
    }

    @Transactional
    public Group updateGroupInfo(String groupId, String groupName, String groupDescription, String groupAvatar) {
        Group group = getGroupById(groupId);
        
        if (groupName != null && !groupName.trim().isEmpty()) {
            group.setGroupName(groupName);
        }
        if (groupDescription != null) {
            group.setGroupDescription(groupDescription);
        }
        if (groupAvatar != null) {
            group.setGroupAvatar(groupAvatar);
        }

        return groupRepository.save(group);
    }

    @Transactional
    public Group deactivateGroup(String groupId) {
        Group group = getGroupById(groupId);
        group.setGroupStatus("inactive");
        return groupRepository.save(group);
    }
}
