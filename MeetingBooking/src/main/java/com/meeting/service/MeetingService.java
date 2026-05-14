package com.meeting.service;

import com.meeting.dto.MeetingCreateRequest;
import com.meeting.dto.MeetingCreateResponse;
import com.meeting.dto.MeetingDetailResponse;
import com.meeting.dto.MeetingListRequest;
import com.meeting.dto.PageResponse;
import com.meeting.entity.Attendee;
import com.meeting.entity.Device;
import com.meeting.entity.Meeting;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.Reminder;
import com.meeting.entity.Schedule;
import com.meeting.exception.MeetingException;
import com.meeting.repository.AttendeeRepository;
import com.meeting.repository.MeetingRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final AttendeeRepository attendeeRepository;
    private final RoomService roomService;
    private final ScheduleService scheduleService;
    private final DeviceService deviceService;
    private final AttendeeService attendeeService;
    private final ReminderService reminderService;
    private final StatsService statsService;
    private final HistoryService historyService;

    private static final String MEETING_STATUS_SCHEDULED = "scheduled";
    private static final String MEETING_STATUS_IN_PROGRESS = "in_progress";
    private static final String MEETING_STATUS_COMPLETED = "completed";
    private static final String MEETING_STATUS_CANCELLED = "cancelled";

    private static final String DEFAULT_MEETING_TYPE = "regular";

    public Meeting getMeetingById(String meetingId) {
        return meetingRepository.findByMeetingId(meetingId)
                .orElseThrow(() -> new MeetingException(404, "会议不存在: " + meetingId));
    }

    public MeetingDetailResponse getMeetingDetail(String meetingId) {
        Meeting meeting = getMeetingById(meetingId);
        MeetingRoom room = roomService.getRoomById(meeting.getRoomId());

        List<Attendee> attendees = attendeeService.getAttendeesByMeetingId(meetingId);
        List<Device> devices = deviceService.getDevicesByRoomId(meeting.getRoomId());
        List<Reminder> reminders = reminderService.getRemindersByMeetingId(meetingId);

        return MeetingDetailResponse.builder()
                .meetingId(meeting.getMeetingId())
                .roomId(meeting.getRoomId())
                .roomName(room.getRoomName())
                .roomLocation(room.getRoomLocation())
                .meetingTopic(meeting.getMeetingTopic())
                .meetingType(meeting.getMeetingType())
                .meetingStatus(meeting.getMeetingStatus())
                .meetingStart(meeting.getMeetingStart())
                .meetingEnd(meeting.getMeetingEnd())
                .organizerId(meeting.getOrganizerId())
                .organizerName(null)
                .description(meeting.getDescription())
                .attendees(attendees.stream()
                        .map(a -> MeetingDetailResponse.AttendeeDetail.builder()
                                .attendeeId(a.getAttendeeId())
                                .userId(a.getUserId())
                                .userName(a.getUserName())
                                .userEmail(a.getUserEmail())
                                .attendeeStatus(a.getAttendeeStatus())
                                .attendeeTime(a.getAttendeeTime())
                                .build())
                        .collect(Collectors.toList()))
                .devices(devices.stream()
                        .map(d -> MeetingDetailResponse.DeviceInfo.builder()
                                .deviceId(d.getDeviceId())
                                .deviceType(d.getDeviceType())
                                .deviceName(d.getDeviceName())
                                .deviceStatus(d.getDeviceStatus())
                                .build())
                        .collect(Collectors.toList()))
                .reminders(reminders.stream()
                        .map(r -> MeetingDetailResponse.ReminderInfo.builder()
                                .reminderId(r.getReminderId())
                                .reminderType(r.getReminderType())
                                .reminderTime(r.getReminderTime())
                                .reminderStatus(r.getReminderStatus())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    public List<Meeting> getAllMeetings() {
        return meetingRepository.findAll();
    }

    public List<Meeting> getMeetingsByRoomId(String roomId) {
        return meetingRepository.findByRoomId(roomId);
    }

    public List<Meeting> getMeetingsByOrganizer(String organizerId) {
        return meetingRepository.findByOrganizerIdOrderByStartTimeDesc(organizerId);
    }

    public List<Meeting> getActiveMeetings() {
        return meetingRepository.findActiveMeetings();
    }

    public PageResponse<Meeting> getMeetings(MeetingListRequest request) {
        List<Meeting> meetings = new ArrayList<>();

        if (request.getRoomId() != null) {
            meetings.addAll(meetingRepository.findByRoomId(request.getRoomId()));
        } else if (request.getOrganizerId() != null) {
            meetings.addAll(meetingRepository.findByOrganizerId(request.getOrganizerId()));
        } else if (request.getUserId() != null) {
            List<Attendee> attendances = attendeeRepository.findByUserId(request.getUserId());
            for (Attendee a : attendances) {
                meetingRepository.findByMeetingId(a.getMeetingId()).ifPresent(meetings::add);
            }
        } else if (request.getMeetingStatus() != null) {
            meetings.addAll(meetingRepository.findByMeetingStatus(request.getMeetingStatus()));
        } else if (request.getStatusList() != null && !request.getStatusList().isEmpty()) {
            for (String status : request.getStatusList()) {
                meetings.addAll(meetingRepository.findByMeetingStatus(status));
            }
        } else {
            meetings.addAll(meetingRepository.findAll());
        }

        int page = request.getPage() != null ? request.getPage() : 0;
        int size = request.getSize() != null ? request.getSize() : 20;

        int start = page * size;
        int end = Math.min(start + size, meetings.size());

        List<Meeting> pageContent = start < meetings.size() ? meetings.subList(start, end) : new ArrayList<>();

        return PageResponse.<Meeting>builder()
                .content(pageContent)
                .totalElements(meetings.size())
                .totalPages((int) Math.ceil((double) meetings.size() / size))
                .currentPage(page)
                .pageSize(size)
                .hasNext(end < meetings.size())
                .hasPrevious(page > 0)
                .build();
    }

    @Transactional
    public MeetingCreateResponse createMeeting(MeetingCreateRequest request) {
        log.info("创建会议: roomId={}, topic={}, organizer={}",
                request.getRoomId(), request.getMeetingTopic(), request.getOrganizerId());

        validateMeetingTime(request.getMeetingStart(), request.getMeetingEnd());

        MeetingRoom room = roomService.getRoomById(request.getRoomId());

        if (!"available".equals(room.getRoomStatus())) {
            if ("closed".equals(room.getRoomStatus())) {
                throw new MeetingException(400, "会议室已关闭");
            } else if ("maintenance".equals(room.getRoomStatus())) {
                throw new MeetingException(400, "会议室正在维护");
            } else {
                throw new MeetingException(400, "会议室状态不可用: " + room.getRoomStatus());
            }
        }

        List<String> activeStatuses = Arrays.asList(MEETING_STATUS_SCHEDULED, MEETING_STATUS_IN_PROGRESS);
        List<Meeting> conflictingMeetings = meetingRepository.findConflictingMeetings(
                room.getRoomId(), request.getMeetingStart(), request.getMeetingEnd(), activeStatuses);
        if (!conflictingMeetings.isEmpty()) {
            throw new MeetingException(409, "会议室已被预约，时间冲突");
        }

        if (!deviceService.checkRoomDevicesAvailable(room.getRoomId())) {
            throw new MeetingException(400, "会议室设备不可用");
        }

        String meetingType = request.getMeetingType() != null ? request.getMeetingType() : DEFAULT_MEETING_TYPE;

        Meeting meeting = Meeting.builder()
                .meetingId(IdGenerator.generateMeetingId())
                .roomId(room.getRoomId())
                .meetingTopic(request.getMeetingTopic())
                .meetingType(meetingType)
                .meetingStart(request.getMeetingStart())
                .meetingEnd(request.getMeetingEnd())
                .meetingStatus(MEETING_STATUS_SCHEDULED)
                .organizerId(request.getOrganizerId())
                .description(request.getDescription())
                .build();

        meeting = meetingRepository.save(meeting);
        log.info("会议创建成功: meetingId={}", meeting.getMeetingId());

        Schedule schedule = scheduleService.createSchedule(meeting, room);

        if (request.getAttendees() != null && !request.getAttendees().isEmpty()) {
            attendeeService.inviteAttendees(meeting.getMeetingId(), request.getAttendees());
            for (int i = 0; i < request.getAttendees().size(); i++) {
                statsService.incrementAttendeeCount(meeting.getMeetingStart());
            }
        }

        deviceService.checkAndOccupyRoomDevices(room.getRoomId());

        reminderService.createMeetingStartReminder(meeting, 30);
        reminderService.createMeetingEndReminder(meeting, 5);

        Duration duration = Duration.between(request.getMeetingStart(), request.getMeetingEnd());
        statsService.incrementMeetingCount(meeting.getMeetingStart(), duration.toMinutes());

        historyService.recordMeetingCreate(meeting, request.getOrganizerId());

        return MeetingCreateResponse.builder()
                .meetingId(meeting.getMeetingId())
                .roomId(room.getRoomId())
                .roomName(room.getRoomName())
                .meetingTopic(meeting.getMeetingTopic())
                .meetingType(meeting.getMeetingType())
                .meetingStart(meeting.getMeetingStart())
                .meetingEnd(meeting.getMeetingEnd())
                .meetingStatus(meeting.getMeetingStatus())
                .organizerId(meeting.getOrganizerId())
                .scheduleId(schedule.getScheduleId())
                .build();
    }

    private void validateMeetingTime(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            throw new MeetingException(400, "会议时间不能为空");
        }
        if (endTime.isBefore(startTime)) {
            throw new MeetingException(400, "会议结束时间不能早于开始时间");
        }
        if (endTime.isEqual(startTime)) {
            throw new MeetingException(400, "会议结束时间不能等于开始时间");
        }
        if (startTime.isBefore(LocalDateTime.now())) {
            throw new MeetingException(400, "会议开始时间不能早于当前时间");
        }
    }

    @Transactional
    public Meeting updateMeeting(String meetingId, Meeting meetingUpdate) {
        Meeting existingMeeting = getMeetingById(meetingId);

        if (MEETING_STATUS_IN_PROGRESS.equals(existingMeeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议进行中，无法修改");
        }
        if (MEETING_STATUS_COMPLETED.equals(existingMeeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议已完成，无法修改");
        }
        if (MEETING_STATUS_CANCELLED.equals(existingMeeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议已取消，无法修改");
        }

        if (meetingUpdate.getMeetingTopic() != null) {
            existingMeeting.setMeetingTopic(meetingUpdate.getMeetingTopic());
        }
        if (meetingUpdate.getMeetingType() != null) {
            existingMeeting.setMeetingType(meetingUpdate.getMeetingType());
        }
        if (meetingUpdate.getDescription() != null) {
            existingMeeting.setDescription(meetingUpdate.getDescription());
        }

        log.info("更新会议: meetingId={}", meetingId);
        Meeting updated = meetingRepository.save(existingMeeting);
        historyService.recordMeetingUpdate(updated, existingMeeting.getOrganizerId());

        return updated;
    }

    @Transactional
    public void cancelMeeting(String meetingId, String operatorId) {
        log.info("取消会议: meetingId={}, operator={}", meetingId, operatorId);

        Meeting meeting = getMeetingById(meetingId);

        if (MEETING_STATUS_COMPLETED.equals(meeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议已完成，无法取消");
        }
        if (MEETING_STATUS_CANCELLED.equals(meeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议已取消");
        }

        meeting.setMeetingStatus(MEETING_STATUS_CANCELLED);
        meetingRepository.save(meeting);

        scheduleService.cancelSchedule(meetingId);

        deviceService.releaseRoomDevices(meeting.getRoomId());

        statsService.incrementCancelledCount(meeting.getMeetingStart());

        historyService.recordMeetingCancel(meeting, operatorId);

        log.info("会议已取消: meetingId={}", meetingId);
    }

    @Transactional
    public void startMeeting(String meetingId) {
        log.info("开始会议: meetingId={}", meetingId);

        Meeting meeting = getMeetingById(meetingId);

        if (!MEETING_STATUS_SCHEDULED.equals(meeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议状态不允许开始: " + meeting.getMeetingStatus());
        }

        meeting.setMeetingStatus(MEETING_STATUS_IN_PROGRESS);
        meetingRepository.save(meeting);

        scheduleService.startSchedule(meetingId);

        historyService.recordMeetingStart(meeting, meeting.getOrganizerId());

        log.info("会议已开始: meetingId={}", meetingId);
    }

    @Transactional
    public void completeMeeting(String meetingId) {
        log.info("完成会议: meetingId={}", meetingId);

        Meeting meeting = getMeetingById(meetingId);

        if (!MEETING_STATUS_IN_PROGRESS.equals(meeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议未开始，无法完成");
        }

        meeting.setMeetingStatus(MEETING_STATUS_COMPLETED);
        meetingRepository.save(meeting);

        scheduleService.completeSchedule(meetingId);

        deviceService.releaseRoomDevices(meeting.getRoomId());

        historyService.recordMeetingComplete(meeting, meeting.getOrganizerId());

        log.info("会议已完成: meetingId={}", meetingId);
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void updateMeetingStatusAutomatically() {
        LocalDateTime now = LocalDateTime.now();

        List<Meeting> scheduledMeetings = meetingRepository.findByMeetingStatus(MEETING_STATUS_SCHEDULED);
        for (Meeting meeting : scheduledMeetings) {
            if (meeting.getMeetingStart().isBefore(now) || meeting.getMeetingStart().isEqual(now)) {
                try {
                    startMeeting(meeting.getMeetingId());
                } catch (Exception e) {
                    log.error("自动开始会议失败: meetingId={}", meeting.getMeetingId(), e);
                }
            }
        }

        List<Meeting> inProgressMeetings = meetingRepository.findByMeetingStatus(MEETING_STATUS_IN_PROGRESS);
        for (Meeting meeting : inProgressMeetings) {
            if (meeting.getMeetingEnd().isBefore(now) || meeting.getMeetingEnd().isEqual(now)) {
                try {
                    completeMeeting(meeting.getMeetingId());
                } catch (Exception e) {
                    log.error("自动完成会议失败: meetingId={}", meeting.getMeetingId(), e);
                }
            }
        }
    }

    public List<Meeting> getMeetingsInRange(LocalDateTime start, LocalDateTime end) {
        return meetingRepository.findByMeetingStartBetween(start, end);
    }

    public boolean checkMeetingExists(String meetingId) {
        return meetingRepository.existsByMeetingId(meetingId);
    }
}
