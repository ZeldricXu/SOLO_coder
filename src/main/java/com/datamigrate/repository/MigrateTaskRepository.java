package com.datamigrate.repository;

import com.datamigrate.common.TaskStatus;
import com.datamigrate.entity.MigrateTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MigrateTaskRepository extends JpaRepository<MigrateTask, String> {

    Optional<MigrateTask> findByTaskId(String taskId);

    List<MigrateTask> findByStatus(TaskStatus status);

    List<MigrateTask> findByStatusIn(List<TaskStatus> statuses);

    @Query("SELECT t FROM MigrateTask t WHERE t.status IN ('PENDING', 'PAUSED')")
    List<MigrateTask> findPendingOrPausedTasks();

    List<MigrateTask> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    List<MigrateTask> findByTaskNameContaining(String keyword);

    long countByStatus(TaskStatus status);
}
