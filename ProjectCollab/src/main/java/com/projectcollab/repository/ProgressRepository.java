package com.projectcollab.repository;

import com.projectcollab.entity.Progress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgressRepository extends JpaRepository<Progress, String> {
    List<Progress> findByProject_ProjectIdOrderByProgressTimeDesc(String projectId);
    List<Progress> findByTaskId(String taskId);
}
