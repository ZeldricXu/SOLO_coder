package com.contractmgmt.repository;

import com.contractmgmt.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContractRepository extends JpaRepository<Contract, String> {

    Optional<Contract> findByContractId(String contractId);

    List<Contract> findByContractStatus(String contractStatus);

    List<Contract> findByContractType(String contractType);

    @Query("SELECT c FROM Contract c WHERE c.contractEnd BETWEEN :startDate AND :endDate AND c.contractStatus IN :statuses")
    List<Contract> findContractsExpiringBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("statuses") List<String> statuses);

    @Query("SELECT c FROM Contract c WHERE c.contractEnd <= :date AND c.contractStatus IN :statuses")
    List<Contract> findExpiredContracts(
            @Param("date") LocalDate date,
            @Param("statuses") List<String> statuses);

    @Query("SELECT COUNT(c) FROM Contract c WHERE c.contractStatus = :status")
    Long countByContractStatus(@Param("status") String status);

    @Query("SELECT SUM(c.contractAmount) FROM Contract c WHERE c.contractStatus = :status")
    java.math.BigDecimal sumAmountByContractStatus(@Param("status") String status);

    @Query("SELECT c FROM Contract c WHERE c.contractStatus IN :statuses")
    List<Contract> findByContractStatusIn(@Param("statuses") List<String> statuses);

    List<Contract> findByPartyA(String partyA);

    List<Contract> findByPartyB(String partyB);
}
