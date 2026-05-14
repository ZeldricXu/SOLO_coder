package com.meeting.repository;

import com.meeting.entity.Attendee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendeeRepository extends JpaRepository<Attendee, String> {

    Optional<Attendee> findByAttendeeId(String attendeeId);

    List<Attendee> findByMeetingId(String meetingId);

    List<Attendee> findByUserId(String userId);

    Optional<Attendee> findByMeetingIdAndUserId(String meetingId, String userId);

    List<Attendee> findByMeetingIdAndAttendeeStatus(String meetingId, String attendeeStatus);

    @Query("SELECT COUNT(a) FROM Attendee a WHERE a.meetingId = :meetingId")
    long countByMeetingId(@Param("meetingId") String meetingId);

    @Query("SELECT COUNT(a) FROM Attendee a WHERE a.meetingId = :meetingId AND a.attendeeStatus = :status")
    long countByMeetingIdAndStatus(@Param("meetingId") String meetingId, @Param("status") String status);

    @Query("SELECT a FROM Attendee a WHERE a.userId = :userId AND a.attendeeStatus IN :statuses ORDER BY a.createdAt DESC")
    List<Attendee> findByUserIdAndStatuses(@Param("userId") String userId, @Param("statuses") List<String> statuses);

    @Query("SELECT COUNT(a) FROM Attendee a WHERE a.attendeeTime IS NOT NULL AND a.attendeeTime >= :start AND a.attendeeTime < :end")
    long countConfirmedInRange(@Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);

    boolean existsByMeetingIdAndUserId(String meetingId, String userId);
}
