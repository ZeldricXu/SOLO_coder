package com.meeting.repository;

import com.meeting.entity.MeetingHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MeetingHistoryRepository extends JpaRepository<MeetingHistory, String> {

    List<MeetingHistory> findByMeetingId(String meetingId);

    List<MeetingHistory> findByOperatorId(String operatorId);

    List<MeetingHistory> findByActionType(String actionType);

    List<MeetingHistory> findByRoomId(String roomId);

    @Query("SELECT h FROM MeetingHistory h WHERE h.createdAt >= :start AND h.createdAt < :end ORDER BY h.createdAt DESC")
    List<MeetingHistory> findByCreatedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT h FROM MeetingHistory h WHERE h.meetingId = :meetingId ORDER BY h.createdAt DESC")
    List<MeetingHistory> findByMeetingIdOrderByCreatedAtDesc(@Param("meetingId") String meetingId);

    @Query("SELECT h FROM MeetingHistory h WHERE h.roomId = :roomId ORDER BY h.createdAt DESC")
    List<MeetingHistory> findByRoomIdOrderByCreatedAtDesc(@Param("roomId") String roomId);

    @Query("SELECT h FROM MeetingHistory h ORDER BY h.createdAt DESC")
    List<MeetingHistory> findAllOrderByCreatedAtDesc();
}
