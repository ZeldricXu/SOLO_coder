package com.adplatform.repository;

import com.adplatform.entity.AdEffect;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdEffectRepository extends JpaRepository<AdEffect, String> {
    Optional<AdEffect> findByEffectId(String effectId);
    List<AdEffect> findByAdId(String adId);
    Optional<AdEffect> findByAdIdAndStatDate(String adId, LocalDate statDate);
    List<AdEffect> findByAdIdAndStatDateBetween(String adId, LocalDate startDate, LocalDate endDate);
    
    @Query("SELECT SUM(e.exposureCount) FROM AdEffect e WHERE e.adId = :adId AND e.statDate BETWEEN :start AND :end")
    Long sumExposureCountByAdIdAndDateRange(@Param("adId") String adId, @Param("start") LocalDate start, @Param("end") LocalDate end);
    
    @Query("SELECT SUM(e.clickCount) FROM AdEffect e WHERE e.adId = :adId AND e.statDate BETWEEN :start AND :end")
    Long sumClickCountByAdIdAndDateRange(@Param("adId") String adId, @Param("start") LocalDate start, @Param("end") LocalDate end);
    
    @Query("SELECT SUM(e.conversionCount) FROM AdEffect e WHERE e.adId = :adId AND e.statDate BETWEEN :start AND :end")
    Long sumConversionCountByAdIdAndDateRange(@Param("adId") String adId, @Param("start") LocalDate start, @Param("end") LocalDate end);
}
