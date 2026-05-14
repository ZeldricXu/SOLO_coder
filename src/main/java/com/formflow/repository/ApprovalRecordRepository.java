package com.formflow.repository;

import com.formflow.entity.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {

    Optional<ApprovalRecord> findByApprovalId(String approvalId);

    List<ApprovalRecord> findByInstanceId(String instanceId);

    List<ApprovalRecord> findByInstanceIdOrderBySortOrderAsc(String instanceId);

    List<ApprovalRecord> findByFormId(String formId);

    List<ApprovalRecord> findByFormIdOrderBySortOrderAsc(String formId);

    List<ApprovalRecord> findByApproverId(String approverId);

    List<ApprovalRecord> findByInstanceIdAndNodeId(String instanceId, String nodeId);

    List<ApprovalRecord> findByTaskId(String taskId);

    @Query("SELECT COUNT(r) FROM ApprovalRecord r WHERE r.instanceId = :instanceId")
    Long countByInstanceId(@Param("instanceId") String instanceId);

    @Query("SELECT MAX(r.sortOrder) FROM ApprovalRecord r WHERE r.instanceId = :instanceId")
    Integer findMaxSortOrderByInstanceId(@Param("instanceId") String instanceId);
}
