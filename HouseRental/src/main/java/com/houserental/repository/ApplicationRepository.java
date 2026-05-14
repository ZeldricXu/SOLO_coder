package com.houserental.repository;

import com.houserental.entity.LeaseApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<LeaseApplication, String> {

    Optional<LeaseApplication> findByApplicationId(String applicationId);

    List<LeaseApplication> findByHouseId(String houseId);

    List<LeaseApplication> findByTenantId(String tenantId);

    List<LeaseApplication> findByLandlordId(String landlordId);

    List<LeaseApplication> findByApplicationStatus(String status);

    List<LeaseApplication> findByHouseIdAndApplicationStatus(String houseId, String status);

    List<LeaseApplication> findByTenantIdAndApplicationStatus(String tenantId, String status);

    List<LeaseApplication> findByLandlordIdAndApplicationStatus(String landlordId, String status);

    @Query("SELECT COUNT(a) FROM LeaseApplication a WHERE a.applicationStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(a) FROM LeaseApplication a")
    long countTotalApplications();

    @Query("SELECT COUNT(a) FROM LeaseApplication a WHERE a.applicationTime BETWEEN :start AND :end")
    long countByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(a) FROM LeaseApplication a WHERE a.applicationStatus = :status AND a.applicationTime BETWEEN :start AND :end")
    long countByStatusAndTimeRange(@Param("status") String status, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    boolean existsByApplicationId(String applicationId);
}
