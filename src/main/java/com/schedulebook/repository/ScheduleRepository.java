package com.schedulebook.repository;

import com.schedulebook.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    
    Optional<Schedule> findByScheduleId(String scheduleId);
    
    Optional<Schedule> findByResourceIdAndScheduleDate(String resourceId, LocalDate scheduleDate);
    
    List<Schedule> findByResourceId(String resourceId);
    
    List<Schedule> findByScheduleDate(LocalDate scheduleDate);
    
    @Query("SELECT s FROM Schedule s WHERE s.resourceId = :resourceId AND s.scheduleDate BETWEEN :startDate AND :endDate")
    List<Schedule> findByResourceIdAndDateRange(
            @Param("resourceId") String resourceId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
    
    boolean existsByResourceIdAndScheduleDate(String resourceId, LocalDate scheduleDate);
}
