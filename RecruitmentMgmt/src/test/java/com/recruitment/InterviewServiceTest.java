package com.recruitment;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.builder.TestDataBuilder;
import com.recruitment.common.enums.*;
import com.recruitment.dto.InterviewArrangeRequest;
import com.recruitment.dto.InterviewArrangeResponse;
import com.recruitment.dto.InterviewExecuteRequest;
import com.recruitment.history.HistoryService;
import com.recruitment.model.*;
import com.recruitment.repository.InterviewRepository;
import com.recruitment.service.*;
import com.recruitment.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试模块单元测试")
class InterviewServiceTest {

    @Mock
    private InterviewRepository interviewRepository;

    @Mock
    private ResumeService resumeService;

    @Mock
    private PositionService positionService;

    @Mock
    private CandidateService candidateService;

    @Mock
    private InterviewerService interviewerService;

    @Mock
    private HireService hireService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private InterviewService interviewService;

    private Position testPosition;
    private Candidate testCandidate;
    private Resume testResume;
    private Interviewer testInterviewer;
    private Interview testInterview;

    @BeforeEach
    void setUp() {
        testPosition = TestDataBuilder.createTestPosition();
        testCandidate = TestDataBuilder.createTestCandidate();
        testResume = TestDataBuilder.createScreenedResume(testPosition.getPositionId(), testCandidate.getCandidateId());
        testInterviewer = TestDataBuilder.createTestInterviewer();
        testInterview = TestDataBuilder.createTestInterview(testResume.getResumeId(), testInterviewer.getInterviewerId());
    }

    @Nested
    @DisplayName("面试安排测试")
    class InterviewArrangeTests {

        @Test
        @DisplayName("简历通过筛选后可安排面试")
        void shouldArrangeInterviewWhenResumeScreened() {
            String resumeId = testResume.getResumeId();
            String interviewerId = testInterviewer.getInterviewerId();
            testResume.setResumeStatus(ResumeStatus.SCREENED);

            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            when(interviewerService.getInterviewer(interviewerId)).thenReturn(testInterviewer);

            List<InterviewType> stages = new ArrayList<>();
            stages.add(InterviewType.TECHNICAL);
            when(workflowService.getInterviewStages(testPosition.getPositionType())).thenReturn(stages);
            when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> invocation.getArgument(0));

            InterviewArrangeRequest request = TestDataBuilder.createInterviewArrangeRequest(resumeId, interviewerId);
            InterviewArrangeResponse response = interviewService.arrangeInterview(request);

            assertNotNull(response.getInterviewId());
            assertEquals(InterviewStatus.SCHEDULED.name(), response.getStatus());
            assertEquals(testInterviewer.getInterviewerName(), response.getInterviewerName());

            ArgumentCaptor<Interview> interviewCaptor = ArgumentCaptor.forClass(Interview.class);
            verify(interviewRepository).save(interviewCaptor.capture());
            assertEquals(InterviewStatus.SCHEDULED, interviewCaptor.getValue().getInterviewStatus());

            verify(resumeService).updateResumeStatus(resumeId, ResumeStatus.IN_INTERVIEW);
            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.INTERVIEWING);
        }

        @Test
        @DisplayName("简历未通过筛选不能安排面试")
        void shouldRejectWhenResumeNotScreened() {
            String resumeId = testResume.getResumeId();
            testResume.setResumeStatus(ResumeStatus.PENDING_SCREEN);

            when(resumeService.getResume(resumeId)).thenReturn(testResume);

            InterviewArrangeRequest request = TestDataBuilder.createInterviewArrangeRequest(
                    resumeId, testInterviewer.getInterviewerId());

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> interviewService.arrangeInterview(request));

            assertEquals("简历未通过筛选，无法安排面试", exception.getMessage());
            verify(interviewRepository, never()).save(any(Interview.class));
        }

        @Test
        @DisplayName("面试官不可用时拒绝安排")
        void shouldRejectWhenInterviewerUnavailable() {
            String resumeId = testResume.getResumeId();
            String interviewerId = testInterviewer.getInterviewerId();
            testResume.setResumeStatus(ResumeStatus.SCREENED);

            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            doThrow(new RuntimeException("面试官不可用"))
                    .when(interviewerService).validateInterviewerForAssignment(interviewerId);

            InterviewArrangeRequest request = TestDataBuilder.createInterviewArrangeRequest(resumeId, interviewerId);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> interviewService.arrangeInterview(request));

            assertEquals("面试官不可用", exception.getMessage());
        }

        @Test
        @DisplayName("面试官忙碌时拒绝安排")
        void shouldRejectWhenInterviewerBusy() {
            String resumeId = testResume.getResumeId();
            String interviewerId = testInterviewer.getInterviewerId();
            testResume.setResumeStatus(ResumeStatus.SCREENED);

            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            doThrow(new RuntimeException("面试官当前忙碌"))
                    .when(interviewerService).validateInterviewerForAssignment(interviewerId);

            InterviewArrangeRequest request = TestDataBuilder.createInterviewArrangeRequest(resumeId, interviewerId);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> interviewService.arrangeInterview(request));

            assertEquals("面试官当前忙碌", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("面试状态流转测试")
    class InterviewStatusFlowTests {

        @Test
        @DisplayName("面试通过后状态应为已通过")
        void shouldHavePassedStatusAfterPassedInterview() {
            String interviewId = testInterview.getInterviewId();
            testInterview.setInterviewStatus(InterviewStatus.SCHEDULED);

            when(interviewRepository.findByInterviewId(interviewId)).thenReturn(Optional.of(testInterview));
            when(resumeService.getResume(testResume.getResumeId())).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> invocation.getArgument(0));

            InterviewExecuteRequest request = TestDataBuilder.createInterviewExecuteRequest(interviewId, true);
            Interview result = interviewService.executeInterview(request);

            assertEquals(InterviewStatus.PASSED, result.getInterviewStatus());
            assertEquals(85, result.getInterviewScore());
            assertEquals("表现优秀", result.getInterviewResult());

            verify(resumeService).updateResumeStatus(testResume.getResumeId(), ResumeStatus.INTERVIEW_PASSED);
            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.IN_HIRE);
            verify(hireService).initiateHireProcess(
                    testResume.getResumeId(),
                    testCandidate.getCandidateId(),
                    testPosition.getPositionId()
            );
        }

        @Test
        @DisplayName("面试不通过后状态应为已淘汰")
        void shouldHaveRejectedStatusAfterFailedInterview() {
            String interviewId = testInterview.getInterviewId();
            testInterview.setInterviewStatus(InterviewStatus.SCHEDULED);

            when(interviewRepository.findByInterviewId(interviewId)).thenReturn(Optional.of(testInterview));
            when(resumeService.getResume(testResume.getResumeId())).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> invocation.getArgument(0));

            InterviewExecuteRequest request = TestDataBuilder.createInterviewExecuteRequest(interviewId, false);
            Interview result = interviewService.executeInterview(request);

            assertEquals(InterviewStatus.REJECTED, result.getInterviewStatus());
            assertEquals(50, result.getInterviewScore());
            assertEquals("技术基础薄弱", result.getRejectReason());

            verify(resumeService).updateResumeStatus(testResume.getResumeId(), ResumeStatus.INTERVIEW_REJECTED);
            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.REJECTED);
            verify(analysisService).incrementRejectCount();
        }

        @Test
        @DisplayName("面试已完成后不能重复执行")
        void shouldRejectWhenInterviewAlreadyCompleted() {
            String interviewId = testInterview.getInterviewId();
            testInterview.setInterviewStatus(InterviewStatus.PASSED);

            when(interviewRepository.findByInterviewId(interviewId)).thenReturn(Optional.of(testInterview));

            InterviewExecuteRequest request = TestDataBuilder.createInterviewExecuteRequest(interviewId, true);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> interviewService.executeInterview(request));

            assertEquals("面试已完成", exception.getMessage());
            verify(interviewRepository, never()).save(any(Interview.class));
        }

        @Test
        @DisplayName("面试状态不是待面试时不能执行")
        void shouldRejectWhenInterviewNotScheduled() {
            String interviewId = testInterview.getInterviewId();
            testInterview.setInterviewStatus(InterviewStatus.CANCELLED);

            when(interviewRepository.findByInterviewId(interviewId)).thenReturn(Optional.of(testInterview));

            InterviewExecuteRequest request = TestDataBuilder.createInterviewExecuteRequest(interviewId, true);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> interviewService.executeInterview(request));

            assertTrue(exception.getMessage().contains("面试状态不允许执行"));
        }
    }

    @Nested
    @DisplayName("面试官可用状态校验测试")
    class InterviewerValidationTests {

        @Test
        @DisplayName("可用面试官可以安排面试")
        void shouldAllowAvailableInterviewer() {
            String resumeId = testResume.getResumeId();
            String interviewerId = testInterviewer.getInterviewerId();
            testResume.setResumeStatus(ResumeStatus.SCREENED);
            testInterviewer.setInterviewerStatus(InterviewerStatus.AVAILABLE);

            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            when(interviewerService.getInterviewer(interviewerId)).thenReturn(testInterviewer);

            List<InterviewType> stages = new ArrayList<>();
            stages.add(InterviewType.TECHNICAL);
            when(workflowService.getInterviewStages(testPosition.getPositionType())).thenReturn(stages);
            when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> invocation.getArgument(0));

            InterviewArrangeRequest request = TestDataBuilder.createInterviewArrangeRequest(resumeId, interviewerId);
            InterviewArrangeResponse response = interviewService.arrangeInterview(request);

            assertNotNull(response.getInterviewId());
            verify(interviewerService).incrementInterviewCount(interviewerId);
        }

        @Test
        @DisplayName("不可用面试官拒绝安排")
        void shouldRejectUnavailableInterviewer() {
            String resumeId = testResume.getResumeId();
            String interviewerId = testInterviewer.getInterviewerId();
            testResume.setResumeStatus(ResumeStatus.SCREENED);

            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            doThrow(new RuntimeException("面试官不可用"))
                    .when(interviewerService).validateInterviewerForAssignment(interviewerId);

            InterviewArrangeRequest request = TestDataBuilder.createInterviewArrangeRequest(resumeId, interviewerId);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> interviewService.arrangeInterview(request));

            assertEquals("面试官不可用", exception.getMessage());
        }

        @Test
        @DisplayName("忙碌面试官拒绝安排")
        void shouldRejectBusyInterviewer() {
            String resumeId = testResume.getResumeId();
            String interviewerId = testInterviewer.getInterviewerId();
            testResume.setResumeStatus(ResumeStatus.SCREENED);

            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            doThrow(new RuntimeException("面试官当前忙碌"))
                    .when(interviewerService).validateInterviewerForAssignment(interviewerId);

            InterviewArrangeRequest request = TestDataBuilder.createInterviewArrangeRequest(resumeId, interviewerId);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> interviewService.arrangeInterview(request));

            assertEquals("面试官当前忙碌", exception.getMessage());
        }
    }

    @Test
    @DisplayName("获取不存在的面试应抛出异常")
    void shouldThrowWhenInterviewNotFound() {
        when(interviewRepository.findByInterviewId("nonexistent")).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> interviewService.getInterview("nonexistent"));

        assertEquals("面试不存在: nonexistent", exception.getMessage());
    }

    @Test
    @DisplayName("面试完成后更新面试官完成计数")
    void shouldIncrementInterviewerCompletedCount() {
        String interviewId = testInterview.getInterviewId();
        testInterview.setInterviewStatus(InterviewStatus.SCHEDULED);

        when(interviewRepository.findByInterviewId(interviewId)).thenReturn(Optional.of(testInterview));
        when(resumeService.getResume(testResume.getResumeId())).thenReturn(testResume);
        when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InterviewExecuteRequest request = TestDataBuilder.createInterviewExecuteRequest(interviewId, true);
        interviewService.executeInterview(request);

        verify(interviewerService).incrementCompletedCount(testInterviewer.getInterviewerId());
        verify(analysisService).incrementInterviewCount();
    }
}
