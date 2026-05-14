package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.entity.MeetingHistory;
import com.meeting.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MeetingHistory>>> getAllHistory() {
        List<MeetingHistory> history = historyService.getAllHistory();
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/meeting/{meetingId}")
    public ResponseEntity<ApiResponse<List<MeetingHistory>>> getHistoryByMeeting(@PathVariable String meetingId) {
        List<MeetingHistory> history = historyService.getHistoryByMeetingId(meetingId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<List<MeetingHistory>>> getHistoryByRoom(@PathVariable String roomId) {
        List<MeetingHistory> history = historyService.getHistoryByRoomId(roomId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/operator/{operatorId}")
    public ResponseEntity<ApiResponse<List<MeetingHistory>>> getHistoryByOperator(@PathVariable String operatorId) {
        List<MeetingHistory> history = historyService.getHistoryByOperator(operatorId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/action/{actionType}")
    public ResponseEntity<ApiResponse<List<MeetingHistory>>> getHistoryByActionType(@PathVariable String actionType) {
        List<MeetingHistory> history = historyService.getHistoryByActionType(actionType);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/range")
    public ResponseEntity<ApiResponse<List<MeetingHistory>>> getHistoryByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        List<MeetingHistory> history = historyService.getHistoryByDateRange(start, end);
        return ResponseEntity.ok(ApiResponse.success(history));
    }
}
