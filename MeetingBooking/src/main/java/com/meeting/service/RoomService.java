package com.meeting.service;

import com.meeting.dto.RoomSearchRequest;
import com.meeting.dto.RoomSearchResponse;
import com.meeting.entity.Meeting;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.Schedule;
import com.meeting.exception.MeetingException;
import com.meeting.repository.MeetingRepository;
import com.meeting.repository.MeetingRoomRepository;
import com.meeting.repository.ScheduleRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final MeetingRoomRepository roomRepository;
    private final MeetingRepository meetingRepository;
    private final ScheduleRepository scheduleRepository;
    private final HistoryService historyService;

    private static final String ROOM_STATUS_AVAILABLE = "available";
    private static final String ROOM_STATUS_OCCUPIED = "occupied";
    private static final String ROOM_STATUS_CLOSED = "closed";
    private static final String ROOM_STATUS_MAINTENANCE = "maintenance";

    public List<MeetingRoom> getAllRooms() {
        return roomRepository.findAll();
    }

    public MeetingRoom getRoomById(String roomId) {
        return roomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new MeetingException(404, "会议室不存在: " + roomId));
    }

    public List<MeetingRoom> getRoomsByStatus(String status) {
        return roomRepository.findByRoomStatus(status);
    }

    public List<RoomSearchResponse> searchRooms(RoomSearchRequest request) {
        log.info("搜索会议室: startTime={}, endTime={}, minCapacity={}, location={}",
                request.getStartTime(), request.getEndTime(), request.getMinCapacity(), request.getLocation());

        List<MeetingRoom> rooms;
        if (request.getMinCapacity() != null) {
            rooms = roomRepository.findAvailableRooms(ROOM_STATUS_AVAILABLE, request.getMinCapacity());
        } else {
            rooms = roomRepository.findByRoomStatus(ROOM_STATUS_AVAILABLE);
        }

        if (request.getLocation() != null && !request.getLocation().isEmpty()) {
            rooms = rooms.stream()
                    .filter(r -> r.getRoomLocation() != null && r.getRoomLocation().contains(request.getLocation()))
                    .collect(Collectors.toList());
        }

        if (request.getRequiredFeatures() != null && !request.getRequiredFeatures().isEmpty()) {
            rooms = rooms.stream()
                    .filter(r -> r.getRoomFeatures() != null &&
                            r.getRoomFeatures().containsAll(request.getRequiredFeatures()))
                    .collect(Collectors.toList());
        }

        List<RoomSearchResponse> responses = new ArrayList<>();
        for (MeetingRoom room : rooms) {
            boolean available = checkRoomAvailability(room, request.getStartTime(), request.getEndTime());
            responses.add(RoomSearchResponse.builder()
                    .roomId(room.getRoomId())
                    .roomName(room.getRoomName())
                    .roomCapacity(room.getRoomCapacity())
                    .roomLocation(room.getRoomLocation())
                    .roomStatus(room.getRoomStatus())
                    .roomFeatures(room.getRoomFeatures())
                    .available(available)
                    .build());
        }

        return responses;
    }

    private boolean checkRoomAvailability(MeetingRoom room, java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return ROOM_STATUS_AVAILABLE.equals(room.getRoomStatus());
        }

        if (!ROOM_STATUS_AVAILABLE.equals(room.getRoomStatus())) {
            return false;
        }

        List<String> activeStatuses = Arrays.asList("scheduled", "in_progress");
        List<Meeting> conflictingMeetings = meetingRepository.findConflictingMeetings(
                room.getRoomId(), startTime, endTime, activeStatuses);

        return conflictingMeetings.isEmpty();
    }

    @Transactional
    public MeetingRoom createRoom(MeetingRoom room) {
        if (room.getRoomId() == null || room.getRoomId().isEmpty()) {
            room.setRoomId(IdGenerator.generateRoomId());
        }
        if (room.getRoomStatus() == null || room.getRoomStatus().isEmpty()) {
            room.setRoomStatus(ROOM_STATUS_AVAILABLE);
        }
        if (room.getRoomFeatures() == null) {
            room.setRoomFeatures(new ArrayList<>());
        }

        log.info("创建会议室: roomId={}, roomName={}", room.getRoomId(), room.getRoomName());
        MeetingRoom savedRoom = roomRepository.save(room);

        historyService.recordRoomCreate(savedRoom, "system");

        return savedRoom;
    }

    @Transactional
    public MeetingRoom updateRoom(String roomId, MeetingRoom roomUpdate) {
        MeetingRoom existingRoom = getRoomById(roomId);

        if (roomUpdate.getRoomName() != null) {
            existingRoom.setRoomName(roomUpdate.getRoomName());
        }
        if (roomUpdate.getRoomCapacity() != null) {
            existingRoom.setRoomCapacity(roomUpdate.getRoomCapacity());
        }
        if (roomUpdate.getRoomLocation() != null) {
            existingRoom.setRoomLocation(roomUpdate.getRoomLocation());
        }
        if (roomUpdate.getRoomStatus() != null) {
            existingRoom.setRoomStatus(roomUpdate.getRoomStatus());
        }
        if (roomUpdate.getRoomFeatures() != null) {
            existingRoom.setRoomFeatures(roomUpdate.getRoomFeatures());
        }

        log.info("更新会议室: roomId={}", roomId);
        MeetingRoom updatedRoom = roomRepository.save(existingRoom);

        historyService.recordRoomUpdate(updatedRoom, "system");

        return updatedRoom;
    }

    @Transactional
    public void deleteRoom(String roomId) {
        MeetingRoom room = getRoomById(roomId);

        List<Meeting> activeMeetings = meetingRepository.findByRoomIdAndMeetingStatus(roomId, "scheduled");
        if (!activeMeetings.isEmpty()) {
            throw new MeetingException(400, "会议室存在进行中的会议，无法删除");
        }

        log.info("删除会议室: roomId={}", roomId);

        historyService.recordRoomDelete(room, "system");

        roomRepository.delete(room);
    }

    @Transactional
    public void updateRoomStatus(String roomId, String status) {
        MeetingRoom room = getRoomById(roomId);
        room.setRoomStatus(status);
        roomRepository.save(room);

        log.info("更新会议室状态: roomId={}, status={}", roomId, status);
        historyService.recordRoomStatusChange(room, status, "system");
    }

    @Transactional
    public void occupyRoom(String roomId) {
        updateRoomStatus(roomId, ROOM_STATUS_OCCUPIED);
    }

    @Transactional
    public void releaseRoom(String roomId) {
        updateRoomStatus(roomId, ROOM_STATUS_AVAILABLE);
    }

    public boolean isRoomAvailable(String roomId) {
        MeetingRoom room = getRoomById(roomId);
        return ROOM_STATUS_AVAILABLE.equals(room.getRoomStatus());
    }

    public boolean checkRoomAvailableForTime(String roomId, java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
        MeetingRoom room = getRoomById(roomId);
        return checkRoomAvailability(room, startTime, endTime);
    }

    public List<Schedule> getRoomSchedule(String roomId, LocalDate date) {
        getRoomById(roomId);
        return scheduleRepository.findByRoomIdAndScheduleDate(roomId, date);
    }

    public List<Schedule> getRoomScheduleRange(String roomId, LocalDate startDate, LocalDate endDate) {
        getRoomById(roomId);
        return scheduleRepository.findByRoomIdAndDateRange(roomId, startDate, endDate);
    }

    public boolean isRoomAvailableForSchedule(String roomId, LocalDate date, LocalTime startTime, LocalTime endTime) {
        getRoomById(roomId);

        List<String> activeStatuses = Arrays.asList("scheduled", "in_progress");
        List<Schedule> conflicting = scheduleRepository.findConflictingSchedules(
                roomId, date, startTime, endTime, activeStatuses);

        return conflicting.isEmpty();
    }
}
