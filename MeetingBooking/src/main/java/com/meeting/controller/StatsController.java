package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.dto.StatsResponse;
import com.meeting.entity.MeetingStats;
import com.meeting.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MeetingStats>>> getAllStats() {
        List<MeetingStats> stats = statsService.getAllStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<StatsResponse>> getCurrentMonthStats() {
        StatsResponse stats = statsService.getCurrentMonthStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/{month}")
    public ResponseEntity<ApiResponse<StatsResponse>> getStatsByMonth(@PathVariable String month) {
        StatsResponse stats = statsService.getStatsResponse(month);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary() {
        long totalMeetings = statsService.countTotalMeetings();
        long scheduledMeetings = statsService.countMeetingsByStatus("scheduled");
        long inProgressMeetings = statsService.countMeetingsByStatus("in_progress");
        long completedMeetings = statsService.countMeetingsByStatus("completed");
        long cancelledMeetings = statsService.countMeetingsByStatus("cancelled");

        Map<String, Object> result = new HashMap<>();
        result.put("total_meetings", totalMeetings);
        result.put("scheduled", scheduledMeetings);
        result.put("in_progress", inProgressMeetings);
        result.put("completed", completedMeetings);
        result.put("cancelled", cancelledMeetings);

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
