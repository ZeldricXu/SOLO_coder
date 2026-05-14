package com.projmanage.repository;

import com.projmanage.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, String> {
    List<Task> findByProjectId(String projectId);
    List<Task> findByTaskAssignee(String taskAssignee);
    List<Task> findByProjectIdAndTaskStatus(String projectId, String taskStatus);
    List<Task> findByMilestoneId(String milestoneId);
}
