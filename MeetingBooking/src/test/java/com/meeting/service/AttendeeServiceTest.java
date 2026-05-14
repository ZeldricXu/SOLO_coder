package com.meeting.service;

import com.meeting.dto.AttendeeConfirmRequest;
import com.meeting.dto.AttendeeConfirmResponse;
import com.meeting.entity.Attendee;
import com.meeting.entity.Meeting;
import com.meeting.exception.MeetingException;
import com.meeting.repository.AttendeeRepository;
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
class AttendeeServiceTest {

    @Mock
    private AttendeeRepository attendeeRepository;

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private StatsService statsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private AttendeeService attendeeService;

    private Attendee testAttendee;
    private Meeting testMeeting;

    @BeforeEach
    void setUp() {
        testAttendee = Attendee.builder()
                .attendeeId("attendee_test_001")
                .meetingId("meeting_001")
                .userId("user_001")
                .userName("张三")
                .userEmail("zhangsan@example.com")
                .attendeeStatus("pending")
                .build();

        testMeeting = Meeting.builder()
                .meetingId("meeting_001")
                .meetingTopic("测试会议")
                .meetingStatus("scheduled")
                .meetingStart(LocalDateTime.now().plusHours(1))
                .build();
    }

    @Test
    void getAttendeeById_ShouldReturnAttendee_WhenExists() {
        when(attendeeRepository.findByAttendeeId(anyString())).thenReturn(Optional.of(testAttendee));

        Attendee result = attendeeService.getAttendeeById("attendee_test_001");

        assertNotNull(result);
        assertEquals("attendee_test_001", result.getAttendeeId());
        assertEquals("张三", result.getUserName());
    }

    @Test
    void getAttendeeById_ShouldThrowException_WhenNotExists() {
        when(attendeeRepository.findByAttendeeId(anyString())).thenReturn(Optional.empty());

        assertThrows(MeetingException.class, () -> {
            attendeeService.getAttendeeById("attendee_not_exist");
        });
    }

    @Test
    void getAttendeesByMeetingId_ShouldReturnAttendees() {
        List<Attendee> attendees = Arrays.asList(testAttendee);
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(attendees);

        List<Attendee> result = attendeeService.getAttendeesByMeetingId("meeting_001");

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void confirmAttendance_ShouldConfirm_WhenStatusConfirmed() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingIdAndUserId(anyString(), anyString()))
                .thenReturn(Optional.of(testAttendee));
        when(attendeeRepository.save(any(Attendee.class))).thenReturn(testAttendee);

        AttendeeConfirmRequest request = AttendeeConfirmRequest.builder()
                .meetingId("meeting_001")
                .userId("user_001")
                .attendeeStatus("confirmed")
                .build();

        AttendeeConfirmResponse result = attendeeService.confirmAttendance(request);

        assertNotNull(result);
        assertEquals("attendee_test_001", result.getAttendeeId());
        assertEquals("confirmed", result.getAttendeeStatus());
    }

    @Test
    void confirmAttendance_ShouldThrowException_WhenMeetingCancelled() {
        Meeting cancelledMeeting = Meeting.builder()
                .meetingId("meeting_002")
                .meetingStatus("cancelled")
                .build();

        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(cancelledMeeting));

        AttendeeConfirmRequest request = AttendeeConfirmRequest.builder()
                .meetingId("meeting_002")
                .userId("user_001")
                .attendeeStatus("confirmed")
                .build();

        assertThrows(MeetingException.class, () -> {
            attendeeService.confirmAttendance(request);
        });
    }

    @Test
    void confirmAttendance_ShouldThrowException_WhenInvalidStatus() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingIdAndUserId(anyString(), anyString()))
                .thenReturn(Optional.of(testAttendee));

        AttendeeConfirmRequest request = AttendeeConfirmRequest.builder()
                .meetingId("meeting_001")
                .userId("user_001")
                .attendeeStatus("invalid_status")
                .build();

        assertThrows(MeetingException.class, () -> {
            attendeeService.confirmAttendance(request);
        });
    }

    @Test
    void countAttendees_ShouldReturnCount() {
        when(attendeeRepository.countByMeetingId(anyString())).thenReturn(5L);

        long result = attendeeService.countAttendees("meeting_001");

        assertEquals(5, result);
    }

    @Test
    void countConfirmedAttendees_ShouldReturnConfirmedCount() {
        when(attendeeRepository.countByMeetingIdAndStatus(anyString(), anyString())).thenReturn(3L);

        long result = attendeeService.countConfirmedAttendees("meeting_001");

        assertEquals(3, result);
    }
}
