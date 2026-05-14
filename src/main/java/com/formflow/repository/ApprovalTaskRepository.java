package com.formflow.repository;

import com.formflow.entity.ApprovalTask;
import com.formflow.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, Long> {

    Optional<ApprovalTask> findByTaskId(String taskId);

    List<ApprovalTask> findByInstanceId(String instanceId);

    List<ApprovalTask> findByApproverId(String approverId);

    List<ApprovalTask> findByApproverIdOrderByAssignedTimeDesc(String approverId);

    List<ApprovalTask> findByApproverIdAndTaskStatus(String approverId, TaskStatus taskStatus);

    List<ApprovalTask> findByApproverIdAndTaskStatusOrderByAssignedTimeDesc(String approverId, TaskStatus taskStatus);

    List<ApprovalTask> findByFormId(String formId);

    List<ApprovalTask> findByInstanceIdAndTaskStatus(String instanceId, TaskStatus taskStatus);

    List<ApprovalTask> findByInstanceIdAndNodeId(String instanceId, String nodeId);

    @Query("SELECT COUNT(t) FROM ApprovalTask t WHERE t.approverId = :approverId AND t.taskStatus = :status")
    Long countByApproverIdAndTaskStatus(@Param("approverId") String approverId, @Param("status") TaskStatus status);

    @Query("SELECT COUNT(t) FROM ApprovalTask t WHERE t.approverId = :approverId")
    Long countByApproverId(@Param("approverId") String approverId);

    void deleteByInstanceId(String instanceId);
}
