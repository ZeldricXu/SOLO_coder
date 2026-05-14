package com.taskscheduler.repository;

import com.taskscheduler.entity.ExecuteRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExecuteRecordRepository extends JpaRepository<ExecuteRecord, String> {

    Optional<ExecuteRecord> findByExecuteId(String executeId);

    List<ExecuteRecord> findByTaskIdOrderByExecuteTimeDesc(String taskId);

    List<ExecuteRecord> findByTaskIdAndExecuteStatus(String taskId, String executeStatus);

    List<ExecuteRecord> findByExecuteStatus(String executeStatus);

    @Query("SELECT e FROM ExecuteRecord e WHERE e.taskId = :taskId AND e.executeTime BETWEEN :startTime AND :endTime ORDER BY e.executeTime DESC")
    List<ExecuteRecord> findByTaskIdAndTimeRange(
            @Param("taskId") String taskId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query("SELECT COUNT(e) FROM ExecuteRecord e WHERE e.taskId = :taskId AND e.executeStatus = :status")
    long countByTaskIdAndStatus(@Param("taskId") String taskId, @Param("status") String status);

    @Query("SELECT AVG(e.executeDurationSeconds) FROM ExecuteRecord e WHERE e.taskId = :taskId AND e.executeStatus = 'success'")
    Double getAverageExecutionTime(@Param("taskId") String taskId);

    @Query("SELECT e FROM ExecuteRecord e WHERE e.executeStatus = :status AND e.startTime IS NOT NULL ORDER BY e.startTime ASC")
    List<ExecuteRecord> findRunningTasks(@Param("status") String status);
}
