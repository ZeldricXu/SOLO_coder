package com.social.repository;

import com.social.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    Optional<GroupMember> findByMemberId(String memberId);
    List<GroupMember> findByGroupIdAndMemberStatus(String groupId, String status);
    List<GroupMember> findByUserIdAndMemberStatus(String userId, String status);
    Optional<GroupMember> findByGroupIdAndUserIdAndMemberStatus(String groupId, String userId, String status);
    boolean existsByGroupIdAndUserIdAndMemberStatus(String groupId, String userId, String status);
}
