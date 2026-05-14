package com.social.repository;

import com.social.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {
    Optional<Group> findByGroupId(String groupId);
    List<Group> findByOwnerIdAndGroupStatus(String ownerId, String status);
    List<Group> findByGroupStatus(String status);
}
