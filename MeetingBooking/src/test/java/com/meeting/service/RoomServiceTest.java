package com.meeting.service;

import com.meeting.builder.TestDataBuilder;
import com.meeting.dto.RoomSearchRequest;
import com.meeting.dto.RoomSearchResponse;
import com.meeting.entity.MeetingRoom;
import com.meeting.repository.MeetingRoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private MeetingRoomRepository roomRepository;

    @Mock
    private com.meeting.repository.MeetingRepository meetingRepository;

    @Mock
    private com.meeting.repository.ScheduleRepository scheduleRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private RoomService roomService;

    private MeetingRoom testRoom;

    @BeforeEach
    void setUp() {
        testRoom = TestDataBuilder.buildMeetingRoom();
    }

    @Test
    void getRoomById_ShouldReturnRoom_WhenRoomExists() {
        when(roomRepository.findByRoomId(anyString())).thenReturn(Optional.of(testRoom));

        MeetingRoom result = roomService.getRoomById("room_test_001");

        assertNotNull(result);
        assertEquals("room_test_001", result.getRoomId());
        assertEquals("测试会议室", result.getRoomName());
    }

    @Test
    void getRoomById_ShouldThrowException_WhenRoomNotExists() {
        when(roomRepository.findByRoomId(anyString())).thenReturn(Optional.empty());

        assertThrows(com.meeting.exception.MeetingException.class, () -> {
            roomService.getRoomById("room_not_exist");
        });
    }

    @Test
    void getAllRooms_ShouldReturnAllRooms() {
        List<MeetingRoom> rooms = Arrays.asList(testRoom);
        when(roomRepository.findAll()).thenReturn(rooms);

        List<MeetingRoom> result = roomService.getAllRooms();

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void getRoomsByStatus_ShouldReturnRoomsWithStatus() {
        List<MeetingRoom> rooms = Arrays.asList(testRoom);
        when(roomRepository.findByRoomStatus(anyString())).thenReturn(rooms);

        List<MeetingRoom> result = roomService.getRoomsByStatus("available");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("available", result.get(0).getRoomStatus());
    }

    @Test
    void searchRooms_ShouldReturnAvailableRooms() {
        List<MeetingRoom> rooms = Arrays.asList(testRoom);
        when(roomRepository.findAvailableRooms(anyString(), any())).thenReturn(rooms);

        RoomSearchRequest request = RoomSearchRequest.builder()
                .minCapacity(5)
                .build();

        List<RoomSearchResponse> result = roomService.searchRooms(request);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getAvailable());
    }

    @Test
    void isRoomAvailable_ShouldReturnTrue_WhenStatusAvailable() {
        when(roomRepository.findByRoomId(anyString())).thenReturn(Optional.of(testRoom));

        boolean result = roomService.isRoomAvailable("room_test_001");

        assertTrue(result);
    }
}
