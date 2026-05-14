package com.contractmgmt.repository;

import com.contractmgmt.entity.RenewalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RenewalRecordRepository extends JpaRepository<RenewalRecord, String> {

    Optional<RenewalRecord> findByRenewalId(String renewalId);

    List<RenewalRecord> findByContractIdOrderByRenewalTimeDesc(String contractId);

    List<RenewalRecord> findByOriginalContractIdOrderByRenewalTimeDesc(String originalContractId);

    List<RenewalRecord> findByRenewalStatus(String renewalStatus);

    Optional<RenewalRecord> findByOriginalContractIdAndRenewalStatus(String originalContractId, String renewalStatus);
}
