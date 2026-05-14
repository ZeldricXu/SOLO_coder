package com.meeting.service;

import com.meeting.dto.AttendeeConfirmRequest;
import com.meeting.dto.AttendeeConfirmResponse;
import com.meeting.entity.Attendee;
import com.meeting.entity.Meeting;
import com.meeting.exception.MeetingException;
import com.meeting.repository.AttendeeRepository;
import com.meeting.repository.MeetingRepository;
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
public class AttendeeService {

    private final AttendeeRepository attendeeRepository;
    private final MeetingRepository meetingRepository;
    private final StatsService statsService;
    private final HistoryService historyService;

    private static final String ATTENDEE_STATUS_PENDING = "pending";
    private static final String ATTENDEE_STATUS_CONFIRMED = "confirmed";
    private static final String ATTENDEE_STATUS_DECLINED = "declined";
    private static final String ATTENDEE_STATUS_TENTATIVE = "tentative";

    public Attendee getAttendeeById(String attendeeId) {
        return attendeeRepository.findByAttendeeId(attendeeId)
                .orElseThrow(() -> new MeetingException(404, "参会记录不存在: " + attendeeId));
    }

    public List<Attendee> getAttendeesByMeetingId(String meetingId) {
        return attendeeRepository.findByMeetingId(meetingId);
    }

    public List<Attendee> getAttendeesByUserId(String userId) {
        return attendeeRepository.findByUserId(userId);
    }

    public Attendee getAttendeeByMeetingAndUser(String meetingId, String userId) {
        return attendeeRepository.findByMeetingIdAndUserId(meetingId, userId)
                .orElseThrow(() -> new MeetingException(404, "参会记录不存在"));
    }

    @Transactional
    public Attendee createAttendee(Attendee attendee) {
        if (attendee.getAttendeeId() == null || attendee.getAttendeeId().isEmpty()) {
            attendee.setAttendeeId(IdGenerator.generateAttendeeId());
        }
        if (attendee.getAttendeeStatus() == null || attendee.getAttendeeStatus().isEmpty()) {
            attendee.setAttendeeStatus(ATTENDEE_STATUS_PENDING);
        }

        log.info("创建参会记录: attendeeId={}, meetingId={}, userId={}",
                attendee.getAttendeeId(), attendee.getMeetingId(), attendee.getUserId());

        return attendeeRepository.save(attendee);
    }

    @Transactional
    public void inviteAttendees(String meetingId, List<com.meeting.dto.MeetingCreateRequest.AttendeeInfo> attendees) {
        if (attendees == null || attendees.isEmpty()) {
            return;
        }

        for (com.meeting.dto.MeetingCreateRequest.AttendeeInfo info : attendees) {
            Attendee attendee = Attendee.builder()
                    .attendeeId(IdGenerator.generateAttendeeId())
                    .meetingId(meetingId)
                    .userId(info.getUserId())
                    .userName(info.getUserName())
                    .userEmail(info.getUserEmail())
                    .attendeeStatus(ATTENDEE_STATUS_PENDING)
                    .build();

            attendeeRepository.save(attendee);
            log.info("邀请参会人员: meetingId={}, userId={}, userName={}",
                    meetingId, info.getUserId(), info.getUserName());

            sendInvitationNotification(attendee);
        }

        historyService.recordAttendeeInvite(meetingId, attendees, "system");
    }

    private void sendInvitationNotification(Attendee attendee) {
        log.info("发送参会邀请通知: attendeeId={}, userId={}", attendee.getAttendeeId(), attendee.getUserId());
    }

    @Transactional
    public AttendeeConfirmResponse confirmAttendance(AttendeeConfirmRequest request) {
        log.info("处理参会确认: meetingId={}, userId={}, status={}",
                request.getMeetingId(), request.getUserId(), request.getAttendeeStatus());

        Meeting meeting = meetingRepository.findByMeetingId(request.getMeetingId())
                .orElseThrow(() -> new MeetingException(404, "会议不存在: " + request.getMeetingId()));

        if ("cancelled".equals(meeting.getMeetingStatus())) {
            throw new MeetingException(400, "会议已取消，无法确认参会");
        }

        Attendee attendee = attendeeRepository.findByMeetingIdAndUserId(request.getMeetingId(), request.getUserId())
                .orElseThrow(() -> new MeetingException(404, "参会记录不存在"));

        String status = request.getAttendeeStatus();
        if (!ATTENDEE_STATUS_CONFIRMED.equals(status) &&
                !ATTENDEE_STATUS_DECLINED.equals(status) &&
                !ATTENDEE_STATUS_TENTATIVE.equals(status)) {
            throw new MeetingException(400, "无效的参会状态: " + status);
        }

        attendee.setAttendeeStatus(status);
        attendee.setAttendeeTime(LocalDateTime.now());

        if (ATTENDEE_STATUS_DECLINED.equals(status)) {
            attendee.setRejectReason(request.getRejectReason());
        }

        attendeeRepository.save(attendee);

        if (ATTENDEE_STATUS_CONFIRMED.equals(status)) {
            statsService.incrementConfirmedAttendee(meeting.getMeetingStart());
            sendConfirmationNotification(attendee, true);
        } else if (ATTENDEE_STATUS_DECLINED.equals(status)) {
            sendConfirmationNotification(attendee, false);
        }

        historyService.recordAttendeeConfirm(attendee, "system");

        return AttendeeConfirmResponse.builder()
                .attendeeId(attendee.getAttendeeId())
                .meetingId(attendee.getMeetingId())
                .userId(attendee.getUserId())
                .userName(attendee.getUserName())
                .attendeeStatus(attendee.getAttendeeStatus())
                .attendeeTime(attendee.getAttendeeTime())
                .build();
    }

    private void sendConfirmationNotification(Attendee attendee, boolean confirmed) {
        String type = confirmed ? "确认参会" : "拒绝参会";
        log.info("发送{}通知: meetingId={}, userId={}", type, attendee.getMeetingId(), attendee.getUserId());
    }

    public long countAttendees(String meetingId) {
        return attendeeRepository.countByMeetingId(meetingId);
    }

    public long countConfirmedAttendees(String meetingId) {
        return attendeeRepository.countByMeetingIdAndStatus(meetingId, ATTENDEE_STATUS_CONFIRMED);
    }

    public List<Attendee> getConfirmedAttendees(String meetingId) {
        return attendeeRepository.findByMeetingIdAndAttendeeStatus(meetingId, ATTENDEE_STATUS_CONFIRMED);
    }

    public List<Attendee> getPendingAttendees(String meetingId) {
        return attendeeRepository.findByMeetingIdAndAttendeeStatus(meetingId, ATTENDEE_STATUS_PENDING);
    }

    public List<Attendee> getUserMeetings(String userId) {
        return attendeeRepository.findByUserIdAndStatuses(userId,
                List.of(ATTENDEE_STATUS_PENDING, ATTENDEE_STATUS_CONFIRMED, ATTENDEE_STATUS_TENTATIVE));
    }

    @Transactional
    public void removeAttendee(String attendeeId) {
        Attendee attendee = getAttendeeById(attendeeId);
        log.info("移除参会人员: attendeeId={}", attendeeId);
        attendeeRepository.delete(attendee);
    }

    @Transactional
    public void removeAttendeeFromMeeting(String meetingId, String userId) {
        Attendee attendee = attendeeRepository.findByMeetingIdAndUserId(meetingId, userId)
                .orElseThrow(() -> new MeetingException(404, "参会记录不存在"));
        attendeeRepository.delete(attendee);
        log.info("从会议移除参会人员: meetingId={}, userId={}", meetingId, userId);
    }

    public boolean isUserAttending(String meetingId, String userId) {
        return attendeeRepository.existsByMeetingIdAndUserId(meetingId, userId);
    }

    @Transactional
    public boolean inviteSingleAttendee(String meetingId, String userId, String userName, String userEmail) {
        log.info("邀请单个参会人员: meetingId={}, userId={}, userName={}", meetingId, userId, userName);

        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        if (isUserAttending(meetingId, userId)) {
            log.warn("用户已在参会列表中: meetingId={}, userId={}", meetingId, userId);
            return true;
        }

        Attendee attendee = Attendee.builder()
                .attendeeId(IdGenerator.generateAttendeeId())
                .meetingId(meetingId)
                .userId(userId)
                .userName(userName)
                .userEmail(userEmail)
                .attendeeStatus(ATTENDEE_STATUS_PENDING)
                .build();

        attendeeRepository.save(attendee);
        sendInvitationNotification(attendee);

        log.info("单个参会邀请成功: attendeeId={}, meetingId={}, userId={}",
                attendee.getAttendeeId(), meetingId, userId);

        return true;
    }
}
