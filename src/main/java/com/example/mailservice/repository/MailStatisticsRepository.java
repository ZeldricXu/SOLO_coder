package com.example.mailservice.repository;

import com.example.mailservice.model.MailStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MailStatisticsRepository extends JpaRepository<MailStatistics, Long> {
    Optional<MailStatistics> findByStatId(String statId);

    Optional<MailStatistics> findByStatDate(LocalDate statDate);

    List<MailStatistics> findByStatDateBetween(LocalDate start, LocalDate end);

    @Query("SELECT SUM(m.sentCount) FROM MailStatistics m WHERE m.statDate BETWEEN :start AND :end")
    Long sumSentCountBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT SUM(m.receivedCount) FROM MailStatistics m WHERE m.statDate BETWEEN :start AND :end")
    Long sumReceivedCountBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT SUM(m.failedCount) FROM MailStatistics m WHERE m.statDate BETWEEN :start AND :end")
    Long sumFailedCountBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
