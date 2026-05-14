package com.meeting.service;

import com.meeting.entity.Attendee;
import com.meeting.entity.Meeting;
import com.meeting.repository.AttendeeRepository;
import com.meeting.repository.MeetingRepository;
import com.meeting.repository.ReminderRepository;
import com.meeting.service.ReminderConfirmService.ConfirmResult;
import com.meeting.service.ReminderConfirmService.ReminderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("提醒确认服务单元测试")
class ReminderConfirmServiceTest {

    @Mock
    private ReminderRepository reminderRepository;

    @Mock
    private AttendeeRepository attendeeRepository;

    @Mock
    private MeetingRepository meetingRepository;

    @InjectMocks
    private ReminderConfirmService reminderConfirmService;

    private Meeting testMeeting;
    private List<Attendee> testAttendees;

    @BeforeEach
    void setUp() {
        LocalDateTime futureTime = LocalDateTime.now().plusHours(2);
        testMeeting = Meeting.builder()
                .meetingId("meeting_reminder_001")
                .roomId("room_001")
                .meetingTopic("测试会议")
                .meetingStart(futureTime)
                .meetingEnd(futureTime.plusHours(1))
                .build();

        testAttendees = new ArrayList<>();
        testAttendees.add(Attendee.builder()
                .attendeeId("att_001")
                .meetingId("meeting_reminder_001")
                .userId("user_important_001")
                .userName("重要参会人")
                .userEmail("important@example.com")
                .attendeeStatus("pending")
                .build());
        testAttendees.add(Attendee.builder()
                .attendeeId("att_002")
                .meetingId("meeting_reminder_001")
                .userId("user_normal_001")
                .userName("普通参会人")
                .userEmail("normal@example.com")
                .attendeeStatus("pending")
                .build());
    }

    @Test
    @DisplayName("创建会议提醒 - 应成功为所有参会人员创建提醒状态")
    void createReminders_ShouldCreateStatus_ForAllAttendees() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<String> importantIds = Arrays.asList("user_important_001");
        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", importantIds);

        verify(reminderRepository, times(2)).save(any());

        ReminderStatus importantStatus = reminderConfirmService.getReminderStatus(
                "meeting_reminder_001", "user_important_001");
        ReminderStatus normalStatus = reminderConfirmService.getReminderStatus(
                "meeting_reminder_001", "user_normal_001");

        assertNotNull(importantStatus);
        assertNotNull(normalStatus);
        assertTrue(importantStatus.isImportant());
        assertFalse(normalStatus.isImportant());
    }

    @Test
    @DisplayName("处理提醒确认 - 确认参会应更新状态为已确认")
    void processReminderConfirm_ShouldUpdateStatus_WhenConfirmed() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        ConfirmResult result = reminderConfirmService.processReminderConfirm(
                "meeting_reminder_001", "user_normal_001", "confirmed");

        assertTrue(result.isSuccess());
        assertEquals("确认成功", result.getMessage());
        assertTrue(reminderConfirmService.isConfirmed("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("处理提醒确认 - 拒绝参会应更新状态为已确认")
    void processReminderConfirm_ShouldUpdateStatus_WhenDeclined() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        ConfirmResult result = reminderConfirmService.processReminderConfirm(
                "meeting_reminder_001", "user_normal_001", "declined");

        assertTrue(result.isSuccess());
        assertTrue(reminderConfirmService.isConfirmed("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("发送提醒 - 未确认的参会人员应能收到提醒")
    void sendReminder_ShouldIncrementCount_WhenNotConfirmed() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        int initialCount = reminderConfirmService.getSentCount("meeting_reminder_001", "user_normal_001");
        assertEquals(0, initialCount);

        boolean sent = reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001");

        assertTrue(sent);
        assertEquals(1, reminderConfirmService.getSentCount("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("发送提醒 - 已确认的参会人员不应再收到提醒")
    void sendReminder_ShouldNotSend_WhenAlreadyConfirmed() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());
        reminderConfirmService.processReminderConfirm("meeting_reminder_001", "user_normal_001", "confirmed");

        boolean sent = reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001");

        assertFalse(sent);
        assertEquals(0, reminderConfirmService.getSentCount("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("重试机制 - 未确认的提醒应支持最多3次重试")
    void sendReminder_ShouldAllowMaxThreeRetries() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        assertTrue(reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001"));
        assertTrue(reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001"));
        assertTrue(reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001"));

        assertFalse(reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001"));
        assertEquals(3, reminderConfirmService.getSentCount("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("重要参会人员确认策略 - 重要参会人员应有更高的确认次数要求")
    void getRequiredConfirmCount_ShouldBeHigher_ForImportantAttendees() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<String> importantIds = Arrays.asList("user_important_001");
        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", importantIds);

        ReminderStatus importantStatus = reminderConfirmService.getReminderStatus(
                "meeting_reminder_001", "user_important_001");
        ReminderStatus normalStatus = reminderConfirmService.getReminderStatus(
                "meeting_reminder_001", "user_normal_001");

        assertTrue(importantStatus.getRequiredConfirmCount() > normalStatus.getRequiredConfirmCount(),
                "重要参会人员应有更高的确认次数要求");
        assertEquals(2, importantStatus.getRequiredConfirmCount());
        assertEquals(1, normalStatus.getRequiredConfirmCount());
    }

    @Test
    @DisplayName("重要参会人员多次确认 - 重要参会人员应收到更多提醒次数")
    void sendReminder_ImportantAttendee_ShouldReceiveMoreReminders() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<String> importantIds = Arrays.asList("user_important_001");
        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", importantIds);

        for (int i = 0; i < 3; i++) {
            reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_important_001");
            reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001");
        }

        assertEquals(3, reminderConfirmService.getSentCount("meeting_reminder_001", "user_important_001"));
        assertEquals(3, reminderConfirmService.getSentCount("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("清除提醒状态 - 应该能够清除会议的所有提醒状态")
    void clearReminderStatus_ShouldRemoveAllStatuses_ForMeeting() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        assertNotNull(reminderConfirmService.getReminderStatus("meeting_reminder_001", "user_normal_001"));

        reminderConfirmService.clearReminderStatus("meeting_reminder_001");

        assertNull(reminderConfirmService.getReminderStatus("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("未确认统计 - 应该正确统计已确认和未确认的参会人员")
    void getUnconfirmedStats_ShouldReturnCorrectCounts() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        reminderConfirmService.processReminderConfirm("meeting_reminder_001", "user_important_001", "confirmed");

        Map<String, Integer> stats = reminderConfirmService.getUnconfirmedStats("meeting_reminder_001");

        assertEquals(2, stats.get("total"));
        assertEquals(1, stats.get("confirmed"));
        assertEquals(1, stats.get("pending"));
    }

    @Test
    @DisplayName("处理确认 - 对于不存在的提醒记录应返回失败")
    void processReminderConfirm_ShouldFail_WhenNoStatusExists() {
        ConfirmResult result = reminderConfirmService.processReminderConfirm(
                "non_existent_meeting", "user_001", "confirmed");

        assertFalse(result.isSuccess());
        assertEquals("未找到对应的提醒记录", result.getMessage());
    }

    @Test
    @DisplayName("已拒绝参会人员 - 不应为已拒绝参会人员创建提醒")
    void createReminders_ShouldSkip_DeclinedAttendees() {
        List<Attendee> mixedAttendees = new ArrayList<>();
        mixedAttendees.add(testAttendees.get(0));
        Attendee declined = Attendee.builder()
                .attendeeId("att_003")
                .meetingId("meeting_reminder_001")
                .userId("user_declined_001")
                .userName("已拒绝")
                .attendeeStatus("declined")
                .build();
        mixedAttendees.add(declined);

        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(mixedAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        verify(reminderRepository, times(1)).save(any());
        assertNull(reminderConfirmService.getReminderStatus("meeting_reminder_001", "user_declined_001"));
    }

    @Test
    @DisplayName("确认后不再发送 - 确认后提醒应立即停止发送")
    void sendReminder_ShouldStop_AfterConfirmation() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(attendeeRepository.findByMeetingId(anyString())).thenReturn(testAttendees);
        when(reminderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reminderConfirmService.createRemindersForMeeting("meeting_reminder_001", Collections.emptyList());

        reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001");
        reminderConfirmService.processReminderConfirm("meeting_reminder_001", "user_normal_001", "confirmed");

        assertFalse(reminderConfirmService.sendReminderToAttendee("meeting_reminder_001", "user_normal_001"));
        assertEquals(1, reminderConfirmService.getSentCount("meeting_reminder_001", "user_normal_001"));
    }

    @Test
    @DisplayName("获取不存在的提醒状态 - 应返回null")
    void getReminderStatus_ShouldReturnNull_WhenNotExists() {
        ReminderStatus status = reminderConfirmService.getReminderStatus("non_existent", "user_001");
        assertNull(status);
    }

    @Test
    @DisplayName("获取发送次数 - 对于不存在的提醒状态应返回0")
    void getSentCount_ShouldReturnZero_WhenNotExists() {
        int count = reminderConfirmService.getSentCount("non_existent", "user_001");
        assertEquals(0, count);
    }

    @Test
    @DisplayName("获取确认状态 - 对于不存在的提醒状态应返回false")
    void isConfirmed_ShouldReturnFalse_WhenNotExists() {
        boolean confirmed = reminderConfirmService.isConfirmed("non_existent", "user_001");
        assertFalse(confirmed);
    }
}
