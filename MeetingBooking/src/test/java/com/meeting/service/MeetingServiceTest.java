package com.meeting.service;

import com.meeting.builder.TestDataBuilder;
import com.meeting.dto.MeetingCreateRequest;
import com.meeting.dto.MeetingCreateResponse;
import com.meeting.entity.Meeting;
import com.meeting.entity.MeetingRoom;
import com.meeting.exception.MeetingException;
import com.meeting.repository.MeetingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private com.meeting.repository.AttendeeRepository attendeeRepository;

    @Mock
    private RoomService roomService;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private DeviceService deviceService;

    @Mock
    private AttendeeService attendeeService;

    @Mock
    private ReminderService reminderService;

    @Mock
    private StatsService statsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private MeetingService meetingService;

    private MeetingRoom testRoom;
    private LocalDateTime futureTime;

    @BeforeEach
    void setUp() {
        testRoom = TestDataBuilder.buildMeetingRoom();
        futureTime = TestDataBuilder.buildFutureTime(2);
    }

    @Test
    void getMeetingById_ShouldReturnMeeting_WhenMeetingExists() {
        Meeting meeting = TestDataBuilder.buildMeeting("room_001", futureTime);
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(meeting));

        Meeting result = meetingService.getMeetingById("meeting_test_001");

        assertNotNull(result);
        assertEquals("meeting_test_001", result.getMeetingId());
    }

    @Test
    void getMeetingById_ShouldThrowException_WhenMeetingNotExists() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.empty());

        assertThrows(MeetingException.class, () -> {
            meetingService.getMeetingById("meeting_not_exist");
        });
    }

    @Test
    void getAllMeetings_ShouldReturnAllMeetings() {
        Meeting meeting = TestDataBuilder.buildMeeting("room_001", futureTime);
        List<Meeting> meetings = Arrays.asList(meeting);
        when(meetingRepository.findAll()).thenReturn(meetings);

        List<Meeting> result = meetingService.getAllMeetings();

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void createMeeting_ShouldFail_WhenEndTimeBeforeStartTime() {
        MeetingCreateRequest request = TestDataBuilder.buildSimpleMeetingCreateRequest("room_001", futureTime);
        request.setMeetingEnd(futureTime.minusHours(1));

        assertThrows(MeetingException.class, () -> {
            meetingService.createMeeting(request);
        });
    }

    @Test
    void createMeeting_ShouldFail_WhenStartTimeInPast() {
        LocalDateTime pastTime = LocalDateTime.now().minusHours(1);
        MeetingCreateRequest request = TestDataBuilder.buildSimpleMeetingCreateRequest("room_001", pastTime);

        assertThrows(MeetingException.class, () -> {
            meetingService.createMeeting(request);
        });
    }

    @Test
    void checkMeetingExists_ShouldReturnTrue_WhenMeetingExists() {
        when(meetingRepository.existsByMeetingId(anyString())).thenReturn(true);

        boolean result = meetingService.checkMeetingExists("meeting_test_001");

        assertTrue(result);
    }

    @Test
    void checkMeetingExists_ShouldReturnFalse_WhenMeetingNotExists() {
        when(meetingRepository.existsByMeetingId(anyString())).thenReturn(false);

        boolean result = meetingService.checkMeetingExists("meeting_not_exist");

        assertFalse(result);
    }
}
