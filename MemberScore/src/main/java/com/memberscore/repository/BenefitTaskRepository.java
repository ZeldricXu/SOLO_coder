package com.memberscore.repository;

import com.memberscore.entity.BenefitTask;
import com.memberscore.enums.BenefitTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BenefitTaskRepository extends JpaRepository<BenefitTask, Long> {
    
    Optional<BenefitTask> findByTaskId(String taskId);
    
    List<BenefitTask> findByStatusOrderByCreatedAtAsc(BenefitTaskStatus status);
    
    List<BenefitTask> findByMemberIdOrderByCreatedAtDesc(String memberId);
    
    List<BenefitTask> findByMemberIdAndStatus(String memberId, BenefitTaskStatus status);
    
    @Query("SELECT t FROM BenefitTask t WHERE t.status = :status AND t.nextRetryAt <= :now ORDER BY t.createdAt ASC")
    List<BenefitTask> findRetryableTasks(@Param("status") BenefitTaskStatus status, @Param("now") LocalDateTime now);
    
    @Query("SELECT t FROM BenefitTask t WHERE t.status IN (:statuses) ORDER BY t.createdAt ASC")
    List<BenefitTask> findPendingTasks(@Param("statuses") List<BenefitTaskStatus> statuses);
    
    long countByStatus(BenefitTaskStatus status);
    
    boolean existsByMemberIdAndLevelIdAndStatusIn(String memberId, String levelId, List<BenefitTaskStatus> statuses);
}
