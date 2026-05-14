package com.eventticket.repository;

import com.eventticket.entity.EventSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventScheduleRepository extends JpaRepository<EventSchedule, String> {
    List<EventSchedule> findByEventId(String eventId);
    List<EventSchedule> findByEventIdOrderByScheduleStartTime(String eventId);
}
