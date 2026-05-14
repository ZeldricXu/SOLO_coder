package com.contractmgmt.repository;

import com.contractmgmt.entity.ExecutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecord, String> {

    Optional<ExecutionRecord> findByExecutionId(String executionId);

    List<ExecutionRecord> findByContractIdOrderByExecutionTimeDesc(String contractId);

    List<ExecutionRecord> findByContractIdAndExecutionType(String contractId, String executionType);

    @Query("SELECT SUM(e.executionAmount) FROM ExecutionRecord e WHERE e.contractId = :contractId")
    java.math.BigDecimal sumAmountByContractId(@Param("contractId") String contractId);

    @Query("SELECT MAX(e.executionProgress) FROM ExecutionRecord e WHERE e.contractId = :contractId")
    Integer findMaxProgressByContractId(@Param("contractId") String contractId);
}
