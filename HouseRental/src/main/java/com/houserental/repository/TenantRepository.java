package com.houserental.repository;

import com.houserental.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    Optional<Tenant> findByTenantId(String tenantId);

    Optional<Tenant> findByTenantPhone(String tenantPhone);

    List<Tenant> findByTenantStatus(String status);

    @Query("SELECT COUNT(t) FROM Tenant t")
    long countTotalTenants();

    @Query("SELECT COUNT(t) FROM Tenant t WHERE t.tenantStatus = :status")
    long countByStatus(@Param("status") String status);

    boolean existsByTenantId(String tenantId);

    boolean existsByTenantPhone(String tenantPhone);
}
