package com.contractmgmt.repository;

import com.contractmgmt.entity.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, String> {

    Optional<ApprovalRecord> findByApprovalId(String approvalId);

    List<ApprovalRecord> findByContractIdOrderByApprovalTimeDesc(String contractId);

    List<ApprovalRecord> findByContractIdAndApprovalType(String contractId, String approvalType);

    List<ApprovalRecord> findByApprover(String approver);

    List<ApprovalRecord> findByApprovalStatus(String approvalStatus);

    @Query("SELECT a FROM ApprovalRecord a WHERE a.contractId = :contractId AND a.approvalType = :approvalType ORDER BY a.approvalTime DESC")
    Optional<ApprovalRecord> findLatestByContractIdAndApprovalType(
            @Param("contractId") String contractId,
            @Param("approvalType") String approvalType);
}
