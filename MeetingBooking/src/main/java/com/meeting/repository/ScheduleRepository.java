package com.meeting.repository;

import com.meeting.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, String> {

    Optional<Schedule> findByScheduleId(String scheduleId);

    List<Schedule> findByMeetingId(String meetingId);

    List<Schedule> findByRoomId(String roomId);

    List<Schedule> findByScheduleDate(LocalDate scheduleDate);

    List<Schedule> findByRoomIdAndScheduleDate(String roomId, LocalDate scheduleDate);

    @Query("SELECT s FROM Schedule s WHERE s.roomId = :roomId AND s.scheduleDate = :date " +
           "AND s.scheduleStatus IN :statuses AND ((s.scheduleStart <= :endTime AND s.scheduleEnd >= :startTime))")
    List<Schedule> findConflictingSchedules(
            @Param("roomId") String roomId,
            @Param("date") LocalDate date,
            @Param("startTime") java.time.LocalTime startTime,
            @Param("endTime") java.time.LocalTime endTime,
            @Param("statuses") List<String> statuses);

    @Query("SELECT s FROM Schedule s WHERE s.roomId = :roomId AND s.scheduleDate BETWEEN :startDate AND :endDate ORDER BY s.scheduleDate, s.scheduleStart")
    List<Schedule> findByRoomIdAndDateRange(
            @Param("roomId") String roomId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    Optional<Schedule> findByMeetingIdAndRoomId(String meetingId, String roomId);

    boolean existsByMeetingId(String meetingId);
}
