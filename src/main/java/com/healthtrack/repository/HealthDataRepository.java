package com.healthtrack.repository;

import com.healthtrack.entity.HealthData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface HealthDataRepository extends JpaRepository<HealthData, String> {
    
    List<HealthData> findByUserId(String userId);
    
    List<HealthData> findByUserIdAndDataType(String userId, String dataType);
    
    List<HealthData> findByUserIdAndCollectedAtBetween(String userId, LocalDateTime start, LocalDateTime end);
    
    List<HealthData> findByUserIdAndDataTypeAndCollectedAtBetween(String userId, String dataType, LocalDateTime start, LocalDateTime end);
    
    Optional<HealthData> findFirstByUserIdAndDataTypeOrderByCollectedAtDesc(String userId, String dataType);
    
    @Query("SELECT AVG(h.dataValue) FROM HealthData h WHERE h.userId = :userId AND h.dataType = :dataType AND h.collectedAt BETWEEN :start AND :end")
    Double findAverageValueByUserIdAndDataTypeAndTimeRange(@Param("userId") String userId, @Param("dataType") String dataType, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    @Query("SELECT MAX(h.dataValue) FROM HealthData h WHERE h.userId = :userId AND h.dataType = :dataType AND h.collectedAt BETWEEN :start AND :end")
    Double findMaxValueByUserIdAndDataTypeAndTimeRange(@Param("userId") String userId, @Param("dataType") String dataType, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    @Query("SELECT MIN(h.dataValue) FROM HealthData h WHERE h.userId = :userId AND h.dataType = :dataType AND h.collectedAt BETWEEN :start AND :end")
    Double findMinValueByUserIdAndDataTypeAndTimeRange(@Param("userId") String userId, @Param("dataType") String dataType, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    long countByUserIdAndQuality(String userId, String quality);
    
    long countByUserIdAndCollectedAtBetween(String userId, LocalDateTime start, LocalDateTime end);
}
