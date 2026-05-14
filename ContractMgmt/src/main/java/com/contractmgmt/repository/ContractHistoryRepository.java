package com.contractmgmt.repository;

import com.contractmgmt.entity.ContractHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractHistoryRepository extends JpaRepository<ContractHistory, String> {

    Optional<ContractHistory> findByHistoryId(String historyId);

    List<ContractHistory> findByContractIdOrderByActionTimeDesc(String contractId);

    List<ContractHistory> findByHistoryType(String historyType);

    List<ContractHistory> findByOperator(String operator);

    List<ContractHistory> findByContractIdAndHistoryTypeOrderByActionTimeDesc(String contractId, String historyType);
}
