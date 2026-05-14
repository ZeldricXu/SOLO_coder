package com.meeting.repository;

import com.meeting.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, String> {

    Optional<Reminder> findByReminderId(String reminderId);

    List<Reminder> findByMeetingId(String meetingId);

    List<Reminder> findByMeetingIdAndReminderStatus(String meetingId, String reminderStatus);

    List<Reminder> findByReminderStatus(String reminderStatus);

    @Query("SELECT r FROM Reminder r WHERE r.reminderStatus = 'pending' AND r.reminderTime <= :currentTime ORDER BY r.reminderTime ASC")
    List<Reminder> findPendingRemindersToSend(@Param("currentTime") LocalDateTime currentTime);

    @Query("SELECT COUNT(r) FROM Reminder r WHERE r.reminderStatus = 'sent' AND r.sentTime >= :start AND r.sentTime < :end")
    long countSentRemindersInRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT r FROM Reminder r WHERE r.meetingId = :meetingId AND r.reminderType = :type")
    Optional<Reminder> findByMeetingIdAndType(@Param("meetingId") String meetingId, @Param("type") String type);

    boolean existsByMeetingIdAndReminderType(String meetingId, String reminderType);
}
