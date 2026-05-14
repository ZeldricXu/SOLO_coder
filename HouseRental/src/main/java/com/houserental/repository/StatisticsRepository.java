package com.houserental.repository;

import com.houserental.entity.Statistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StatisticsRepository extends JpaRepository<Statistics, String> {

    Optional<Statistics> findByStatId(String statId);

    Optional<Statistics> findByStatMonth(String statMonth);

    @Query("SELECT s FROM Statistics s WHERE s.statMonth BETWEEN :startMonth AND :endMonth ORDER BY s.statMonth ASC")
    List<Statistics> findByMonthRange(@Param("startMonth") String startMonth, @Param("endMonth") String endMonth);

    @Query("SELECT s FROM Statistics s ORDER BY s.statMonth DESC LIMIT :limit")
    List<Statistics> findRecentStatistics(@Param("limit") int limit);

    boolean existsByStatMonth(String statMonth);
}
