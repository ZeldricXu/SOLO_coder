package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.dto.RoomSearchRequest;
import com.meeting.dto.RoomSearchResponse;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.Schedule;
import com.meeting.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MeetingRoom>>> getAllRooms() {
        List<MeetingRoom> rooms = roomService.getAllRooms();
        return ResponseEntity.ok(ApiResponse.success(rooms));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ApiResponse<MeetingRoom>> getRoomById(@PathVariable String roomId) {
        MeetingRoom room = roomService.getRoomById(roomId);
        return ResponseEntity.ok(ApiResponse.success(room));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchRooms(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime endTime,
            @RequestParam(required = false) Integer minCapacity,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String roomStatus) {

        RoomSearchRequest request = RoomSearchRequest.builder()
                .startTime(startTime)
                .endTime(endTime)
                .minCapacity(minCapacity)
                .location(location)
                .roomStatus(roomStatus)
                .build();

        List<RoomSearchResponse> rooms = roomService.searchRooms(request);

        Map<String, Object> result = new HashMap<>();
        result.put("rooms", rooms);
        result.put("total", rooms.size());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MeetingRoom>> createRoom(@Valid @RequestBody MeetingRoom room) {
        MeetingRoom created = roomService.createRoom(room);
        return ResponseEntity.ok(ApiResponse.success("会议室创建成功", created));
    }

    @PutMapping("/{roomId}")
    public ResponseEntity<ApiResponse<MeetingRoom>> updateRoom(
            @PathVariable String roomId,
            @RequestBody MeetingRoom roomUpdate) {
        MeetingRoom updated = roomService.updateRoom(roomId, roomUpdate);
        return ResponseEntity.ok(ApiResponse.success("会议室更新成功", updated));
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(@PathVariable String roomId) {
        roomService.deleteRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success("会议室删除成功", null));
    }

    @PutMapping("/{roomId}/status")
    public ResponseEntity<ApiResponse<Void>> updateRoomStatus(
            @PathVariable String roomId,
            @RequestParam String status) {
        roomService.updateRoomStatus(roomId, status);
        return ResponseEntity.ok(ApiResponse.success("会议室状态更新成功", null));
    }

    @GetMapping("/{roomId}/check-availability")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkRoomAvailability(
            @PathVariable String roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime endTime) {

        boolean available = roomService.checkRoomAvailableForTime(roomId, startTime, endTime);

        Map<String, Object> result = new HashMap<>();
        result.put("roomId", roomId);
        result.put("available", available);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{roomId}/schedule")
    public ResponseEntity<ApiResponse<List<Schedule>>> getRoomSchedule(
            @PathVariable String roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<Schedule> schedules = roomService.getRoomSchedule(roomId, date);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }

    @GetMapping("/{roomId}/schedule-range")
    public ResponseEntity<ApiResponse<List<Schedule>>> getRoomScheduleRange(
            @PathVariable String roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<Schedule> schedules = roomService.getRoomScheduleRange(roomId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }
}
