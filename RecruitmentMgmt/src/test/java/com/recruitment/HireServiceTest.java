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
import com.recruitment.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("录用模块单元测试")
class HireServiceTest {

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
    private HireService hireService;

    private Resume testResume;
    private Candidate testCandidate;
    private Hire testHire;

    @BeforeEach
    void setUp() {
        testResume = TestDataBuilder.createTestResume(ResumeStatus.INTERVIEW_PASSED);
        testCandidate = TestDataBuilder.createTestCandidate();
        testHire = TestDataBuilder.createTestHire(testResume.getResumeId(), testCandidate.getCandidateId());
    }

    @Nested
    @DisplayName("录用流程初始化测试")
    class HireInitiationTests {

        @Test
        @DisplayName("面试通过后初始化录用流程")
        void shouldInitiateHireProcessAfterInterviewPassed() {
            String resumeId = testResume.getResumeId();
            String candidateId = testCandidate.getCandidateId();
            String positionId = testResume.getPositionId();

            when(hireRepository.existsByResumeId(resumeId)).thenReturn(false);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Hire result = hireService.initiateHireProcess(resumeId, candidateId, positionId);

            assertNotNull(result.getHireId());
            assertEquals(HireStatus.PENDING_APPROVAL, result.getHireStatus());

            ArgumentCaptor<Hire> hireCaptor = ArgumentCaptor.forClass(Hire.class);
            verify(hireRepository).save(hireCaptor.capture());
            assertEquals(HireStatus.PENDING_APPROVAL, hireCaptor.getValue().getHireStatus());
            assertEquals(resumeId, hireCaptor.getValue().getResumeId());
            assertEquals(candidateId, hireCaptor.getValue().getCandidateId());
        }

        @Test
        @DisplayName("重复初始化应返回现有记录")
        void shouldReturnExistingHireWhenDuplicateInitiation() {
            String resumeId = testResume.getResumeId();
            String candidateId = testCandidate.getCandidateId();
            String positionId = testResume.getPositionId();

            when(hireRepository.existsByResumeId(resumeId)).thenReturn(true);
            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));

            Hire result = hireService.initiateHireProcess(resumeId, candidateId, positionId);

            assertEquals(testHire.getHireId(), result.getHireId());
            verify(hireRepository, never()).save(any(Hire.class));
        }
    }

    @Nested
    @DisplayName("录用审批测试")
    class HireApprovalTests {

        @Test
        @DisplayName("录用审批通过")
        void shouldApproveHireWhenApproved() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);
            HireApproveResponse response = hireService.approveHire(request);

            assertEquals(HireStatus.APPROVED.name(), response.getStatus());

            ArgumentCaptor<Hire> hireCaptor = ArgumentCaptor.forClass(Hire.class);
            verify(hireRepository).save(hireCaptor.capture());
            assertEquals(HireStatus.APPROVED, hireCaptor.getValue().getHireStatus());
            assertEquals("25K", hireCaptor.getValue().getHireSalary());
            assertNotNull(hireCaptor.getValue().getApprovedAt());

            verify(resumeService).updateResumeStatus(resumeId, ResumeStatus.HIRED);
            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.HIRED);
            verify(analysisService).incrementHireCount();
        }

        @Test
        @DisplayName("录用审批不通过")
        void shouldRejectHireWhenNotApproved() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));
            when(resumeService.getResume(resumeId)).thenReturn(testResume);
            when(candidateService.getCandidate(testCandidate.getCandidateId())).thenReturn(testCandidate);
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, false);
            HireApproveResponse response = hireService.approveHire(request);

            assertEquals(HireStatus.REJECTED.name(), response.getStatus());

            verify(resumeService).updateResumeStatus(resumeId, ResumeStatus.REJECTED);
            verify(candidateService).updateCandidateStatus(testCandidate.getCandidateId(), CandidateStatus.REJECTED);
            verify(analysisService).incrementRejectCount();
        }

        @Test
        @DisplayName("非待审批状态不能审批")
        void shouldRejectWhenNotPendingApproval() {
            String resumeId = testResume.getResumeId();
            testHire.setHireStatus(HireStatus.APPROVED);

            when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));

            HireApproveRequest request = TestDataBuilder.createHireApproveRequest(resumeId, true);

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> hireService.approveHire(request));

            assertTrue(exception.getMessage().contains("录用状态不允许审批"));
            verify(hireRepository, never()).save(any(Hire.class));
        }
    }

    @Nested
    @DisplayName("录用确认测试")
    class HireConfirmationTests {

        @Test
        @DisplayName("已审批的录用可以确认")
        void shouldConfirmApprovedHire() {
            String hireId = testHire.getHireId();
            testHire.setHireStatus(HireStatus.APPROVED);

            when(hireRepository.findByHireId(hireId)).thenReturn(Optional.of(testHire));
            when(hireRepository.save(any(Hire.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Hire result = hireService.confirmHire(hireId);

            assertEquals(HireStatus.CONFIRMED, result.getHireStatus());
        }

        @Test
        @DisplayName("非已审批状态不能确认")
        void shouldRejectConfirmationWhenNotApproved() {
            String hireId = testHire.getHireId();
            testHire.setHireStatus(HireStatus.PENDING_APPROVAL);

            when(hireRepository.findByHireId(hireId)).thenReturn(Optional.of(testHire));

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> hireService.confirmHire(hireId));

            assertTrue(exception.getMessage().contains("录用状态不允许确认"));
        }
    }

    @Test
    @DisplayName("获取不存在的录用应抛出异常")
    void shouldThrowWhenHireNotFound() {
        when(hireRepository.findByHireId("nonexistent")).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> hireService.getHire("nonexistent"));

        assertEquals("录用记录不存在: nonexistent", exception.getMessage());
    }

    @Test
    @DisplayName("获取简历对应的录用记录")
    void shouldGetHireByResume() {
        String resumeId = testResume.getResumeId();

        when(hireRepository.findByResumeId(resumeId)).thenReturn(Optional.of(testHire));

        Hire result = hireService.getHireByResume(resumeId);

        assertEquals(testHire.getHireId(), result.getHireId());
        assertEquals(resumeId, result.getResumeId());
    }

    @Test
    @DisplayName("简历没有录用记录时抛出异常")
    void shouldThrowWhenNoHireForResume() {
        when(hireRepository.findByResumeId("nonexistent")).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> hireService.getHireByResume("nonexistent"));

        assertTrue(exception.getMessage().contains("该简历没有录用记录"));
    }
}
