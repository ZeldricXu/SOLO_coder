package com.meeting.service;

import com.meeting.builder.TestDataBuilder;
import com.meeting.dto.MeetingCreateRequest;
import com.meeting.entity.Attendee;
import com.meeting.repository.AttendeeRepository;
import com.meeting.service.AttendeeInviteWorker.InviteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("参会邀请Worker单元测试")
class AttendeeInviteWorkerTest {

    @Mock
    private AttendeeRepository attendeeRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private AttendeeInviteWorker attendeeInviteWorker;

    private String testMeetingId;
    private String testOperatorId;

    @BeforeEach
    void setUp() {
        testMeetingId = "meeting_invite_001";
        testOperatorId = "user_001";
    }

    @Test
    @DisplayName("同步处理参会邀请 - 应成功处理所有参会人员")
    void processInvitesSync_ShouldProcessAllAttendees_Successfully() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_003", "李四", "lisi@example.com"));

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> {
            Attendee att = invocation.getArgument(0);
            return att;
        });

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        assertTrue(result.isAllSuccess());
        verify(historyService, times(1)).recordAttendeeInvite(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("异步处理参会邀请 - 应立即返回结果不阻塞")
    void processInvitesAsync_ShouldReturnCompletableFuture() throws Exception {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        long startTime = System.currentTimeMillis();
        CompletableFuture<InviteResult> future = attendeeInviteWorker.processInvitesAsync(
                testMeetingId, attendees, testOperatorId);

        assertNotNull(future);
        InviteResult result = future.get();

        assertTrue(System.currentTimeMillis() - startTime < 5000);
        assertEquals(1, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
    }

    @Test
    @DisplayName("空参会人员列表 - 应返回空结果")
    void processInvitesSync_ShouldReturnEmptyResult_WhenNoAttendees() {
        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, Collections.emptyList(), testOperatorId);

        assertEquals(0, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
        assertTrue(result.isAllSuccess());
    }

    @Test
    @DisplayName("null参会人员列表 - 应返回空结果")
    void processInvitesSync_ShouldReturnEmptyResult_WhenAttendeesNull() {
        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, null, testOperatorId);

        assertEquals(0, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
    }

    @Test
    @DisplayName("无效用户ID - 应标记为失败")
    void processInvitesSync_ShouldFail_WhenUserIdIsNullOrEmpty() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo(null, "无效用户", "invalid@example.com"));
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "有效用户", "valid@example.com"));

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(2, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailedCount());
        assertFalse(result.isAllSuccess());
        assertTrue(result.getFailedUsers().contains(null));
    }

    @Test
    @DisplayName("重试机制 - 首次失败后重试应成功")
    void processInvitesSync_ShouldRetryOnFailure() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));

        when(attendeeRepository.save(any(Attendee.class)))
                .thenThrow(new RuntimeException("网络异常"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(1, result.getSuccessCount());
        assertTrue(result.isAllSuccess());
        verify(attendeeRepository, atLeast(2)).save(any(Attendee.class));
    }

    @Test
    @DisplayName("多次重试失败 - 最终应标记为失败")
    void processInvitesSync_ShouldFailAfterMaxRetries() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));

        when(attendeeRepository.save(any(Attendee.class)))
                .thenThrow(new RuntimeException("网络异常"))
                .thenThrow(new RuntimeException("网络异常"))
                .thenThrow(new RuntimeException("网络异常"))
                .thenThrow(new RuntimeException("网络异常"));

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(1, result.getFailedCount());
        assertFalse(result.isAllSuccess());
        verify(attendeeRepository, times(3)).save(any(Attendee.class));
    }

    @Test
    @DisplayName("混合成功和失败 - 应正确统计成功和失败数量")
    void processInvitesSync_ShouldCorrectlyTrackSuccessAndFailures() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_001", "成功1", "success1@example.com"));
        attendees.add(TestDataBuilder.buildAttendeeInfo(null, "失败1", "fail1@example.com"));
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_003", "成功2", "success2@example.com"));
        attendees.add(TestDataBuilder.buildAttendeeInfo("", "失败2", "fail2@example.com"));

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(4, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals(2, result.getFailedCount());
        assertFalse(result.isAllSuccess());
    }

    @Test
    @DisplayName("大量参会人员 - 应高效处理")
    void processInvitesSync_ShouldHandleLargeBatch() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            attendees.add(TestDataBuilder.buildAttendeeInfo(
                    "user_" + String.format("%03d", i),
                    "用户" + i,
                    "user" + i + "@example.com"));
        }

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        long startTime = System.currentTimeMillis();
        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);
        long duration = System.currentTimeMillis() - startTime;

        assertEquals(50, result.getTotalCount());
        assertEquals(50, result.getSuccessCount());
        assertTrue(duration < 10000);
    }

    @Test
    @DisplayName("重要参会人员标记 - 应正确处理重要参会人员")
    void processInvitesSync_ShouldHandleImportantAttendees() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = TestDataBuilder.buildMixedAttendees();

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(5, result.getTotalCount());
        assertEquals(5, result.getSuccessCount());
        assertTrue(result.isAllSuccess());
    }

    @Test
    @DisplayName("邀请结果isAllSuccess - 全部成功应返回true")
    void isAllSuccess_ShouldReturnTrue_WhenAllSuccess() {
        InviteResult allSuccess = new InviteResult(3, 3, 0, Collections.emptyList());
        assertTrue(allSuccess.isAllSuccess());
    }

    @Test
    @DisplayName("邀请结果isAllSuccess - 有失败应返回false")
    void isAllSuccess_ShouldReturnFalse_WhenHasFailures() {
        InviteResult withFailure = new InviteResult(3, 2, 1, Collections.singletonList("user_fail"));
        assertFalse(withFailure.isAllSuccess());
    }

    @Test
    @DisplayName("邀请结果字段验证 - 应正确返回各字段值")
    void inviteResult_ShouldReturnCorrectFieldValues() {
        List<String> failedUsers = new ArrayList<>();
        failedUsers.add("user_fail_1");
        failedUsers.add("user_fail_2");

        InviteResult result = new InviteResult(5, 3, 2, failedUsers);

        assertEquals(5, result.getTotalCount());
        assertEquals(3, result.getSuccessCount());
        assertEquals(2, result.getFailedCount());
        assertEquals(failedUsers, result.getFailedUsers());
    }

    @Test
    @DisplayName("记录历史 - 应在邀请处理完成后记录历史")
    void processInvitesSync_ShouldRecordHistory() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        verify(historyService, times(1)).recordAttendeeInvite(
                eq(testMeetingId),
                eq(attendees),
                eq(testOperatorId));
    }

    @Test
    @DisplayName("单次邀请 - 应创建参会记录并发送通知")
    void processInvitesSync_ShouldCreateAttendeeRecord_ForSingleUser() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));

        when(attendeeRepository.save(any(Attendee.class))).thenAnswer(invocation -> {
            Attendee att = invocation.getArgument(0);
            assertEquals("user_002", att.getUserId());
            assertEquals("张三", att.getUserName());
            assertEquals("pending", att.getAttendeeStatus());
            return att;
        });

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(1, result.getSuccessCount());
        verify(attendeeRepository, times(1)).save(any(Attendee.class));
    }

    @Test
    @DisplayName("异常处理 - 存储库异常应触发重试机制")
    void processInvitesSync_ShouldRetry_OnRepositoryException() {
        List<MeetingCreateRequest.AttendeeInfo> attendees = new ArrayList<>();
        attendees.add(TestDataBuilder.buildAttendeeInfo("user_002", "张三", "zhangsan@example.com"));

        when(attendeeRepository.save(any(Attendee.class)))
                .thenThrow(new RuntimeException("数据库连接失败"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InviteResult result = attendeeInviteWorker.processInvitesSync(testMeetingId, attendees, testOperatorId);

        assertEquals(1, result.getSuccessCount());
        verify(attendeeRepository, times(2)).save(any(Attendee.class));
    }
}
