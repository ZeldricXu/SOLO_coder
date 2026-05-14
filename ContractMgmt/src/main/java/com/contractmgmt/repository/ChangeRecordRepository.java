package com.contractmgmt.repository;

import com.contractmgmt.entity.ChangeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChangeRecordRepository extends JpaRepository<ChangeRecord, String> {

    Optional<ChangeRecord> findByChangeId(String changeId);

    List<ChangeRecord> findByContractIdOrderByChangeTimeDesc(String contractId);

    List<ChangeRecord> findByChangeStatus(String changeStatus);

    List<ChangeRecord> findByChangeType(String changeType);
}
