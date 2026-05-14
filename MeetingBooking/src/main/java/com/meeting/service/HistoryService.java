package com.meeting.service;

import com.meeting.dto.MeetingCreateRequest;
import com.meeting.entity.Attendee;
import com.meeting.entity.Meeting;
import com.meeting.entity.MeetingHistory;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.Reminder;
import com.meeting.repository.MeetingHistoryRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final MeetingHistoryRepository historyRepository;

    public List<MeetingHistory> getAllHistory() {
        return historyRepository.findAllOrderByCreatedAtDesc();
    }

    public List<MeetingHistory> getHistoryByMeetingId(String meetingId) {
        return historyRepository.findByMeetingIdOrderByCreatedAtDesc(meetingId);
    }

    public List<MeetingHistory> getHistoryByRoomId(String roomId) {
        return historyRepository.findByRoomIdOrderByCreatedAtDesc(roomId);
    }

    public List<MeetingHistory> getHistoryByOperator(String operatorId) {
        return historyRepository.findByOperatorId(operatorId);
    }

    public List<MeetingHistory> getHistoryByActionType(String actionType) {
        return historyRepository.findByActionType(actionType);
    }

    public List<MeetingHistory> getHistoryByDateRange(LocalDateTime start, LocalDateTime end) {
        return historyRepository.findByCreatedAtBetween(start, end);
    }

    @Transactional
    public void recordMeetingCreate(Meeting meeting, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(meeting.getMeetingId())
                .actionType("MEETING_CREATE")
                .actionDetail(String.format("创建会议: %s", meeting.getMeetingTopic()))
                .operatorId(operatorId)
                .roomId(meeting.getRoomId())
                .meetingTopic(meeting.getMeetingTopic())
                .startTime(meeting.getMeetingStart())
                .endTime(meeting.getMeetingEnd())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 创建会议 meetingId={}", meeting.getMeetingId());
    }

    @Transactional
    public void recordMeetingUpdate(Meeting meeting, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(meeting.getMeetingId())
                .actionType("MEETING_UPDATE")
                .actionDetail(String.format("更新会议: %s", meeting.getMeetingTopic()))
                .operatorId(operatorId)
                .roomId(meeting.getRoomId())
                .meetingTopic(meeting.getMeetingTopic())
                .startTime(meeting.getMeetingStart())
                .endTime(meeting.getMeetingEnd())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 更新会议 meetingId={}", meeting.getMeetingId());
    }

    @Transactional
    public void recordMeetingCancel(Meeting meeting, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(meeting.getMeetingId())
                .actionType("MEETING_CANCEL")
                .actionDetail(String.format("取消会议: %s", meeting.getMeetingTopic()))
                .operatorId(operatorId)
                .roomId(meeting.getRoomId())
                .meetingTopic(meeting.getMeetingTopic())
                .startTime(meeting.getMeetingStart())
                .endTime(meeting.getMeetingEnd())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 取消会议 meetingId={}", meeting.getMeetingId());
    }

    @Transactional
    public void recordMeetingStart(Meeting meeting, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(meeting.getMeetingId())
                .actionType("MEETING_START")
                .actionDetail(String.format("开始会议: %s", meeting.getMeetingTopic()))
                .operatorId(operatorId)
                .roomId(meeting.getRoomId())
                .meetingTopic(meeting.getMeetingTopic())
                .startTime(meeting.getMeetingStart())
                .endTime(meeting.getMeetingEnd())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 开始会议 meetingId={}", meeting.getMeetingId());
    }

    @Transactional
    public void recordMeetingComplete(Meeting meeting, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(meeting.getMeetingId())
                .actionType("MEETING_COMPLETE")
                .actionDetail(String.format("完成会议: %s", meeting.getMeetingTopic()))
                .operatorId(operatorId)
                .roomId(meeting.getRoomId())
                .meetingTopic(meeting.getMeetingTopic())
                .startTime(meeting.getMeetingStart())
                .endTime(meeting.getMeetingEnd())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 完成会议 meetingId={}", meeting.getMeetingId());
    }

    @Transactional
    public void recordAttendeeInvite(String meetingId, List<MeetingCreateRequest.AttendeeInfo> attendees, String operatorId) {
        if (attendees == null || attendees.isEmpty()) {
            return;
        }

        StringBuilder detail = new StringBuilder("邀请参会人员: ");
        for (int i = 0; i < attendees.size(); i++) {
            MeetingCreateRequest.AttendeeInfo info = attendees.get(i);
            if (i > 0) detail.append(", ");
            detail.append(info.getUserName() != null ? info.getUserName() : info.getUserId());
        }

        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(meetingId)
                .actionType("ATTENDEE_INVITE")
                .actionDetail(detail.toString())
                .operatorId(operatorId)
                .build();
        historyRepository.save(history);
        log.info("记录历史: 邀请参会人员 meetingId={}, count={}", meetingId, attendees.size());
    }

    @Transactional
    public void recordAttendeeConfirm(Attendee attendee, String operatorId) {
        String actionType;
        String actionDetail;
        if ("confirmed".equals(attendee.getAttendeeStatus())) {
            actionType = "ATTENDEE_CONFIRM";
            actionDetail = String.format("确认参会: %s", attendee.getUserName() != null ? attendee.getUserName() : attendee.getUserId());
        } else if ("declined".equals(attendee.getAttendeeStatus())) {
            actionType = "ATTENDEE_DECLINE";
            actionDetail = String.format("拒绝参会: %s", attendee.getUserName() != null ? attendee.getUserName() : attendee.getUserId());
        } else {
            actionType = "ATTENDEE_TENTATIVE";
            actionDetail = String.format("暂定参会: %s", attendee.getUserName() != null ? attendee.getUserName() : attendee.getUserId());
        }

        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(attendee.getMeetingId())
                .actionType(actionType)
                .actionDetail(actionDetail)
                .operatorId(operatorId)
                .build();
        historyRepository.save(history);
        log.info("记录历史: 参会确认 attendeeId={}, status={}", attendee.getAttendeeId(), attendee.getAttendeeStatus());
    }

    @Transactional
    public void recordReminderSent(Reminder reminder, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .meetingId(reminder.getMeetingId())
                .actionType("REMINDER_SENT")
                .actionDetail(String.format("发送提醒: %s", reminder.getReminderType()))
                .operatorId(operatorId)
                .build();
        historyRepository.save(history);
        log.info("记录历史: 发送提醒 reminderId={}", reminder.getReminderId());
    }

    @Transactional
    public void recordRoomCreate(MeetingRoom room, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .actionType("ROOM_CREATE")
                .actionDetail(String.format("创建会议室: %s", room.getRoomName()))
                .operatorId(operatorId)
                .roomId(room.getRoomId())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 创建会议室 roomId={}", room.getRoomId());
    }

    @Transactional
    public void recordRoomUpdate(MeetingRoom room, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .actionType("ROOM_UPDATE")
                .actionDetail(String.format("更新会议室: %s", room.getRoomName()))
                .operatorId(operatorId)
                .roomId(room.getRoomId())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 更新会议室 roomId={}", room.getRoomId());
    }

    @Transactional
    public void recordRoomDelete(MeetingRoom room, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .actionType("ROOM_DELETE")
                .actionDetail(String.format("删除会议室: %s", room.getRoomName()))
                .operatorId(operatorId)
                .roomId(room.getRoomId())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 删除会议室 roomId={}", room.getRoomId());
    }

    @Transactional
    public void recordRoomStatusChange(MeetingRoom room, String status, String operatorId) {
        MeetingHistory history = MeetingHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .actionType("ROOM_STATUS_CHANGE")
                .actionDetail(String.format("会议室状态变更: %s -> %s", room.getRoomName(), status))
                .operatorId(operatorId)
                .roomId(room.getRoomId())
                .build();
        historyRepository.save(history);
        log.info("记录历史: 会议室状态变更 roomId={}, status={}", room.getRoomId(), status);
    }
}
