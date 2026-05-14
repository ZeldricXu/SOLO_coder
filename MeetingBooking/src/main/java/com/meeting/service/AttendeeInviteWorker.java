package com.meeting.service;

import com.meeting.dto.MeetingCreateRequest;
import com.meeting.entity.Attendee;
import com.meeting.repository.AttendeeRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendeeInviteWorker {

    private final AttendeeRepository attendeeRepository;
    private final HistoryService historyService;

    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public static class InviteResult {
        private final int totalCount;
        private final int successCount;
        private final int failedCount;
        private final List<String> failedUsers;

        public InviteResult(int totalCount, int successCount, int failedCount, List<String> failedUsers) {
            this.totalCount = totalCount;
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.failedCount = failedCount;
            this.failedUsers = failedUsers;
        }

        public int getTotalCount() { return totalCount; }
        public int getSuccessCount() { return successCount; }
        public int getFailedCount() { return failedCount; }
        public List<String> getFailedUsers() { return failedUsers; }
        public boolean isAllSuccess() { return failedCount == 0; }
    }

    @Async
    public CompletableFuture<InviteResult> processInvitesAsync(
            String meetingId,
            List<MeetingCreateRequest.AttendeeInfo> attendees,
            String operatorId) {

        log.info("异步处理参会邀请: meetingId={}, attendeeCount={}", meetingId, attendees.size());

        if (attendees == null || attendees.isEmpty()) {
            return CompletableFuture.completedFuture(
                new InviteResult(0, 0, 0, new ArrayList<>()));
        }

        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failedUsers = new ArrayList<>();

        for (MeetingCreateRequest.AttendeeInfo info : attendees) {
            boolean success = processSingleInviteWithRetry(meetingId, info);
            if (success) {
                successCount.incrementAndGet();
            } else {
                failedUsers.add(info.getUserId());
            }
        }

        historyService.recordAttendeeInvite(meetingId, attendees, operatorId);

        InviteResult result = new InviteResult(
                attendees.size(),
                successCount.get(),
                attendees.size() - successCount.get(),
                failedUsers);

        log.info("参会邀请处理完成: meetingId={}, total={}, success={}, failed={}",
                meetingId, result.getTotalCount(), result.getSuccessCount(), result.getFailedCount());

        return CompletableFuture.completedFuture(result);
    }

    private boolean processSingleInviteWithRetry(String meetingId, MeetingCreateRequest.AttendeeInfo info) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                return processSingleInvite(meetingId, info);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                log.warn("参会邀请失败，准备重试: meetingId={}, userId={}, retryCount={}/{}",
                        meetingId, info.getUserId(), retryCount, MAX_RETRY_COUNT);

                if (retryCount < MAX_RETRY_COUNT) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("重试等待被中断");
                        break;
                    }
                }
            }
        }

        log.error("参会邀请最终失败: meetingId={}, userId={}, reason={}",
                meetingId, info.getUserId(), lastException != null ? lastException.getMessage() : "未知错误");
        return false;
    }

    private boolean processSingleInvite(String meetingId, MeetingCreateRequest.AttendeeInfo info) {
        if (info.getUserId() == null || info.getUserId().isEmpty()) {
            throw new RuntimeException("用户ID不能为空");
        }

        Attendee attendee = Attendee.builder()
                .attendeeId(IdGenerator.generateAttendeeId())
                .meetingId(meetingId)
                .userId(info.getUserId())
                .userName(info.getUserName())
                .userEmail(info.getUserEmail())
                .attendeeStatus("pending")
                .build();

        attendeeRepository.save(attendee);

        sendInvitationNotification(meetingId, info);

        log.info("参会邀请发送成功: meetingId={}, userId={}, userName={}",
                meetingId, info.getUserId(), info.getUserName());

        return true;
    }

    private void sendInvitationNotification(String meetingId, MeetingCreateRequest.AttendeeInfo info) {
        log.info("发送邀请通知: meetingId={}, userId={}, email={}",
                meetingId, info.getUserId(), info.getUserEmail());
    }

    public InviteResult processInvitesSync(
            String meetingId,
            List<MeetingCreateRequest.AttendeeInfo> attendees,
            String operatorId) {

        try {
            return processInvitesAsync(meetingId, attendees, operatorId).get();
        } catch (Exception e) {
            log.error("同步处理参会邀请失败: meetingId={}", meetingId, e);
            return new InviteResult(
                    attendees != null ? attendees.size() : 0,
                    0,
                    attendees != null ? attendees.size() : 0,
                    new ArrayList<>());
        }
    }
}
