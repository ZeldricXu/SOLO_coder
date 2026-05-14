package com.schedulebook.repository;

import com.schedulebook.model.ScheduleSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleSlotRepository extends JpaRepository<ScheduleSlot, Long> {
    
    List<ScheduleSlot> findByScheduleId(Long scheduleId);
    
    @Query("SELECT ss FROM ScheduleSlot ss WHERE ss.schedule.id = :scheduleId AND ss.slotTime = :slotTime")
    Optional<ScheduleSlot> findByScheduleIdAndSlotTime(
            @Param("scheduleId") Long scheduleId,
            @Param("slotTime") LocalTime slotTime
    );
    
    @Query("SELECT ss FROM ScheduleSlot ss JOIN ss.schedule s WHERE s.resourceId = :resourceId AND s.scheduleDate = :scheduleDate AND ss.slotTime = :slotTime")
    Optional<ScheduleSlot> findByResourceAndDateAndTime(
            @Param("resourceId") String resourceId,
            @Param("scheduleDate") java.time.LocalDate scheduleDate,
            @Param("slotTime") LocalTime slotTime
    );
    
    @Query("SELECT ss FROM ScheduleSlot ss JOIN ss.schedule s WHERE s.resourceId = :resourceId AND s.scheduleDate = :scheduleDate")
    List<ScheduleSlot> findByResourceAndDate(
            @Param("resourceId") String resourceId,
            @Param("scheduleDate") java.time.LocalDate scheduleDate
    );
}
