package com.meeting.service;

import com.meeting.builder.TestDataBuilder;
import com.meeting.entity.Meeting;
import com.meeting.entity.Schedule;
import com.meeting.repository.MeetingRepository;
import com.meeting.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("日程服务单元测试")
class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private ScheduleService scheduleService;

    private String testRoomId;
    private String testMeetingId;
    private LocalDateTime testDate;
    private Meeting testMeeting;

    @BeforeEach
    void setUp() {
        testRoomId = "room_schedule_001";
        testMeetingId = "meeting_schedule_001";
        testDate = LocalDateTime.now().plusDays(1);
        testMeeting = Meeting.builder()
                .meetingId(testMeetingId)
                .roomId(testRoomId)
                .meetingTopic("测试会议")
                .meetingStart(testDate.withHour(14).withMinute(0))
                .meetingEnd(testDate.withHour(15).withMinute(0))
                .meetingStatus("scheduled")
                .organizerId("user_001")
                .build();
    }

    @Test
    @DisplayName("创建日程 - 应成功创建日程记录")
    void createSchedule_ShouldCreateSuccessfully() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.of(testMeeting));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Schedule schedule = scheduleService.createSchedule(testMeetingId);

        assertNotNull(schedule);
        assertEquals(testMeetingId, schedule.getMeetingId());
        assertEquals(testRoomId, schedule.getRoomId());
        assertEquals("scheduled", schedule.getScheduleStatus());
        verify(scheduleRepository, times(1)).save(any(Schedule.class));
    }

    @Test
    @DisplayName("创建日程 - 会议不存在时应抛异常")
    void createSchedule_ShouldThrowException_WhenMeetingNotExists() {
        when(meetingRepository.findByMeetingId(anyString())).thenReturn(Optional.empty());

        assertThrows(com.meeting.exception.MeetingException.class, () -> {
            scheduleService.createSchedule("non_existent_meeting");
        });
    }

    @Test
    @DisplayName("日程时间冲突 - 完全重叠应检测到冲突")
    void checkScheduleConflict_ShouldReturnTrue_WhenCompleteOverlap() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 14, 0, 16, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 30, 15, 30);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertTrue(hasConflict);
    }

    @Test
    @DisplayName("日程时间冲突 - 开始时间重叠应检测到冲突")
    void checkScheduleConflict_ShouldReturnTrue_WhenStartOverlap() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 14, 0, 15, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 30, 16, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertTrue(hasConflict);
    }

    @Test
    @DisplayName("日程时间冲突 - 结束时间重叠应检测到冲突")
    void checkScheduleConflict_ShouldReturnTrue_WhenEndOverlap() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 14, 30, 16, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 0, 15, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertTrue(hasConflict);
    }

    @Test
    @DisplayName("日程时间不冲突 - 相邻时间不应检测到冲突")
    void checkScheduleConflict_ShouldReturnFalse_WhenAdjacentTime() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 14, 0, 15, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 15, 0, 16, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertFalse(hasConflict);
    }

    @Test
    @DisplayName("日程时间不冲突 - 完全不重叠不应检测到冲突")
    void checkScheduleConflict_ShouldReturnFalse_WhenNoOverlap() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 9, 0, 10, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 0, 15, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertFalse(hasConflict);
    }

    @Test
    @DisplayName("日程时间不冲突 - 无现有日程时不应检测到冲突")
    void checkScheduleConflict_ShouldReturnFalse_WhenNoExistingSchedules() {
        LocalDateTime baseDate = testDate;
        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(Collections.emptyList());

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 0, 15, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertFalse(hasConflict);
    }

    @Test
    @DisplayName("日程状态流转 - 应能更新日程状态")
    void updateScheduleStatus_ShouldUpdateCorrectly() {
        Schedule existingSchedule = TestDataBuilder.buildSchedule(testMeetingId, testRoomId, testDate);

        when(scheduleRepository.findByMeetingId(anyString())).thenReturn(Optional.of(existingSchedule));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = scheduleService.updateScheduleStatusByMeetingId(testMeetingId, "in_progress");

        assertTrue(result);
        verify(scheduleRepository, times(1)).save(any(Schedule.class));
    }

    @Test
    @DisplayName("日程状态流转 - 不存在的日程应返回false")
    void updateScheduleStatus_ShouldReturnFalse_WhenNotExists() {
        when(scheduleRepository.findByMeetingId(anyString())).thenReturn(Optional.empty());

        boolean result = scheduleService.updateScheduleStatusByMeetingId("non_existent", "completed");

        assertFalse(result);
    }

    @Test
    @DisplayName("日程状态生命周期 - 已安排->进行中->已完成")
    void scheduleStatusLifecycle_ShouldTransitionCorrectly() {
        Schedule schedule = TestDataBuilder.buildSchedule(testMeetingId, testRoomId, testDate);

        when(scheduleRepository.findByMeetingId(anyString())).thenReturn(Optional.of(schedule));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> {
            Schedule s = invocation.getArgument(0);
            schedule.setScheduleStatus(s.getScheduleStatus());
            return s;
        });

        assertEquals("scheduled", schedule.getScheduleStatus());

        assertTrue(scheduleService.updateScheduleStatusByMeetingId(testMeetingId, "in_progress"));
        assertEquals("in_progress", schedule.getScheduleStatus());

        assertTrue(scheduleService.updateScheduleStatusByMeetingId(testMeetingId, "completed"));
        assertEquals("completed", schedule.getScheduleStatus());
    }

    @Test
    @DisplayName("获取会议室日程列表 - 应返回所有相关日程")
    void getSchedulesByRoom_ShouldReturnAllSchedules() {
        List<Schedule> schedules = TestDataBuilder.buildNonOverlappingSchedules(testRoomId, testDate);
        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(schedules);

        List<Schedule> result = scheduleService.getSchedulesByRoom(testRoomId, testDate.toLocalDate());

        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("获取会议日程 - 应返回对应的日程")
    void getScheduleByMeetingId_ShouldReturnSchedule() {
        Schedule schedule = TestDataBuilder.buildSchedule(testMeetingId, testRoomId, testDate);
        when(scheduleRepository.findByMeetingId(anyString())).thenReturn(Optional.of(schedule));

        Schedule result = scheduleService.getScheduleByMeetingId(testMeetingId);

        assertNotNull(result);
        assertEquals(testMeetingId, result.getMeetingId());
    }

    @Test
    @DisplayName("获取会议日程 - 不存在时应返回null")
    void getScheduleByMeetingId_ShouldReturnNull_WhenNotExists() {
        when(scheduleRepository.findByMeetingId(anyString())).thenReturn(Optional.empty());

        Schedule result = scheduleService.getScheduleByMeetingId(testMeetingId);

        assertNull(result);
    }

    @Test
    @DisplayName("删除日程 - 应成功删除")
    void deleteSchedule_ShouldDeleteSuccessfully() {
        Schedule schedule = TestDataBuilder.buildSchedule(testMeetingId, testRoomId, testDate);
        when(scheduleRepository.findByMeetingId(anyString())).thenReturn(Optional.of(schedule));
        doNothing().when(scheduleRepository).delete(any(Schedule.class));

        boolean result = scheduleService.deleteScheduleByMeetingId(testMeetingId);

        assertTrue(result);
        verify(scheduleRepository, times(1)).delete(any(Schedule.class));
    }

    @Test
    @DisplayName("删除日程 - 不存在时应返回false")
    void deleteSchedule_ShouldReturnFalse_WhenNotExists() {
        when(scheduleRepository.findByMeetingId(anyString())).thenReturn(Optional.empty());

        boolean result = scheduleService.deleteScheduleByMeetingId("non_existent");

        assertFalse(result);
    }

    @Test
    @DisplayName("多个冲突检测 - 应检测到任何一个冲突")
    void checkScheduleConflict_ShouldDetectAnyConflict() {
        LocalDateTime baseDate = testDate;
        Schedule schedule1 = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 9, 0, 10, 0);
        Schedule schedule2 = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 0, 15, 0);
        Schedule schedule3 = TestDataBuilder.buildScheduleWithTime("m3", testRoomId, baseDate, 16, 0, 17, 0);
        List<Schedule> existingSchedules = Arrays.asList(schedule1, schedule2, schedule3);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m4", testRoomId, baseDate, 14, 30, 15, 30);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertTrue(hasConflict);
    }

    @Test
    @DisplayName("包含冲突检测 - 新日程完全包含在现有日程内")
    void checkScheduleConflict_ShouldReturnTrue_WhenNewInsideExisting() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 14, 0, 17, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 15, 0, 16, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertTrue(hasConflict);
    }

    @Test
    @DisplayName("被包含冲突检测 - 现有日程完全在新日程内")
    void checkScheduleConflict_ShouldReturnTrue_WhenExistingInsideNew() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 15, 0, 16, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 0, 17, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertTrue(hasConflict);
    }

    @Test
    @DisplayName("相同时间冲突 - 完全相同时间应检测到冲突")
    void checkScheduleConflict_ShouldReturnTrue_WhenSameTime() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 14, 0, 15, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 14, 0, 15, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertTrue(hasConflict);
    }

    @Test
    @DisplayName("时间点重叠 - 开始时间等于结束时间不应冲突")
    void checkScheduleConflict_ShouldNotConflict_WhenStartEqualsEnd() {
        LocalDateTime baseDate = testDate;
        Schedule existing = TestDataBuilder.buildScheduleWithTime("m1", testRoomId, baseDate, 14, 0, 15, 0);
        List<Schedule> existingSchedules = Collections.singletonList(existing);

        when(scheduleRepository.findByRoomIdAndDateRange(anyString(), any(), any())).thenReturn(existingSchedules);

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", testRoomId, baseDate, 15, 0, 16, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(testRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertFalse(hasConflict);
    }

    @Test
    @DisplayName("不同会议室 - 不同会议室的日程不应冲突")
    void checkScheduleConflict_ShouldNotConflict_WhenDifferentRoom() {
        String otherRoomId = "room_other_001";
        LocalDateTime baseDate = testDate;

        when(scheduleRepository.findByRoomIdAndDateRange(eq(otherRoomId), any(), any())).thenReturn(Collections.emptyList());

        Schedule newSchedule = TestDataBuilder.buildScheduleWithTime("m2", otherRoomId, baseDate, 14, 0, 15, 0);

        boolean hasConflict = scheduleService.checkScheduleConflict(otherRoomId,
                newSchedule.getScheduleDate(),
                newSchedule.getScheduleStart(),
                newSchedule.getScheduleEnd());

        assertFalse(hasConflict);
    }
}
