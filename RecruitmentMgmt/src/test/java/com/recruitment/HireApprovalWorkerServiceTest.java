package com.recruitment;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.builder.TestDataBuilder;
import com.recruitment.common.enums.*;
import com.recruitment.dto.HireApproveRequest;
import com.recruitment.dto.HireApproveResponse;
import com.recruitment.history.HistoryService;
import com.recruitment.model.Candidate;
import com.recruitment.model.Hire;
import com.recruitment.model.Resume;
import com.recruitment.repository.HireRepository;
import com.recruitment.service.CandidateService;
import com.recruitment.service.HireApprovalWorkerService;
import com.recruitment.service.ResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("录用审批异步Worker单元测试")
class HireApprovalWorkerServiceTest {

    @Mock
    private HireRepository hireRepository;

    @Mock
    private ResumeService resumeService;

    @Mock
    private CandidateService candidateService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private HireApprovalWorkerService hireApprovalWorkerService;

    private Resume testResume;
    private Candidate testCandidate;
    private Hire testHire;

    @BeforeEach
    void setUp() {
        testResume = TestDataBuilder.createTestResume(ResumeStatus.INTERVIEW_PASSED);
        testCandidate = TestDataBuilder.createTestCandidate();
        testHire = TestDataBuilder.createTestHire(testResume.getResumeId(), testCandidate.getCandidateId());
        testHire.setHireStatus(HireStatus.PENDING_APPROVAL);
        hireApprovalWorkerService.resetCounters();
    }

    @Nested
    @DisplayName("异步审批发起测试")
    class AsyncInitiationTests {

        @Test
        @DisplayName("发起异步审批后立即返回响应")
        void shouldReturnResponseImmediately() {
            String resumeId = testResume.getResumeId();

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);
            HireApproveResponse response = hireApprovalWorkerService.initiateAsyncApproval(request);

            assertNotNull(response);
            assertEquals(testHire.getHireId(), response.getHireId());
            assertEquals("PENDING_ASYNC_PROCESSING", response.getStatus());
            assertEquals(testCandidate.getCandidateName(), response.getCandidateName());
        }
    }

    @Nested
    @DisplayName("重试机制测试")
    class RetryMechanismTests {

        @Test
        @DisplayName("审批成功时不重试")
        void shouldNotRetryWhenSuccessful() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);
            boolean success = hireApprovalWorkerService.processWithRetry(request);

            assertTrue(success);
            assertEquals(0, hireApprovalWorkerService.getRetryCount(testHire.getHireId()));
            assertEquals(1, hireApprovalWorkerService.getSuccessCount());
            assertEquals(0, hireApprovalWorkerService.getTotalRetryCount());
        }

        @Test
        @DisplayName("审批失败时应重试")
        void shouldRetryWhenFailing() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);

            when(hireRepository.save(any(Hire.class)))
                    .thenThrow(new RuntimeException("数据库连接失败"))
                    .thenThrow(new RuntimeException("数据库连接失败"))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);
            boolean success = hireApprovalWorkerService.processWithRetry(request);

            assertTrue(success);
            assertEquals(2, hireApprovalWorkerService.getRetryCount(testHire.getHireId()));
            assertEquals(1, hireApprovalWorkerService.getSuccessCount());
            assertEquals(2, hireApprovalWorkerService.getTotalRetryCount());
        }

        @Test
        @DisplayName("达到最大重试次数后失败")
        void shouldFailAfterMaxRetries() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class)))
                    .thenThrow(new RuntimeException("数据库连接失败"));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);
            boolean success = hireApprovalWorkerService.processWithRetry(request);

            assertFalse(success);
            assertTrue(hireApprovalWorkerService.hasMaxRetryReached(testHire.getHireId()));
            assertEquals(HireApprovalWorkerService.MAX_RETRY_TIMES,
                    hireApprovalWorkerService.getRetryCount(testHire.getHireId()));
            assertEquals(1, hireApprovalWorkerService.getFailCount());
        }
    }

    @Nested
    @DisplayName("审批状态更新测试")
    class ApprovalStateTests {

        @Test
        @DisplayName("审批通过后更新状态")
        void shouldUpdateStatusWhenApproved() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);
            hireApprovalWorkerService.processWithRetry(request);

            ArgumentCaptor<Hire> hireCaptor = ArgumentCaptor.forClass(Hire.class);
            verify(hireRepository).save(hireCaptor.capture());

            assertEquals(HireStatus.APPROVED, hireCaptor.getValue().getHireStatus());
            assertNotNull(hireCaptor.getValue().getApprovedAt());
            assertEquals("25K", hireCaptor.getValue().getHireSalary());

            verify(resumeService).updateResumeStatus(resumeId, ResumeStatus.HIRED);
            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.HIRED);
            verify(analysisService).incrementHireCount();
        }

        @Test
        @DisplayName("审批不通过后更新状态")
        void shouldUpdateStatusWhenRejected() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, false);
            hireApprovalWorkerService.processWithRetry(request);

            ArgumentCaptor<Hire> hireCaptor = ArgumentCaptor.forClass(Hire.class);
            verify(hireRepository).save(hireCaptor.capture());

            assertEquals(HireStatus.REJECTED, hireCaptor.getValue().getHireStatus());

            verify(resumeService).updateResumeStatus(resumeId, ResumeStatus.REJECTED);
            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.REJECTED);
            verify(analysisService).incrementRejectCount();
        }

        @Test
        @DisplayName("非待审批状态应抛出异常")
        void shouldThrowWhenNotPending() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.APPROVED);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);
            boolean success = hireApprovalWorkerService.processWithRetry(request);

            assertFalse(success);
            assertTrue(hireApprovalWorkerService.hasMaxRetryReached(testHire.getHireId()));
        }
    }

    @Nested
    @DisplayName("计数器测试")
    class CounterTests {

        @Test
        @DisplayName("成功计数正确")
        void shouldCountSuccesses() {
            Hire hire1 = TestDataBuilder.createTestHire("resume_001", "candidate_001");
            hire1.setHireStatus(HireStatus.PENDING_APPROVAL);
            Hire hire2 = TestDataBuilder.createTestHire("resume_002", "candidate_002");
            hire2.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId("resume_001")).thenReturn(Optional.of(hire1));
            when(hireRepository.findByResumeId("resume_002")).thenReturn(Optional.of(hire2));
            when(resumeService.getResume(anyString())).thenReturn(testResume);
            when(candidateService.getCandidate(anyString())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            hireApprovalWorkerService.processWithRetry(
                    TestDataBuilder.createHireApproveRequest("resume_001", true));
            hireApprovalWorkerService.processWithRetry(
                    TestDataBuilder.createHireApproveRequest("resume_002", true));

            assertEquals(2, hireApprovalWorkerService.getTotalApprovalCount());
            assertEquals(2, hireApprovalWorkerService.getSuccessCount());
            assertEquals(0, hireApprovalWorkerService.getFailCount());
        }

        @Test
        @DisplayName("重置计数器后应为零")
        void shouldResetCounters() {
            Hire hire = TestDataBuilder.createTestHire("resume_001", "candidate_001");
            hire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId("resume_001")).thenReturn(Optional.of(hire));
            when(resumeService.getResume("resume_001")).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            hireApprovalWorkerService.processWithRetry(
                    TestDataBuilder.createHireApproveRequest("resume_001", true));

            assertEquals(1, hireApprovalWorkerService.getTotalApprovalCount());
            assertEquals(1, hireApprovalWorkerService.getSuccessCount());

            hireApprovalWorkerService.resetCounters();

            assertEquals(0, hireApprovalWorkerService.getTotalApprovalCount());
            assertEquals(0, hireApprovalWorkerService.getSuccessCount());
            assertEquals(0, hireApprovalWorkerService.getFailCount());
            assertEquals(0, hireApprovalWorkerService.getTotalRetryCount());
        }
    }
}
