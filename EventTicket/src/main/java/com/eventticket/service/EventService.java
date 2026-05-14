package com.eventticket.service;

import com.eventticket.dto.EventSearchResponse;
import com.eventticket.entity.Event;
import com.eventticket.repository.EventRepository;
import com.eventticket.repository.SeatRepository;
import com.eventticket.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EventService {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Transactional
    public Event createEvent(Event event) {
        event.setEventId(IdGenerator.generateEventId());
        if (event.getCreatedAt() == null) {
            event.setCreatedAt(LocalDateTime.now());
        }
        return eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public Optional<Event> getEventById(String eventId) {
        return eventRepository.findById(eventId);
    }

    @Transactional(readOnly = true)
    public List<Event> searchEvents(String eventType) {
        return eventRepository.searchEvents(eventType);
    }

    @Transactional(readOnly = true)
    public List<EventSearchResponse> searchEventsWithAvailability(String eventType) {
        List<Event> events = eventRepository.searchEvents(eventType);
        return events.stream().map(this::convertToSearchResponse).collect(Collectors.toList());
    }

    private EventSearchResponse convertToSearchResponse(Event event) {
        EventSearchResponse response = new EventSearchResponse();
        response.setEventId(event.getEventId());
        response.setEventName(event.getEventName());
        response.setEventType(event.getEventType());
        response.setEventDate(event.getEventDate());
        response.setEventVenue(event.getEventVenue());
        response.setEventCapacity(event.getEventCapacity());
        response.setEventStatus(event.getEventStatus());
        
        long availableSeats = seatRepository.countAvailableSeats(event.getEventId());
        response.setAvailableSeats((int) availableSeats);
        
        return response;
    }

    @Transactional(readOnly = true)
    public List<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    @Transactional
    public Event updateEvent(String eventId, Event updatedEvent) {
        return eventRepository.findById(eventId).map(event -> {
            event.setEventName(updatedEvent.getEventName());
            event.setEventType(updatedEvent.getEventType());
            event.setEventDate(updatedEvent.getEventDate());
            event.setEventVenue(updatedEvent.getEventVenue());
            event.setEventCapacity(updatedEvent.getEventCapacity());
            event.setEventStatus(updatedEvent.getEventStatus());
            return eventRepository.save(event);
        }).orElse(null);
    }

    @Transactional
    public boolean deleteEvent(String eventId) {
        return eventRepository.findById(eventId).map(event -> {
            eventRepository.delete(event);
            return true;
        }).orElse(false);
    }

    public boolean validateEventStatus(Event event) {
        String status = event.getEventStatus();
        if ("cancelled".equals(status)) {
            return false;
        }
        if ("ended".equals(status)) {
            return false;
        }
        return "scheduled".equals(status) || "ongoing".equals(status);
    }
}
