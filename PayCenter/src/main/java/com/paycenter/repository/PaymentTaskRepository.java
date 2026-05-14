package com.paycenter.repository;

import com.paycenter.entity.PaymentTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PaymentTaskRepository extends JpaRepository<PaymentTask, String> {
    List<PaymentTask> findByStatusIn(List<PaymentTask.TaskStatus> statuses);
    
    List<PaymentTask> findByStatusAndNextRetryAtBefore(PaymentTask.TaskStatus status, LocalDateTime time);
    
    @Query("SELECT t FROM PaymentTask t WHERE t.status = :status ORDER BY t.createdAt ASC")
    List<PaymentTask> findPendingTasks(@Param("status") PaymentTask.TaskStatus status);
    
    List<PaymentTask> findByTransactionId(String transactionId);
}
