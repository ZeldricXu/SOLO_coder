package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.dto.EventSearchResponse;
import com.eventticket.entity.Event;
import com.eventticket.service.EventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    @Autowired
    private EventService eventService;

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> searchEvents(
            @RequestParam(required = false) String eventType) {
        List<EventSearchResponse> events = eventService.searchEventsWithAvailability(eventType);
        
        Map<String, Object> data = new HashMap<>();
        data.put("events", events);
        
        return ApiResponse.success(data);
    }

    @PostMapping
    public ApiResponse<Event> createEvent(@RequestBody Event event) {
        Event createdEvent = eventService.createEvent(event);
        return ApiResponse.success(createdEvent);
    }

    @GetMapping("/{eventId}")
    public ApiResponse<Event> getEventById(@PathVariable String eventId) {
        Optional<Event> event = eventService.getEventById(eventId);
        if (event.isPresent()) {
            return ApiResponse.success(event.get());
        }
        return ApiResponse.error(404, "活动不存在");
    }

    @GetMapping
    public ApiResponse<List<Event>> getAllEvents() {
        List<Event> events = eventService.getAllEvents();
        return ApiResponse.success(events);
    }

    @PutMapping("/{eventId}")
    public ApiResponse<Event> updateEvent(@PathVariable String eventId, @RequestBody Event updatedEvent) {
        Event event = eventService.updateEvent(eventId, updatedEvent);
        if (event != null) {
            return ApiResponse.success(event);
        }
        return ApiResponse.error(404, "活动不存在");
    }

    @DeleteMapping("/{eventId}")
    public ApiResponse<Boolean> deleteEvent(@PathVariable String eventId) {
        boolean deleted = eventService.deleteEvent(eventId);
        if (deleted) {
            return ApiResponse.success(true);
        }
        return ApiResponse.error(404, "活动不存在");
    }
}
