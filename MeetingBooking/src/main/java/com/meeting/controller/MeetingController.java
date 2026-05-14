package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.dto.MeetingCreateRequest;
import com.meeting.dto.MeetingCreateResponse;
import com.meeting.dto.MeetingDetailResponse;
import com.meeting.dto.MeetingListRequest;
import com.meeting.dto.PageResponse;
import com.meeting.entity.Meeting;
import com.meeting.service.MeetingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Meeting>>> getAllMeetings() {
        List<Meeting> meetings = meetingService.getAllMeetings();
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }

    @GetMapping("/{meetingId}")
    public ResponseEntity<ApiResponse<Meeting>> getMeetingById(@PathVariable String meetingId) {
        Meeting meeting = meetingService.getMeetingById(meetingId);
        return ResponseEntity.ok(ApiResponse.success(meeting));
    }

    @GetMapping("/{meetingId}/detail")
    public ResponseEntity<ApiResponse<MeetingDetailResponse>> getMeetingDetail(@PathVariable String meetingId) {
        MeetingDetailResponse detail = meetingService.getMeetingDetail(meetingId);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    @PostMapping("/list")
    public ResponseEntity<ApiResponse<PageResponse<Meeting>>> getMeetings(@RequestBody MeetingListRequest request) {
        PageResponse<Meeting> meetings = meetingService.getMeetings(request);
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createMeeting(@Valid @RequestBody MeetingCreateRequest request) {
        MeetingCreateResponse response = meetingService.createMeeting(request);

        Map<String, Object> result = new HashMap<>();
        result.put("meeting_id", response.getMeetingId());
        result.put("status", response.getMeetingStatus());
        result.put("room_id", response.getRoomId());
        result.put("room_name", response.getRoomName());
        result.put("meeting_topic", response.getMeetingTopic());
        result.put("meeting_type", response.getMeetingType());
        result.put("meeting_start", response.getMeetingStart());
        result.put("meeting_end", response.getMeetingEnd());
        result.put("organizer_id", response.getOrganizerId());
        result.put("schedule_id", response.getScheduleId());

        return ResponseEntity.ok(ApiResponse.success("会议预约成功", result));
    }

    @PutMapping("/{meetingId}")
    public ResponseEntity<ApiResponse<Meeting>> updateMeeting(
            @PathVariable String meetingId,
            @RequestBody Meeting meetingUpdate) {
        Meeting updated = meetingService.updateMeeting(meetingId, meetingUpdate);
        return ResponseEntity.ok(ApiResponse.success("会议更新成功", updated));
    }

    @PostMapping("/{meetingId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelMeeting(
            @PathVariable String meetingId,
            @RequestHeader(value = "X-User-Id", required = false) String operatorId) {
        if (operatorId == null) {
            operatorId = "system";
        }
        meetingService.cancelMeeting(meetingId, operatorId);
        return ResponseEntity.ok(ApiResponse.success("会议取消成功", null));
    }

    @PostMapping("/{meetingId}/start")
    public ResponseEntity<ApiResponse<Void>> startMeeting(@PathVariable String meetingId) {
        meetingService.startMeeting(meetingId);
        return ResponseEntity.ok(ApiResponse.success("会议开始成功", null));
    }

    @PostMapping("/{meetingId}/complete")
    public ResponseEntity<ApiResponse<Void>> completeMeeting(@PathVariable String meetingId) {
        meetingService.completeMeeting(meetingId);
        return ResponseEntity.ok(ApiResponse.success("会议完成成功", null));
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<List<Meeting>>> getMeetingsByRoom(@PathVariable String roomId) {
        List<Meeting> meetings = meetingService.getMeetingsByRoomId(roomId);
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }

    @GetMapping("/organizer/{organizerId}")
    public ResponseEntity<ApiResponse<List<Meeting>>> getMeetingsByOrganizer(@PathVariable String organizerId) {
        List<Meeting> meetings = meetingService.getMeetingsByOrganizer(organizerId);
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<Meeting>>> getActiveMeetings() {
        List<Meeting> meetings = meetingService.getActiveMeetings();
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }
}
