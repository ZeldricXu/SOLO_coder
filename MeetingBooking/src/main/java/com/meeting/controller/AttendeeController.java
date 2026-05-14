package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.dto.AttendeeConfirmRequest;
import com.meeting.dto.AttendeeConfirmResponse;
import com.meeting.entity.Attendee;
import com.meeting.service.AttendeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/attendees")
@RequiredArgsConstructor
public class AttendeeController {

    private final AttendeeService attendeeService;

    @GetMapping("/{attendeeId}")
    public ResponseEntity<ApiResponse<Attendee>> getAttendeeById(@PathVariable String attendeeId) {
        Attendee attendee = attendeeService.getAttendeeById(attendeeId);
        return ResponseEntity.ok(ApiResponse.success(attendee));
    }

    @GetMapping("/meeting/{meetingId}")
    public ResponseEntity<ApiResponse<List<Attendee>>> getAttendeesByMeeting(@PathVariable String meetingId) {
        List<Attendee> attendees = attendeeService.getAttendeesByMeetingId(meetingId);
        return ResponseEntity.ok(ApiResponse.success(attendees));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<Attendee>>> getAttendeesByUser(@PathVariable String userId) {
        List<Attendee> attendees = attendeeService.getAttendeesByUserId(userId);
        return ResponseEntity.ok(ApiResponse.success(attendees));
    }

    @GetMapping("/meeting/{meetingId}/user/{userId}")
    public ResponseEntity<ApiResponse<Attendee>> getAttendeeByMeetingAndUser(
            @PathVariable String meetingId,
            @PathVariable String userId) {
        Attendee attendee = attendeeService.getAttendeeByMeetingAndUser(meetingId, userId);
        return ResponseEntity.ok(ApiResponse.success(attendee));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmAttendance(@Valid @RequestBody AttendeeConfirmRequest request) {
        AttendeeConfirmResponse response = attendeeService.confirmAttendance(request);

        Map<String, Object> result = new HashMap<>();
        result.put("attendee_id", response.getAttendeeId());
        result.put("status", response.getAttendeeStatus());
        result.put("meeting_id", response.getMeetingId());
        result.put("user_id", response.getUserId());
        result.put("user_name", response.getUserName());
        result.put("attendee_time", response.getAttendeeTime());

        return ResponseEntity.ok(ApiResponse.success("参会确认成功", result));
    }

    @GetMapping("/meeting/{meetingId}/count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAttendeeCounts(@PathVariable String meetingId) {
        long total = attendeeService.countAttendees(meetingId);
        long confirmed = attendeeService.countConfirmedAttendees(meetingId);

        Map<String, Object> result = new HashMap<>();
        result.put("meeting_id", meetingId);
        result.put("total_count", total);
        result.put("confirmed_count", confirmed);
        result.put("pending_count", total - confirmed);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/meeting/{meetingId}/confirmed")
    public ResponseEntity<ApiResponse<List<Attendee>>> getConfirmedAttendees(@PathVariable String meetingId) {
        List<Attendee> attendees = attendeeService.getConfirmedAttendees(meetingId);
        return ResponseEntity.ok(ApiResponse.success(attendees));
    }

    @GetMapping("/meeting/{meetingId}/pending")
    public ResponseEntity<ApiResponse<List<Attendee>>> getPendingAttendees(@PathVariable String meetingId) {
        List<Attendee> attendees = attendeeService.getPendingAttendees(meetingId);
        return ResponseEntity.ok(ApiResponse.success(attendees));
    }

    @GetMapping("/user/{userId}/meetings")
    public ResponseEntity<ApiResponse<List<Attendee>>> getUserMeetings(@PathVariable String userId) {
        List<Attendee> attendees = attendeeService.getUserMeetings(userId);
        return ResponseEntity.ok(ApiResponse.success(attendees));
    }

    @DeleteMapping("/{attendeeId}")
    public ResponseEntity<ApiResponse<Void>> removeAttendee(@PathVariable String attendeeId) {
        attendeeService.removeAttendee(attendeeId);
        return ResponseEntity.ok(ApiResponse.success("参会人员移除成功", null));
    }

    @DeleteMapping("/meeting/{meetingId}/user/{userId}")
    public ResponseEntity<ApiResponse<Void>> removeAttendeeFromMeeting(
            @PathVariable String meetingId,
            @PathVariable String userId) {
        attendeeService.removeAttendeeFromMeeting(meetingId, userId);
        return ResponseEntity.ok(ApiResponse.success("参会人员移除成功", null));
    }
}
