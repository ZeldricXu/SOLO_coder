package com.recruitment;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.builder.TestDataBuilder;
import com.recruitment.common.enums.*;
import com.recruitment.dto.ResumeScreenRequest;
import com.recruitment.dto.ResumeSubmitRequest;
import com.recruitment.dto.ResumeSubmitResponse;
import com.recruitment.history.HistoryService;
import com.recruitment.model.Candidate;
import com.recruitment.model.Position;
import com.recruitment.model.Resume;
import com.recruitment.repository.ResumeRepository;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历模块单元测试")
class ResumeServiceTest {

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private PositionService positionService;

    @Mock
    private CandidateService candidateService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private ResumeService resumeService;

    private Position testPosition;
    private Candidate testCandidate;
    private Resume testResume;

    @BeforeEach
    void setUp() {
        testPosition = TestDataBuilder.createTestPosition();
        testCandidate = TestDataBuilder.createTestCandidate();
        testResume = TestDataBuilder.createTestResume(testPosition.getPositionId(), testCandidate.getCandidateId());
    }

    @Nested
    @DisplayName("简历投递检查机制测试")
    class ResumeCheckMechanismTests {

        @Test
        @DisplayName("同一候选人重复投递同一职位应被拒绝")
        void shouldRejectDuplicateApplication() {
            String positionId = testPosition.getPositionId();
            String candidateId = testCandidate.getCandidateId();
            String phone = testCandidate.getCandidatePhone();

            when(positionService.getPosition(positionId)).thenReturn(testPosition);
            when(candidateService.createOrGetCandidate(anyString(), eq(phone), any(), any(), any()))
                    .thenReturn(testCandidate);
            when(resumeRepository.existsByPositionIdAndCandidateId(positionId, candidateId))
                    .thenReturn(true);

            ResumeSubmitRequest request = TestDataBuilder.createResumeSubmitRequest(positionId, phone);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> resumeService.submitResume(request));

            assertEquals("已投递过该职位，请勿重复投递", exception.getMessage());
            verify(resumeRepository, never()).save(any(Resume.class));
            verify(analysisService, never()).incrementResumeCount();
        }

        @Test
        @DisplayName("同一候选人可投递不同职位")
        void shouldAllowSameCandidateDifferentPosition() {
            Position position1 = TestDataBuilder.createTestPosition();
            Position position2 = TestDataBuilder.createTestPosition();
            String phone = testCandidate.getCandidatePhone();

            when(positionService.getPosition(position1.getPositionId())).thenReturn(position1);
            when(positionService.getPosition(position2.getPositionId())).thenReturn(position2);
            when(candidateService.createOrGetCandidate(anyString(), eq(phone), any(), any(), any()))
                    .thenReturn(testCandidate);

            when(resumeRepository.existsByPositionIdAndCandidateId(position1.getPositionId(), testCandidate.getCandidateId()))
                    .thenReturn(false);
            when(resumeRepository.existsByPositionIdAndCandidateId(position2.getPositionId(), testCandidate.getCandidateId()))
                    .thenReturn(false);
            when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeSubmitRequest request1 = TestDataBuilder.createResumeSubmitRequest(position1.getPositionId(), phone);
            ResumeSubmitResponse response1 = resumeService.submitResume(request1);

            ResumeSubmitRequest request2 = TestDataBuilder.createResumeSubmitRequest(position2.getPositionId(), phone);
            ResumeSubmitResponse response2 = resumeService.submitResume(request2);

            assertNotNull(response1.getResumeId());
            assertNotNull(response2.getResumeId());
            assertNotEquals(response1.getResumeId(), response2.getResumeId());

            verify(resumeRepository, times(2)).save(any(Resume.class));
            verify(analysisService, times(2)).incrementResumeCount();
        }

        @Test
        @DisplayName("职位关闭时拒绝投递")
        void shouldRejectWhenPositionClosed() {
            Position closedPosition = TestDataBuilder.createClosedPosition();

            doThrow(new RuntimeException("职位已关闭"))
                    .when(positionService).validatePositionForApplication(closedPosition.getPositionId());

            ResumeSubmitRequest request = TestDataBuilder.createResumeSubmitRequest(closedPosition.getPositionId());

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> resumeService.submitResume(request));

            assertEquals("职位已关闭", exception.getMessage());
            verify(resumeRepository, never()).save(any(Resume.class));
        }

        @Test
        @DisplayName("职位暂停时拒绝投递")
        void shouldRejectWhenPositionSuspended() {
            Position suspendedPosition = TestDataBuilder.createSuspendedPosition();

            doThrow(new RuntimeException("职位已暂停招聘"))
                    .when(positionService).validatePositionForApplication(suspendedPosition.getPositionId());

            ResumeSubmitRequest request = TestDataBuilder.createResumeSubmitRequest(suspendedPosition.getPositionId());

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> resumeService.submitResume(request));

            assertEquals("职位已暂停招聘", exception.getMessage());
            verify(resumeRepository, never()).save(any(Resume.class));
        }

        @Test
        @DisplayName("职位未在招聘中时拒绝投递")
        void shouldRejectWhenPositionNotRecruiting() {
            Position draftPosition = TestDataBuilder.createDraftPosition();

            doThrow(new RuntimeException("职位未在招聘中"))
                    .when(positionService).validatePositionForApplication(draftPosition.getPositionId());

            ResumeSubmitRequest request = TestDataBuilder.createResumeSubmitRequest(draftPosition.getPositionId());

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> resumeService.submitResume(request));

            assertEquals("职位未在招聘中", exception.getMessage());
            verify(resumeRepository, never()).save(any(Resume.class));
        }
    }

    @Nested
    @DisplayName("简历状态流转测试")
    class ResumeStatusFlowTests {

        @Test
        @DisplayName("简历投递后状态应为待筛选")
        void shouldHavePendingScreenStatusAfterSubmit() {
            String positionId = testPosition.getPositionId();
            String phone = testCandidate.getCandidatePhone();

            when(positionService.getPosition(positionId)).thenReturn(testPosition);
            when(candidateService.createOrGetCandidate(anyString(), eq(phone), any(), any(), any()))
                    .thenReturn(testCandidate);
            when(resumeRepository.existsByPositionIdAndCandidateId(positionId, testCandidate.getCandidateId()))
                    .thenReturn(false);
            when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeSubmitRequest request = TestDataBuilder.createResumeSubmitRequest(positionId, phone);
            ResumeSubmitResponse response = resumeService.submitResume(request);

            assertEquals(ResumeStatus.PENDING_SCREEN.name(), response.getStatus());

            ArgumentCaptor<Resume> resumeCaptor = ArgumentCaptor.forClass(Resume.class);
            verify(resumeRepository).save(resumeCaptor.capture());
            assertEquals(ResumeStatus.PENDING_SCREEN, resumeCaptor.getValue().getResumeStatus());
        }

        @Test
        @DisplayName("简历筛选通过后状态应为已筛选")
        void shouldHaveScreenedStatusAfterPass() {
            String resumeId = testResume.getResumeId();
            testResume.setResumeStatus(ResumeStatus.PENDING_SCREEN);

            when(resumeRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testResume));
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(workflowService.evaluateScreenRules(any(), any(), any())).thenReturn(true);
            when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeScreenRequest request = TestDataBuilder.createResumeScreenRequest(resumeId, true);
            Resume result = resumeService.screenResume(request);

            assertEquals(ResumeStatus.SCREENED, result.getResumeStatus());
            assertNotNull(result.getScreenedAt());
            assertEquals("符合要求，进入面试", result.getScreenResult());

            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.SCREENED);
            verify(analysisService).incrementScreenedCount();
        }

        @Test
        @DisplayName("简历筛选不通过后状态应为已淘汰")
        void shouldHaveRejectedStatusAfterFail() {
            String resumeId = testResume.getResumeId();
            testResume.setResumeStatus(ResumeStatus.PENDING_SCREEN);

            when(resumeRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testResume));
            when(positionService.getPosition(testPosition.getPositionId())).thenReturn(testPosition);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeScreenRequest request = TestDataBuilder.createResumeScreenRequest(resumeId, false);
            Resume result = resumeService.screenResume(request);

            assertEquals(ResumeStatus.REJECTED_SCREEN, result.getResumeStatus());
            assertNotNull(result.getScreenedAt());
            assertEquals("经验不足", result.getRejectReason());

            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.REJECTED);
            verify(analysisService).incrementRejectCount();
        }

        @Test
        @DisplayName("重复筛选应被拒绝")
        void shouldRejectDuplicateScreening() {
            String resumeId = testResume.getResumeId();
            testResume.setResumeStatus(ResumeStatus.SCREENED);

            when(resumeRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testResume));

            ResumeScreenRequest request = TestDataBuilder.createResumeScreenRequest(resumeId, true);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> resumeService.screenResume(request));

            assertEquals("简历已筛选", exception.getMessage());
        }

        @Test
        @DisplayName("非待筛选状态不能筛选")
        void shouldRejectScreeningWhenNotPending() {
            String resumeId = testResume.getResumeId();
            testResume.setResumeStatus(ResumeStatus.IN_INTERVIEW);

            when(resumeRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testResume));

            ResumeScreenRequest request = TestDataBuilder.createResumeScreenRequest(resumeId, true);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> resumeService.screenResume(request));

            assertTrue(exception.getMessage().contains("简历状态不允许筛选"));
        }
    }

    @Nested
    @DisplayName("简历投递历史检查测试")
    class ResumeHistoryTests {

        @Test
        @DisplayName("不同候选人可投递同一职位")
        void shouldAllowDifferentCandidatesSamePosition() {
            String positionId = testPosition.getPositionId();

            Candidate candidate1 = TestDataBuilder.createTestCandidate("候选人1", "13800000001");
            Candidate candidate2 = TestDataBuilder.createTestCandidate("候选人2", "13800000002");

            when(positionService.getPosition(positionId)).thenReturn(testPosition);

            when(candidateService.createOrGetCandidate(eq("候选人1"), eq("13800000001"), any(), any(), any()))
                    .thenReturn(candidate1);
            when(candidateService.createOrGetCandidate(eq("候选人2"), eq("13800000002"), any(), any(), any()))
                    .thenReturn(candidate2);

            when(resumeRepository.existsByPositionIdAndCandidateId(positionId, candidate1.getCandidateId()))
                    .thenReturn(false);
            when(resumeRepository.existsByPositionIdAndCandidateId(positionId, candidate2.getCandidateId()))
                    .thenReturn(false);
            when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeSubmitRequest request1 = ResumeSubmitRequest.builder()
                    .positionId(positionId)
                    .candidateName("候选人1")
                    .candidatePhone("13800000001")
                    .build();
            ResumeSubmitResponse response1 = resumeService.submitResume(request1);

            ResumeSubmitRequest request2 = ResumeSubmitRequest.builder()
                    .positionId(positionId)
                    .candidateName("候选人2")
                    .candidatePhone("13800000002")
                    .build();
            ResumeSubmitResponse response2 = resumeService.submitResume(request2);

            assertNotNull(response1.getResumeId());
            assertNotNull(response2.getResumeId());
            assertNotEquals(response1.getCandidateId(), response2.getCandidateId());

            verify(resumeRepository, times(2)).save(any(Resume.class));
        }
    }

    @Nested
    @DisplayName("候选人信息处理测试")
    class CandidateHandlingTests {

        @Test
        @DisplayName("现有候选人应复用而不是重复创建")
        void shouldReuseExistingCandidate() {
            String positionId = testPosition.getPositionId();
            String phone = testCandidate.getCandidatePhone();

            when(positionService.getPosition(positionId)).thenReturn(testPosition);
            when(candidateService.createOrGetCandidate(anyString(), eq(phone), any(), any(), any()))
                    .thenReturn(testCandidate);
            when(resumeRepository.existsByPositionIdAndCandidateId(positionId, testCandidate.getCandidateId()))
                    .thenReturn(false);
            when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeSubmitRequest request = TestDataBuilder.createResumeSubmitRequest(positionId, phone);
            ResumeSubmitResponse response = resumeService.submitResume(request);

            assertEquals(testCandidate.getCandidateId(), response.getCandidateId());
            verify(candidateService, times(1))
                    .createOrGetCandidate(anyString(), eq(phone), any(), any(), any());
        }

        @Test
        @DisplayName("简历投递后候选人状态更新为已申请")
        void shouldUpdateCandidateStatusToApplied() {
            String positionId = testPosition.getPositionId();
            String phone = testCandidate.getCandidatePhone();

            when(positionService.getPosition(positionId)).thenReturn(testPosition);
            when(candidateService.createOrGetCandidate(anyString(), eq(phone), any(), any(), any()))
                    .thenReturn(testCandidate);
            when(resumeRepository.existsByPositionIdAndCandidateId(positionId, testCandidate.getCandidateId()))
                    .thenReturn(false);
            when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeSubmitRequest request = TestDataBuilder.createResumeSubmitRequest(positionId, phone);
            resumeService.submitResume(request);

            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.APPLIED);
        }
    }

    @Test
    @DisplayName("获取不存在的简历应抛出异常")
    void shouldThrowWhenResumeNotFound() {
        when(resumeRepository.findByResumeId("nonexistent")).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> resumeService.getResume("nonexistent"));

        assertEquals("简历不存在: nonexistent", exception.getMessage());
    }

    @Test
    @DisplayName("简历状态更新功能正常")
    void shouldUpdateResumeStatus() {
        String resumeId = testResume.getResumeId();

        when(resumeRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testResume));
        when(resumeRepository.save(any(Resume.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Resume result = resumeService.updateResumeStatus(resumeId, ResumeStatus.IN_INTERVIEW);

        assertEquals(ResumeStatus.IN_INTERVIEW, result.getResumeStatus());
    }
}
