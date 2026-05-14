package com.taskscheduler.repository;

import com.taskscheduler.entity.TaskConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskConfigRepository extends JpaRepository<TaskConfig, String> {

    Optional<TaskConfig> findByTaskId(String taskId);

    List<TaskConfig> findByEnabledTrue();

    List<TaskConfig> findByTaskType(String taskType);

    @Query("SELECT t FROM TaskConfig t WHERE t.enabled = true AND t.cronExpression IS NOT NULL")
    List<TaskConfig> findAllScheduledTasks();

    boolean existsByTaskId(String taskId);
}
