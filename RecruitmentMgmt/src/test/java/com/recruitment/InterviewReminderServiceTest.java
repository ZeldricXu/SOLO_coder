package com.recruitment;

import com.recruitment.builder.TestDataBuilder;
import com.recruitment.common.enums.InterviewStatus;
import com.recruitment.common.enums.InterviewType;
import com.recruitment.model.Interview;
import com.recruitment.model.Interviewer;
import com.recruitment.repository.InterviewRepository;
import com.recruitment.service.CandidateService;
import com.recruitment.service.InterviewReminderService;
import com.recruitment.service.InterviewerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试提醒服务单元测试")
class InterviewReminderServiceTest {

    @Mock
    private InterviewRepository interviewRepository;

    @Mock
    private CandidateService candidateService;

    @Mock
    private InterviewerService interviewerService;

    @Spy
    @InjectMocks
    private InterviewReminderService interviewReminderService;

    private Interviewer testInterviewer;

    @BeforeEach
    void setUp() {
        testInterviewer = TestDataBuilder.createTestInterviewer();
        interviewReminderService.resetCounters();
    }

    @Nested
    @DisplayName("面试紧急程度计算测试")
    class UrgencyCalculationTests {

        @Test
        @DisplayName("24小时内的面试应为紧急")
        void shouldBeUrgentWhenLessThan24Hours() {
            Instant urgentTime = Instant.now().plusSeconds(3600);

            InterviewReminderService.UrgencyLevel urgency =
                    interviewReminderService.calculateUrgency(urgentTime);

            assertEquals(InterviewReminderService.UrgencyLevel.URGENT, urgency);
        }

        @Test
        @DisplayName("24-72小时的面试应为普通")
        void shouldBeNormalWhen24To72Hours() {
            Instant normalTime = Instant.now().plusSeconds(86400 * 2);

            InterviewReminderService.UrgencyLevel urgency =
                    interviewReminderService.calculateUrgency(normalTime);

            assertEquals(InterviewReminderService.UrgencyLevel.NORMAL, urgency);
        }

        @Test
        @DisplayName("超过72小时的面试应为低优先级")
        void shouldBeLowWhenMoreThan72Hours() {
            Instant lowTime = Instant.now().plusSeconds(86400 * 5);

            InterviewReminderService.UrgencyLevel urgency =
                    interviewReminderService.calculateUrgency(lowTime);

            assertEquals(InterviewReminderService.UrgencyLevel.LOW, urgency);
        }

        @Test
        @DisplayName("临近时间点边界测试")
        void shouldHandleBoundaryCorrectly() {
            Instant justUnder24Hours = Instant.now().plusSeconds(86400 - 1);
            Instant justOver24Hours = Instant.now().plusSeconds(86400 + 1);

            assertEquals(InterviewReminderService.UrgencyLevel.URGENT,
                    interviewReminderService.calculateUrgency(justUnder24Hours));
            assertEquals(InterviewReminderService.UrgencyLevel.NORMAL,
                    interviewReminderService.calculateUrgency(justOver24Hours));
        }
    }

    @Nested
    @DisplayName("面试提醒频率测试")
    class ReminderFrequencyTests {

        @Test
        @DisplayName("紧急面试应发送3次提醒")
        void urgentInterviewsShouldHaveThreeReminders() {
            int frequency = interviewReminderService.getReminderFrequency(
                    InterviewReminderService.UrgencyLevel.URGENT);

            assertEquals(3, frequency);
        }

        @Test
        @DisplayName("普通面试应发送1次提醒")
        void normalInterviewsShouldHaveOneReminder() {
            int frequency = interviewReminderService.getReminderFrequency(
                    InterviewReminderService.UrgencyLevel.NORMAL);

            assertEquals(1, frequency);
        }

        @Test
        @DisplayName("低优先级面试不发送提醒")
        void lowPriorityInterviewsShouldHaveNoReminders() {
            int frequency = interviewReminderService.getReminderFrequency(
                    InterviewReminderService.UrgencyLevel.LOW);

            assertEquals(0, frequency);
        }
    }

    @Nested
    @DisplayName("面试提醒发送测试")
    class ReminderSendingTests {

        @Test
        @DisplayName("面试安排后应触发提醒")
        void shouldTriggerReminderAfterInterviewScheduled() {
            Interview scheduledInterview = TestDataBuilder.createNormalInterview();
            scheduledInterview.setInterviewStatus(InterviewStatus.SCHEDULED);

            when(interviewerService.getInterviewer(scheduledInterview.getInterviewerId()))
                    .thenReturn(testInterviewer);

            doNothing().when(interviewReminderService).sendEmailNotification(anyString());

            interviewReminderService.sendReminderNotification(
                    scheduledInterview,
                    InterviewReminderService.UrgencyLevel.NORMAL,
                    1
            );

            verify(interviewerService).getInterviewer(scheduledInterview.getInterviewerId());
            verify(interviewReminderService).sendEmailNotification(anyString());
        }

        @Test
        @DisplayName("紧急面试应发送多渠道提醒")
        void urgentInterviewsShouldUseMultipleChannels() {
            Interview urgentInterview = TestDataBuilder.createUrgentInterview();
            urgentInterview.setInterviewStatus(InterviewStatus.SCHEDULED);

            when(interviewerService.getInterviewer(urgentInterview.getInterviewerId()))
                    .thenReturn(testInterviewer);

            doNothing().when(interviewReminderService).sendEmailNotification(anyString());
            doNothing().when(interviewReminderService).sendSmsNotification(anyString());
            doNothing().when(interviewReminderService).sendAppPushNotification(anyString());

            interviewReminderService.sendReminderNotification(
                    urgentInterview,
                    InterviewReminderService.UrgencyLevel.URGENT,
                    1
            );

            ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> smsCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> pushCaptor = ArgumentCaptor.forClass(String.class);

            verify(interviewReminderService).sendEmailNotification(emailCaptor.capture());
            verify(interviewReminderService).sendSmsNotification(smsCaptor.capture());
            verify(interviewReminderService).sendAppPushNotification(pushCaptor.capture());

            assertTrue(emailCaptor.getValue().contains("面试提醒"));
            assertTrue(smsCaptor.getValue().contains("面试提醒"));
            assertTrue(pushCaptor.getValue().contains("面试提醒"));
        }

        @Test
        @DisplayName("普通面试只发送邮件提醒")
        void normalInterviewsShouldUseEmailOnly() {
            Interview normalInterview = TestDataBuilder.createNormalInterview();
            normalInterview.setInterviewStatus(InterviewStatus.SCHEDULED);

            when(interviewerService.getInterviewer(normalInterview.getInterviewerId()))
                    .thenReturn(testInterviewer);

            doNothing().when(interviewReminderService).sendEmailNotification(anyString());

            interviewReminderService.sendReminderNotification(
                    normalInterview,
                    InterviewReminderService.UrgencyLevel.NORMAL,
                    1
            );

            verify(interviewReminderService).sendEmailNotification(anyString());
            verify(interviewReminderService, never()).sendSmsNotification(anyString());
            verify(interviewReminderService, never()).sendAppPushNotification(anyString());
        }

        @Test
        @DisplayName("非待面试状态不发送提醒")
        void shouldNotSendReminderForNonScheduledInterview() {
            Interview completedInterview = TestDataBuilder.createTestInterview(InterviewStatus.PASSED);

            when(interviewerService.getInterviewer(completedInterview.getInterviewerId()))
                    .thenReturn(testInterviewer);

            interviewReminderService.sendInterviewReminder(completedInterview);

            assertFalse(interviewReminderService.hasReminderSent(completedInterview.getInterviewId()));
        }

        @Test
        @DisplayName("提醒消息应包含完整信息")
        void reminderMessageShouldContainAllInformation() {
            Interview interview = TestDataBuilder.createTestInterview();
            interview.setInterviewStatus(InterviewStatus.SCHEDULED);
            interview.setInterviewType(InterviewType.TECHNICAL);

            when(interviewerService.getInterviewer(interview.getInterviewerId()))
                    .thenReturn(testInterviewer);

            doNothing().when(interviewReminderService).sendEmailNotification(anyString());

            interviewReminderService.sendReminderNotification(
                    interview,
                    InterviewReminderService.UrgencyLevel.NORMAL,
                    1
            );

            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(interviewReminderService).sendEmailNotification(messageCaptor.capture());

            String message = messageCaptor.getValue();
            assertTrue(message.contains(testInterviewer.getInterviewerName()));
            assertTrue(message.contains("TECHNICAL"));
            assertTrue(message.contains("NORMAL"));
        }
    }

    @Nested
    @DisplayName("批量提醒测试")
    class BatchReminderTests {

        @Test
        @DisplayName("批量处理所有待面试")
        void shouldProcessAllScheduledInterviews() {
            Interview interview1 = TestDataBuilder.createTestInterview(InterviewStatus.SCHEDULED);
            Interview interview2 = TestDataBuilder.createTestInterview(InterviewStatus.SCHEDULED);
            Interview passedInterview = TestDataBuilder.createTestInterview(InterviewStatus.PASSED);

            List<Interview> scheduledInterviews = new ArrayList<>();
            scheduledInterviews.add(interview1);
            scheduledInterviews.add(interview2);

            when(interviewRepository.findByInterviewStatus(InterviewStatus.SCHEDULED))
                    .thenReturn(scheduledInterviews);
            when(interviewerService.getInterviewer(anyString()))
                    .thenReturn(testInterviewer);
            doNothing().when(interviewReminderService).sendEmailNotification(anyString());

            interviewReminderService.sendRemindersForAllScheduled();

            verify(interviewRepository).findByInterviewStatus(InterviewStatus.SCHEDULED);
            assertTrue(interviewReminderService.hasReminderSent(interview1.getInterviewId()));
            assertTrue(interviewReminderService.hasReminderSent(interview2.getInterviewId()));
            assertFalse(interviewReminderService.hasReminderSent(passedInterview.getInterviewId()));
        }
    }

    @Nested
    @DisplayName("提醒记录和计数测试")
    class ReminderTrackingTests {

        @Test
        @DisplayName("应正确记录已发送的提醒")
        void shouldTrackSentReminders() {
            Interview interview = TestDataBuilder.createNormalInterview();
            interview.setInterviewStatus(InterviewStatus.SCHEDULED);

            when(interviewerService.getInterviewer(interview.getInterviewerId()))
                    .thenReturn(testInterviewer);

            doNothing().when(interviewReminderService).sendEmailNotification(anyString());

            assertFalse(interviewReminderService.hasReminderSent(interview.getInterviewId()));

            interviewReminderService.sendInterviewReminder(interview);

            assertTrue(interviewReminderService.hasReminderSent(interview.getInterviewId()));
            assertEquals(1, interviewReminderService.getSentReminderCount(interview.getInterviewId()));
        }

        @Test
        @DisplayName("紧急和普通提醒计数应分开")
        void shouldSeparateUrgentAndNormalCounters() {
            Interview urgentInterview = TestDataBuilder.createUrgentInterview();
            urgentInterview.setInterviewStatus(InterviewStatus.SCHEDULED);
            Interview normalInterview = TestDataBuilder.createNormalInterview();
            normalInterview.setInterviewStatus(InterviewStatus.SCHEDULED);

            when(interviewerService.getInterviewer(anyString()))
                    .thenReturn(testInterviewer);

            doNothing().when(interviewReminderService).sendEmailNotification(anyString());
            doNothing().when(interviewReminderService).sendSmsNotification(anyString());
            doNothing().when(interviewReminderService).sendAppPushNotification(anyString());

            interviewReminderService.sendInterviewReminder(urgentInterview);
            interviewReminderService.sendInterviewReminder(normalInterview);

            assertEquals(1, interviewReminderService.getTotalUrgentReminders());
            assertEquals(1, interviewReminderService.getTotalNormalReminders());
        }

        @Test
        @DisplayName("重置计数器后计数应为零")
        void shouldResetCounters() {
            when(interviewerService.getInterviewer(anyString()))
                    .thenReturn(testInterviewer);
            doNothing().when(interviewReminderService).sendEmailNotification(anyString());

            Interview interview = TestDataBuilder.createNormalInterview();
            interview.setInterviewStatus(InterviewStatus.SCHEDULED);

            interviewReminderService.sendInterviewReminder(interview);
            assertEquals(1, interviewReminderService.getTotalNormalReminders());

            interviewReminderService.resetCounters();

            assertEquals(0, interviewReminderService.getTotalUrgentReminders());
            assertEquals(0, interviewReminderService.getTotalNormalReminders());
        }
    }
}
