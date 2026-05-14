package com.eventticket.service;

import com.eventticket.entity.EventSchedule;
import com.eventticket.repository.EventScheduleRepository;
import com.eventticket.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class EventScheduleService {

    @Autowired
    private EventScheduleRepository eventScheduleRepository;

    @Transactional
    public EventSchedule createSchedule(EventSchedule schedule) {
        schedule.setScheduleId(IdGenerator.generateScheduleId());
        if (schedule.getCreatedAt() == null) {
            schedule.setCreatedAt(LocalDateTime.now());
        }
        return eventScheduleRepository.save(schedule);
    }

    @Transactional(readOnly = true)
    public Optional<EventSchedule> getScheduleById(String scheduleId) {
        return eventScheduleRepository.findById(scheduleId);
    }

    @Transactional(readOnly = true)
    public List<EventSchedule> getSchedulesByEventId(String eventId) {
        return eventScheduleRepository.findByEventIdOrderByScheduleStartTime(eventId);
    }

    @Transactional
    public EventSchedule updateSchedule(String scheduleId, EventSchedule updatedSchedule) {
        return eventScheduleRepository.findById(scheduleId).map(schedule -> {
            schedule.setScheduleTitle(updatedSchedule.getScheduleTitle());
            schedule.setScheduleStartTime(updatedSchedule.getScheduleStartTime());
            schedule.setScheduleEndTime(updatedSchedule.getScheduleEndTime());
            if (updatedSchedule.getScheduleVenue() != null) {
                schedule.setScheduleVenue(updatedSchedule.getScheduleVenue());
            }
            if (updatedSchedule.getScheduleDescription() != null) {
                schedule.setScheduleDescription(updatedSchedule.getScheduleDescription());
            }
            return eventScheduleRepository.save(schedule);
        }).orElse(null);
    }

    @Transactional
    public boolean deleteSchedule(String scheduleId) {
        return eventScheduleRepository.findById(scheduleId).map(schedule -> {
            eventScheduleRepository.delete(schedule);
            return true;
        }).orElse(false);
    }
}
