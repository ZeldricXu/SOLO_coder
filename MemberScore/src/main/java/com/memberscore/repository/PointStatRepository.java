package com.memberscore.repository;

import com.memberscore.entity.PointStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PointStatRepository extends JpaRepository<PointStat, Long> {
    
    Optional<PointStat> findByStatId(String statId);
    
    Optional<PointStat> findByStatDate(LocalDate statDate);
    
    List<PointStat> findByStatDateBetweenOrderByStatDateDesc(LocalDate startDate, LocalDate endDate);
    
    @Query("SELECT COALESCE(SUM(s.earnPoints), 0) FROM PointStat s WHERE s.statDate BETWEEN :start AND :end")
    Integer sumEarnPointsBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
    
    @Query("SELECT COALESCE(SUM(s.consumePoints), 0) FROM PointStat s WHERE s.statDate BETWEEN :start AND :end")
    Integer sumConsumePointsBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
    
    @Query("SELECT COALESCE(SUM(s.earnCount), 0) FROM PointStat s WHERE s.statDate BETWEEN :start AND :end")
    Long sumEarnCountBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
    
    @Query("SELECT COALESCE(SUM(s.consumeCount), 0) FROM PointStat s WHERE s.statDate BETWEEN :start AND :end")
    Long sumConsumeCountBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
