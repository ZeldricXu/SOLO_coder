package com.projmanage.repository;

import com.projmanage.model.ProjectActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectActivityRepository extends JpaRepository<ProjectActivity, String> {
    Optional<ProjectActivity> findByProjectId(String projectId);
    List<ProjectActivity> findByActivityLevel(String activityLevel);
}
