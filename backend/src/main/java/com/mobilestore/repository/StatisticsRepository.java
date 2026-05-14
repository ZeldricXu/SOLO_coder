package com.mobilestore.repository;

import com.mobilestore.entity.Statistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StatisticsRepository extends JpaRepository<Statistics, String> {

    Optional<Statistics> findByStatId(String statId);

    Optional<Statistics> findByAppIdAndStatDate(String appId, LocalDate statDate);

    List<Statistics> findByAppIdOrderByStatDateDesc(String appId);

    List<Statistics> findByAppIdAndStatDateBetweenOrderByStatDateAsc(
            String appId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT SUM(s.downloadCount) FROM Statistics s WHERE s.appId = ?1")
    Long sumDownloadCountByAppId(String appId);

    @Query("SELECT SUM(s.activeUsers) FROM Statistics s WHERE s.appId = ?1")
    Long sumActiveUsersByAppId(String appId);

    @Query("SELECT AVG(s.avgRating) FROM Statistics s WHERE s.appId = ?1")
    Double avgRatingByAppId(String appId);

    @Query("SELECT SUM(s.feedbackCount) FROM Statistics s WHERE s.appId = ?1")
    Long sumFeedbackCountByAppId(String appId);

    @Query("SELECT DISTINCT s.appId FROM Statistics s")
    List<String> findAllAppIds();
}
