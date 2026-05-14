package com.parking.repository;

import com.parking.entity.SettlementTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementTaskRepository extends JpaRepository<SettlementTask, Long> {

    Optional<SettlementTask> findByTaskId(String taskId);

    Optional<SettlementTask> findByEntryId(String entryId);

    List<SettlementTask> findByTaskStatusOrderByCreatedAtAsc(String taskStatus);

    List<SettlementTask> findByTaskStatusInOrderByCreatedAtAsc(List<String> taskStatuses);

    @Query("SELECT t FROM SettlementTask t WHERE t.taskStatus = :status AND t.nextRetryAt <= :now ORDER BY t.createdAt ASC")
    List<SettlementTask> findPendingRetryTasks(@Param("status") String status, @Param("now") LocalDateTime now);

    @Query("SELECT t FROM SettlementTask t WHERE t.taskStatus IN ('pending', 'retry') ORDER BY t.createdAt ASC")
    List<SettlementTask> findAllPendingTasks();

    @Modifying
    @Query("UPDATE SettlementTask t SET t.taskStatus = :status, t.completedAt = :completedAt WHERE t.taskId = :taskId")
    int updateTaskStatus(@Param("taskId") String taskId, @Param("status") String status, @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Query("UPDATE SettlementTask t SET t.taskStatus = :status, t.retryAttempts = :retryAttempts, t.nextRetryAt = :nextRetryAt, t.errorMessage = :errorMessage WHERE t.taskId = :taskId")
    int updateTaskForRetry(@Param("taskId") String taskId, @Param("status") String status, @Param("retryAttempts") Integer retryAttempts, @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("errorMessage") String errorMessage);

    boolean existsByEntryIdAndTaskStatusIn(String entryId, List<String> statuses);

    long countByTaskStatus(String taskStatus);
}
