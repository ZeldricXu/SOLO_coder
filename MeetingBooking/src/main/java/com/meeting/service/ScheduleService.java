package com.meeting.service;

import com.meeting.entity.Meeting;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.Schedule;
import com.meeting.exception.MeetingException;
import com.meeting.repository.ScheduleRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final RoomService roomService;

    private static final String SCHEDULE_STATUS_SCHEDULED = "scheduled";
    private static final String SCHEDULE_STATUS_IN_PROGRESS = "in_progress";
    private static final String SCHEDULE_STATUS_COMPLETED = "completed";
    private static final String SCHEDULE_STATUS_CANCELLED = "cancelled";

    public Schedule getScheduleById(String scheduleId) {
        return scheduleRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new MeetingException(404, "日程不存在: " + scheduleId));
    }

    public Schedule getScheduleByMeetingId(String meetingId) {
        return scheduleRepository.findByMeetingId(meetingId).stream().findFirst()
                .orElseThrow(() -> new MeetingException(404, "会议日程不存在: " + meetingId));
    }

    public List<Schedule> getSchedulesByRoomId(String roomId) {
        return scheduleRepository.findByRoomId(roomId);
    }

    public List<Schedule> getSchedulesByDate(LocalDate date) {
        return scheduleRepository.findByScheduleDate(date);
    }

    public List<Schedule> getSchedulesByRoomAndDate(String roomId, LocalDate date) {
        return scheduleRepository.findByRoomIdAndScheduleDate(roomId, date);
    }

    @Transactional
    public Schedule createSchedule(Meeting meeting, MeetingRoom room) {
        log.info("创建日程: meetingId={}, roomId={}", meeting.getMeetingId(), room.getRoomId());

        LocalDate scheduleDate = meeting.getMeetingStart().toLocalDate();
        LocalTime scheduleStart = meeting.getMeetingStart().toLocalTime();
        LocalTime scheduleEnd = meeting.getMeetingEnd().toLocalTime();

        if (!roomService.isRoomAvailableForSchedule(room.getRoomId(), scheduleDate, scheduleStart, scheduleEnd)) {
            throw new MeetingException(409, "会议室日程时间冲突");
        }

        Schedule schedule = Schedule.builder()
                .scheduleId(IdGenerator.generateScheduleId())
                .meetingId(meeting.getMeetingId())
                .roomId(room.getRoomId())
                .scheduleDate(scheduleDate)
                .scheduleStart(scheduleStart)
                .scheduleEnd(scheduleEnd)
                .scheduleStatus(SCHEDULE_STATUS_SCHEDULED)
                .build();

        return scheduleRepository.save(schedule);
    }

    @Transactional
    public Schedule updateScheduleStatus(String scheduleId, String status) {
        Schedule schedule = getScheduleById(scheduleId);
        schedule.setScheduleStatus(status);
        return scheduleRepository.save(schedule);
    }

    @Transactional
    public void updateScheduleStatusByMeetingId(String meetingId, String status) {
        List<Schedule> schedules = scheduleRepository.findByMeetingId(meetingId);
        for (Schedule schedule : schedules) {
            schedule.setScheduleStatus(status);
        }
        scheduleRepository.saveAll(schedules);
    }

    @Transactional
    public void cancelSchedule(String meetingId) {
        updateScheduleStatusByMeetingId(meetingId, SCHEDULE_STATUS_CANCELLED);
    }

    @Transactional
    public void completeSchedule(String meetingId) {
        updateScheduleStatusByMeetingId(meetingId, SCHEDULE_STATUS_COMPLETED);
    }

    @Transactional
    public void startSchedule(String meetingId) {
        updateScheduleStatusByMeetingId(meetingId, SCHEDULE_STATUS_IN_PROGRESS);
    }

    public boolean checkScheduleConflict(String roomId, LocalDate date, LocalTime startTime, LocalTime endTime) {
        List<String> activeStatuses = Arrays.asList(SCHEDULE_STATUS_SCHEDULED, SCHEDULE_STATUS_IN_PROGRESS);
        List<Schedule> conflicting = scheduleRepository.findConflictingSchedules(
                roomId, date, startTime, endTime, activeStatuses);
        return !conflicting.isEmpty();
    }

    public List<Schedule> getAllSchedules() {
        return scheduleRepository.findAll();
    }

    public void deleteSchedule(String scheduleId) {
        Schedule schedule = getScheduleById(scheduleId);
        scheduleRepository.delete(schedule);
    }
}
