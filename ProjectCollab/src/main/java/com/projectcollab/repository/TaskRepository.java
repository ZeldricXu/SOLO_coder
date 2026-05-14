package com.projectcollab.repository;

import com.projectcollab.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, String> {
    List<Task> findByProject_ProjectId(String projectId);
    List<Task> findByTaskAssignee(String assignee);
    List<Task> findByTaskStatus(String status);
    List<Task> findByProject_ProjectIdAndTaskStatus(String projectId, String status);
    List<Task> findByProject_ProjectIdAndTaskStage(String projectId, String stage);
    int countByProject_ProjectIdAndTaskStatus(String projectId, String status);
}
