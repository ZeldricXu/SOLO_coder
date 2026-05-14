package com.taskscheduler.repository;

import com.taskscheduler.entity.TaskLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TaskLogRepository extends JpaRepository<TaskLog, Long> {

    List<TaskLog> findByExecuteIdOrderByLogTimeAsc(String executeId);

    List<TaskLog> findByTaskIdOrderByLogTimeDesc(String taskId);

    List<TaskLog> findByLogLevel(String logLevel);

    @Query("SELECT l FROM TaskLog l WHERE l.taskId = :taskId AND l.logTime BETWEEN :startTime AND :endTime ORDER BY l.logTime DESC")
    List<TaskLog> findByTaskIdAndTimeRange(
            @Param("taskId") String taskId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query("SELECT l FROM TaskLog l WHERE l.executeId = :executeId ORDER BY l.logTime ASC")
    List<TaskLog> findLogsByExecuteId(@Param("executeId") String executeId);
}
