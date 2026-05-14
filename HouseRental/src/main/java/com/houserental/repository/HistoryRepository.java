package com.houserental.repository;

import com.houserental.entity.History;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HistoryRepository extends JpaRepository<History, String> {

    List<History> findByHistoryType(String historyType);

    List<History> findByRelatedId(String relatedId);

    List<History> findByRelatedType(String relatedType);

    List<History> findByHouseId(String houseId);

    List<History> findByTenantId(String tenantId);

    List<History> findByLandlordId(String landlordId);

    List<History> findByRelatedIdAndRelatedType(String relatedId, String relatedType);

    @Query("SELECT h FROM History h WHERE h.createdAt BETWEEN :start AND :end ORDER BY h.createdAt DESC")
    List<History> findByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT h FROM History h WHERE h.houseId = :houseId ORDER BY h.createdAt DESC")
    List<History> findHouseHistory(@Param("houseId") String houseId);

    @Query("SELECT h FROM History h WHERE h.tenantId = :tenantId ORDER BY h.createdAt DESC")
    List<History> findTenantHistory(@Param("tenantId") String tenantId);

    @Query("SELECT h FROM History h WHERE h.landlordId = :landlordId ORDER BY h.createdAt DESC")
    List<History> findLandlordHistory(@Param("landlordId") String landlordId);

    @Query("SELECT h FROM History h ORDER BY h.createdAt DESC LIMIT :limit")
    List<History> findRecentHistory(@Param("limit") int limit);
}
