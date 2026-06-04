package com.proteinviewer.repository;

import com.proteinviewer.model.BatchTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BatchTaskRepository extends JpaRepository<BatchTask, Long> {

    Optional<BatchTask> findByTaskId(String taskId);

    List<BatchTask> findByStatusIn(List<String> statuses);

    List<BatchTask> findByHeartbeatAtBefore(Instant threshold);

    @Query("SELECT t FROM BatchTask t WHERE t.status = :status ORDER BY t.priority DESC, t.createdAt ASC")
    List<BatchTask> findByStatusOrderByPriorityDescCreatedAtAsc(@Param("status") String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM BatchTask t WHERE t.taskId = :taskId")
    Optional<BatchTask> findByTaskIdWithLock(@Param("taskId") String taskId);

    @Query(value = "SELECT * FROM batch_tasks WHERE status = 'PENDING' ORDER BY priority DESC, created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<BatchTask> claimNextPendingTask();

    void deleteByCompletedAtBeforeAndStatus(Instant threshold, String status);
}
