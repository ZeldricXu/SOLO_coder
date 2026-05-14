package com.meeting.repository;

import com.meeting.entity.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, String> {

    Optional<Meeting> findByMeetingId(String meetingId);

    List<Meeting> findByRoomId(String roomId);

    List<Meeting> findByOrganizerId(String organizerId);

    List<Meeting> findByMeetingStatus(String meetingStatus);

    List<Meeting> findByRoomIdAndMeetingStatus(String roomId, String meetingStatus);

    @Query("SELECT m FROM Meeting m WHERE m.roomId = :roomId AND m.meetingStatus IN :statuses " +
           "AND ((m.meetingStart <= :endTime AND m.meetingEnd >= :startTime))")
    List<Meeting> findConflictingMeetings(
            @Param("roomId") String roomId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("statuses") List<String> statuses);

    @Query("SELECT m FROM Meeting m WHERE m.meetingStart >= :start AND m.meetingStart < :end")
    List<Meeting> findByMeetingStartBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT m FROM Meeting m WHERE m.organizerId = :organizerId ORDER BY m.meetingStart DESC")
    List<Meeting> findByOrganizerIdOrderByStartTimeDesc(@Param("organizerId") String organizerId);

    @Query("SELECT COUNT(m) FROM Meeting m WHERE m.meetingStart >= :start AND m.meetingStart < :end")
    long countByMeetingStartBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(m) FROM Meeting m WHERE m.meetingStatus = :status")
    long countByMeetingStatus(@Param("status") String status);

    @Query("SELECT m.roomId, COUNT(m) FROM Meeting m WHERE m.meetingStart >= :start AND m.meetingStart < :end GROUP BY m.roomId")
    List<Object[]> countMeetingsByRoomInRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT m.meetingType, COUNT(m) FROM Meeting m WHERE m.meetingStart >= :start AND m.meetingStart < :end GROUP BY m.meetingType")
    List<Object[]> countMeetingsByTypeInRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT m FROM Meeting m WHERE m.meetingStatus IN ('scheduled', 'in_progress') ORDER BY m.meetingStart ASC")
    List<Meeting> findActiveMeetings();

    boolean existsByMeetingId(String meetingId);
}
