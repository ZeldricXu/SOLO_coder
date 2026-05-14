package com.houserental.repository;

import com.houserental.entity.Contract;
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

    List<Contract> findByHouseId(String houseId);

    List<Contract> findByTenantId(String tenantId);

    List<Contract> findByLandlordId(String landlordId);

    List<Contract> findByContractStatus(String status);

    List<Contract> findByHouseIdAndContractStatus(String houseId, String status);

    List<Contract> findByTenantIdAndContractStatus(String tenantId, String status);

    List<Contract> findByLandlordIdAndContractStatus(String landlordId, String status);

    @Query("SELECT c FROM Contract c WHERE c.contractEnd < :date AND c.contractStatus = 'active'")
    List<Contract> findExpiringContracts(@Param("date") LocalDate date);

    @Query("SELECT COUNT(c) FROM Contract c WHERE c.contractStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(c) FROM Contract c")
    long countTotalContracts();

    @Query("SELECT COUNT(c) FROM Contract c WHERE c.renewalCount > 0")
    long countRenewedContracts();

    @Query("SELECT SUM(c.contractRent) FROM Contract c WHERE c.contractStatus = 'active'")
    Double sumActiveContractRent();

    boolean existsByContractId(String contractId);
}
