package com.projectcollab.repository;

import com.projectcollab.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, String> {
    List<ProjectMember> findByProject_ProjectId(String projectId);
    List<ProjectMember> findByUserId(String userId);
    List<ProjectMember> findByProject_ProjectIdAndMemberStatus(String projectId, String status);
    Optional<ProjectMember> findByProject_ProjectIdAndUserId(String projectId, String userId);
}
