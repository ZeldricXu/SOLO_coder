package com.eventticket.repository;

import com.eventticket.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {
    List<Event> findByEventTypeAndEventDateBetween(String eventType, LocalDateTime startDate, LocalDateTime endDate);
    List<Event> findByEventStatus(String eventStatus);
    
    @Query("SELECT e FROM Event e WHERE e.eventType = :eventType OR :eventType IS NULL")
    List<Event> searchEvents(@Param("eventType") String eventType);
    
    @Query("SELECT COUNT(e) FROM Event e WHERE e.eventDate BETWEEN :start AND :end")
    long countEventsByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
