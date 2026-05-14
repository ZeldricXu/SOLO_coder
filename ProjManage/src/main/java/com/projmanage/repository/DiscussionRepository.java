package com.projmanage.repository;

import com.projmanage.model.Discussion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DiscussionRepository extends JpaRepository<Discussion, String> {
    List<Discussion> findByProjectId(String projectId);
    List<Discussion> findByTaskId(String taskId);
    List<Discussion> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
