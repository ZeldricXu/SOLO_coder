package com.meeting.builder;

import com.meeting.dto.MeetingCreateRequest;
import com.meeting.entity.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static MeetingRoom buildMeetingRoom() {
        return MeetingRoom.builder()
                .roomId("room_test_001")
                .roomName("测试会议室")
                .roomCapacity(10)
                .roomLocation("测试楼1层")
                .roomStatus("available")
                .roomFeatures(Arrays.asList("投影", "白板"))
                .build();
    }

    public static MeetingRoom buildLargeMeetingRoom() {
        return MeetingRoom.builder()
                .roomId("room_test_002")
                .roomName("大型会议室")
                .roomCapacity(50)
                .roomLocation("测试楼2层")
                .roomStatus("available")
                .roomFeatures(Arrays.asList("投影", "白板", "音响", "视频会议"))
                .build();
    }

    public static MeetingRoom buildOccupiedMeetingRoom() {
        return MeetingRoom.builder()
                .roomId("room_test_003")
                .roomName("已占用会议室")
                .roomCapacity(20)
                .roomLocation("测试楼3层")
                .roomStatus("occupied")
                .roomFeatures(Arrays.asList("投影", "白板"))
                .build();
    }

    public static MeetingRoom buildClosedMeetingRoom() {
        return MeetingRoom.builder()
                .roomId("room_test_004")
                .roomName("已关闭会议室")
                .roomCapacity(15)
                .roomLocation("测试楼4层")
                .roomStatus("closed")
                .roomFeatures(Arrays.asList("投影"))
                .build();
    }

    public static Meeting buildMeeting(String roomId, LocalDateTime startTime) {
        return Meeting.builder()
                .meetingId("meeting_test_001")
                .roomId(roomId)
                .meetingTopic("测试会议")
                .meetingType("regular")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusHours(1))
                .meetingStatus("scheduled")
                .organizerId("user_001")
                .build();
    }

    public static Meeting buildUrgentMeeting(String roomId, LocalDateTime startTime) {
        return Meeting.builder()
                .meetingId("meeting_urgent_001")
                .roomId(roomId)
                .meetingTopic("紧急会议")
                .meetingType("urgent")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusMinutes(30))
                .meetingStatus("scheduled")
                .organizerId("user_001")
                .build();
    }

    public static Meeting buildMeetingWithStatus(String roomId, LocalDateTime startTime, String status) {
        return Meeting.builder()
                .meetingId("meeting_test_002")
                .roomId(roomId)
                .meetingTopic("状态测试会议")
                .meetingType("regular")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusHours(1))
                .meetingStatus(status)
                .organizerId("user_001")
                .build();
    }

    public static Meeting buildCancelledMeeting(String roomId, LocalDateTime startTime) {
        return buildMeetingWithStatus(roomId, startTime, "cancelled");
    }

    public static Meeting buildInProgressMeeting(String roomId, LocalDateTime startTime) {
        return buildMeetingWithStatus(roomId, startTime, "in_progress");
    }

    public static Meeting buildCompletedMeeting(String roomId, LocalDateTime startTime) {
        return buildMeetingWithStatus(roomId, startTime, "completed");
    }

    public static Schedule buildSchedule(String meetingId, String roomId, LocalDateTime scheduleDate) {
        return Schedule.builder()
                .scheduleId("schedule_test_001")
                .meetingId(meetingId)
                .roomId(roomId)
                .scheduleDate(scheduleDate.toLocalDate())
                .scheduleStart(LocalTime.of(14, 0))
                .scheduleEnd(LocalTime.of(15, 0))
                .scheduleStatus("scheduled")
                .build();
    }

    public static Schedule buildScheduleWithTime(
            String meetingId, String roomId, LocalDateTime date,
            int startHour, int startMinute, int endHour, int endMinute) {
        return Schedule.builder()
                .scheduleId("schedule_test_" + System.currentTimeMillis())
                .meetingId(meetingId)
                .roomId(roomId)
                .scheduleDate(date.toLocalDate())
                .scheduleStart(LocalTime.of(startHour, startMinute))
                .scheduleEnd(LocalTime.of(endHour, endMinute))
                .scheduleStatus("scheduled")
                .build();
    }

    public static Attendee buildAttendee(String meetingId, String userId) {
        return Attendee.builder()
                .attendeeId("attendee_test_001")
                .meetingId(meetingId)
                .userId(userId)
                .userName("张三")
                .userEmail("zhangsan@example.com")
                .attendeeStatus("pending")
                .build();
    }

    public static Attendee buildConfirmedAttendee(String meetingId, String userId) {
        return Attendee.builder()
                .attendeeId("attendee_test_002")
                .meetingId(meetingId)
                .userId(userId)
                .userName("李四")
                .userEmail("lisi@example.com")
                .attendeeStatus("confirmed")
                .attendeeTime(LocalDateTime.now())
                .build();
    }

    public static Attendee buildDeclinedAttendee(String meetingId, String userId) {
        return Attendee.builder()
                .attendeeId("attendee_test_003")
                .meetingId(meetingId)
                .userId(userId)
                .userName("王五")
                .userEmail("wangwu@example.com")
                .attendeeStatus("declined")
                .attendeeTime(LocalDateTime.now())
                .rejectReason("时间冲突")
                .build();
    }

    public static Device buildDevice(String roomId, String deviceType) {
        return Device.builder()
                .deviceId("device_test_001")
                .roomId(roomId)
                .deviceType(deviceType)
                .deviceName("投影仪")
                .deviceStatus("available")
                .deviceFeatures("高清1080P")
                .build();
    }

    public static Device buildOccupiedDevice(String roomId, String deviceType) {
        return Device.builder()
                .deviceId("device_test_002")
                .roomId(roomId)
                .deviceType(deviceType)
                .deviceName("白板")
                .deviceStatus("occupied")
                .deviceFeatures("智能白板")
                .build();
    }

    public static Reminder buildReminder(String meetingId, String reminderType) {
        return Reminder.builder()
                .reminderId("reminder_test_001")
                .meetingId(meetingId)
                .reminderType(reminderType)
                .reminderTime(LocalDateTime.now().plusMinutes(30))
                .reminderStatus("pending")
                .reminderContent("会议即将开始提醒")
                .build();
    }

    public static Reminder buildSentReminder(String meetingId, String reminderType) {
        return Reminder.builder()
                .reminderId("reminder_test_002")
                .meetingId(meetingId)
                .reminderType(reminderType)
                .reminderTime(LocalDateTime.now().minusMinutes(10))
                .reminderStatus("sent")
                .reminderContent("会议即将开始提醒")
                .sentTime(LocalDateTime.now().minusMinutes(10))
                .build();
    }

    public static MeetingType buildMeetingType(String typeCode, String typeName) {
        return MeetingType.builder()
                .typeId("type_test_001")
                .typeCode(typeCode)
                .typeName(typeName)
                .description("测试会议类型")
                .defaultDurationMinutes(60)
                .requiredApproval(false)
                .status("active")
                .build();
    }

    public static MeetingStats buildMeetingStats(String statMonth) {
        return MeetingStats.builder()
                .statId("stat_test_001")
                .statMonth(statMonth)
                .meetingCount(50)
                .totalDurationMinutes(3000)
                .attendeeCount(200)
                .confirmedAttendeeCount(180)
                .reminderSentCount(150)
                .cancelledCount(5)
                .build();
    }

    public static MeetingHistory buildMeetingHistory(String meetingId, String actionType) {
        return MeetingHistory.builder()
                .historyId("history_test_001")
                .meetingId(meetingId)
                .actionType(actionType)
                .actionDetail("测试操作")
                .operatorId("user_001")
                .roomId("room_test_001")
                .meetingTopic("测试会议")
                .startTime(LocalDateTime.now().plusHours(1))
                .endTime(LocalDateTime.now().plusHours(2))
                .build();
    }

    public static MeetingCreateRequest buildMeetingCreateRequest(String roomId, LocalDateTime startTime) {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));
        attendees.add(buildAttendeeInfo("user_003", "李四", "lisi@example.com"));

        return MeetingCreateRequest.builder()
                .roomId(roomId)
                .meetingTopic("项目周会")
                .meetingType("regular")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusHours(1))
                .organizerId("user_001")
                .description("每周项目进度汇报")
                .attendees(attendees)
                .build();
    }

    public static MeetingCreateRequest buildSimpleMeetingCreateRequest(String roomId, LocalDateTime startTime) {
        return MeetingCreateRequest.builder()
                .roomId(roomId)
                .meetingTopic("简单测试会议")
                .meetingType("regular")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusHours(1))
                .organizerId("user_001")
                .build();
    }

    public static MeetingCreateRequest buildUrgentMeetingCreateRequest(String roomId, LocalDateTime startTime) {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));

        return MeetingCreateRequest.builder()
                .roomId(roomId)
                .meetingTopic("紧急故障处理")
                .meetingType("urgent")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusMinutes(30))
                .organizerId("user_001")
                .description("紧急处理生产环境故障")
                .attendees(attendees)
                .build();
    }

    public static MeetingCreateRequest buildMeetingWithManyAttendees(String roomId, LocalDateTime startTime) {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            attendees.add(buildAttendeeInfo(
                    "user_" + String.format("%03d", i),
                    "参会人" + i,
                    "user" + i + "@example.com"));
        }

        return MeetingCreateRequest.builder()
                .roomId(roomId)
                .meetingTopic("大型会议")
                .meetingType("training")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusHours(2))
                .organizerId("user_001")
                .description("全员培训")
                .attendees(attendees)
                .build();
    }

    public static MeetingCreateRequest.AttendeeInfo buildAttendeeInfo(String userId, String userName, String email) {
        return MeetingCreateRequest.AttendeeInfo.builder()
                .userId(userId)
                .userName(userName)
                .userEmail(email)
                .build();
    }

    public static MeetingCreateRequest.AttendeeInfo buildImportantAttendeeInfo(String userId, String userName, String email) {
        return MeetingCreateRequest.AttendeeInfo.builder()
                .userId(userId)
                .userName(userName)
                .userEmail(email)
                .important(true)
                .build();
    }

    public static List<MeetingCreateRequest.AttendeeInfo> buildMixedAttendees() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(buildImportantAttendeeInfo("user_important_001", "重要参会人1", "important1@example.com"));
        attendees.add(buildImportantAttendeeInfo("user_important_002", "重要参会人2", "important2@example.com"));
        attendees.add(buildAttendeeInfo("user_normal_001", "普通参会人1", "normal1@example.com"));
        attendees.add(buildAttendeeInfo("user_normal_002", "普通参会人2", "normal2@example.com"));
        attendees.add(buildAttendeeInfo("user_normal_003", "普通参会人3", "normal3@example.com"));
        return attendees;
    }

    public static MeetingCreateRequest buildMeetingWithNullUserId(String roomId, LocalDateTime startTime) {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(MeetingCreateRequest.AttendeeInfo.builder()
                .userId(null)
                .userName("无效用户")
                .userEmail("invalid@example.com")
                .build());

        return MeetingCreateRequest.builder()
                .roomId(roomId)
                .meetingTopic("测试无效用户会议")
                .meetingType("regular")
                .meetingStart(startTime)
                .meetingEnd(startTime.plusHours(1))
                .organizerId("user_001")
                .attendees(attendees)
                .build();
    }

    public static LocalDateTime buildFutureTime(int hoursFromNow) {
        return LocalDateTime.now().plusHours(hoursFromNow);
    }

    public static LocalDateTime buildFutureTimeAtHour(int hour) {
        return LocalDateTime.now().plusDays(1).withHour(hour).withMinute(0).withSecond(0);
    }

    public static LocalDateTime buildPastTime(int hoursAgo) {
        return LocalDateTime.now().minusHours(hoursAgo);
    }

    public static List<Schedule> buildOverlappingSchedules(String roomId, LocalDateTime baseDate) {
        List<Schedule> schedules = new ArrayList<>();
        schedules.add(buildScheduleWithTime("m1", roomId, baseDate, 9, 0, 10, 30));
        schedules.add(buildScheduleWithTime("m2", roomId, baseDate, 10, 0, 11, 30));
        schedules.add(buildScheduleWithTime("m3", roomId, baseDate, 14, 0, 15, 0));
        return schedules;
    }

    public static List<Schedule> buildNonOverlappingSchedules(String roomId, LocalDateTime baseDate) {
        List<Schedule> schedules = new ArrayList<>();
        schedules.add(buildScheduleWithTime("m1", roomId, baseDate, 9, 0, 10, 0));
        schedules.add(buildScheduleWithTime("m2", roomId, baseDate, 10, 30, 11, 30));
        schedules.add(buildScheduleWithTime("m3", roomId, baseDate, 14, 0, 15, 0));
        return schedules;
    }
}
