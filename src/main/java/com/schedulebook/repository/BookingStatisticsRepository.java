package com.schedulebook.repository;

import com.schedulebook.model.BookingStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingStatisticsRepository extends JpaRepository<BookingStatistics, Long> {
    
    Optional<BookingStatistics> findByStatId(String statId);
    
    Optional<BookingStatistics> findByStatDate(LocalDate statDate);
    
    @Query("SELECT bs FROM BookingStatistics bs WHERE bs.statDate BETWEEN :startDate AND :endDate ORDER BY bs.statDate DESC")
    List<BookingStatistics> findByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
    
    boolean existsByStatDate(LocalDate statDate);
}
