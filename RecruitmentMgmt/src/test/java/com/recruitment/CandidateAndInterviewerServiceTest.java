package com.recruitment;

import com.recruitment.builder.TestDataBuilder;
import com.recruitment.common.enums.*;
import com.recruitment.model.Candidate;
import com.recruitment.model.Interviewer;
import com.recruitment.repository.CandidateRepository;
import com.recruitment.repository.InterviewerRepository;
import com.recruitment.service.CandidateService;
import com.recruitment.service.InterviewerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("候选人和面试官模块单元测试")
class CandidateAndInterviewerServiceTest {

    @Mock
    private CandidateRepository candidateRepository;

    @Mock
    private InterviewerRepository interviewerRepository;

    @InjectMocks
    private CandidateService candidateService;

    @InjectMocks
    private InterviewerService interviewerService;

    private Candidate testCandidate;
    private Interviewer testInterviewer;

    @BeforeEach
    void setUp() {
        testCandidate = TestDataBuilder.createTestCandidate();
        testInterviewer = TestDataBuilder.createTestInterviewer();
    }

    @Nested
    @DisplayName("候选人模块测试")
    class CandidateServiceTests {

        @Nested
        @DisplayName("候选人创建测试")
        class CandidateCreationTests {

            @Test
            @DisplayName("应创建新候选人")
            void shouldCreateNewCandidate() {
                String name = "张三";
                String phone = "13800000001";
                String email = "zhangsan@example.com";

                when(candidateRepository.findByCandidatePhone(phone)).thenReturn(Optional.empty());
                when(candidateRepository.save(any(Candidate.class))).thenAnswer(invocation -> {
                    Candidate c = invocation.getArgument(0);
                    c.setCandidateId("candidate_001");
                    return c;
                });

                Candidate result = candidateService.createOrGetCandidate(name, phone, email, "本科", "5年");

                assertNotNull(result);
                assertEquals(name, result.getCandidateName());
                assertEquals(phone, result.getCandidatePhone());
                assertEquals(email, result.getCandidateEmail());
                assertEquals(CandidateStatus.REGISTERED, result.getCandidateStatus());

                ArgumentCaptor<Candidate> captor = ArgumentCaptor.forClass(Candidate.class);
                verify(candidateRepository).save(captor.capture());
                assertEquals(name, captor.getValue().getCandidateName());
            }

            @Test
            @DisplayName("应复用现有候选人并更新信息")
            void shouldReuseAndUpdateExistingCandidate() {
                String phone = testCandidate.getCandidatePhone();
                String newName = "新名字";
                String newEmail = "newemail@example.com";

                when(candidateRepository.findByCandidatePhone(phone)).thenReturn(Optional.of(testCandidate));
                when(candidateRepository.save(any(Candidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

                Candidate result = candidateService.createOrGetCandidate(newName, phone, newEmail, "硕士", "6年");

                assertEquals(testCandidate.getCandidateId(), result.getCandidateId());
                assertEquals(newName, result.getCandidateName());
                assertEquals(newEmail, result.getCandidateEmail());
                assertEquals("硕士", result.getCandidateEducation());
                assertEquals("6年", result.getCandidateExperience());
            }

            @Test
            @DisplayName("空信息不应覆盖现有值")
            void shouldNotOverrideWithEmptyValues() {
                String phone = testCandidate.getCandidatePhone();
                String originalName = testCandidate.getCandidateName();
                String originalEmail = testCandidate.getCandidateEmail();

                when(candidateRepository.findByCandidatePhone(phone)).thenReturn(Optional.of(testCandidate));
                when(candidateRepository.save(any(Candidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

                Candidate result = candidateService.createOrGetCandidate("", phone, "", "", "");

                assertEquals(originalName, result.getCandidateName());
                assertEquals(originalEmail, result.getCandidateEmail());
            }
        }

        @Nested
        @DisplayName("候选人状态流转测试")
        class CandidateStatusFlowTests {

            @Test
            @DisplayName("应正确更新候选人状态")
            void shouldUpdateCandidateStatus() {
                String candidateId = testCandidate.getCandidateId();

                when(candidateRepository.findByCandidateId(candidateId)).thenReturn(Optional.of(testCandidate));
                when(candidateRepository.save(any(Candidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

                Candidate applied = candidateService.updateCandidateStatus(candidateId, CandidateStatus.APPLIED);
                assertEquals(CandidateStatus.APPLIED, applied.getCandidateStatus());

                Candidate screened = candidateService.updateCandidateStatus(candidateId, CandidateStatus.SCREENED);
                assertEquals(CandidateStatus.SCREENED, screened.getCandidateStatus());

                Candidate interviewing = candidateService.updateCandidateStatus(candidateId, CandidateStatus.INTERVIEWING);
                assertEquals(CandidateStatus.INTERVIEWING, interviewing.getCandidateStatus());

                Candidate inHire = candidateService.updateCandidateStatus(candidateId, CandidateStatus.IN_HIRE);
                assertEquals(CandidateStatus.IN_HIRE, inHire.getCandidateStatus());

                Candidate hired = candidateService.updateCandidateStatus(candidateId, CandidateStatus.HIRED);
                assertEquals(CandidateStatus.HIRED, hired.getCandidateStatus());
            }

            @Test
            @DisplayName("应支持淘汰状态")
            void shouldSupportRejectedStatus() {
                String candidateId = testCandidate.getCandidateId();

                when(candidateRepository.findByCandidateId(candidateId)).thenReturn(Optional.of(testCandidate));
                when(candidateRepository.save(any(Candidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

                Candidate rejected = candidateService.updateCandidateStatus(candidateId, CandidateStatus.REJECTED);
                assertEquals(CandidateStatus.REJECTED, rejected.getCandidateStatus());
            }

            @Test
            @DisplayName("候选人状态完整流转")
            void shouldSupportFullStatusFlow() {
                String candidateId = testCandidate.getCandidateId();

                when(candidateRepository.findByCandidateId(candidateId)).thenReturn(Optional.of(testCandidate));
                when(candidateRepository.save(any(Candidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

                CandidateStatus[] flow = {
                        CandidateStatus.REGISTERED,
                        CandidateStatus.APPLIED,
                        CandidateStatus.SCREENED,
                        CandidateStatus.INTERVIEWING,
                        CandidateStatus.IN_HIRE,
                        CandidateStatus.HIRED
                };

                for (CandidateStatus status : flow) {
                    Candidate updated = candidateService.updateCandidateStatus(candidateId, status);
                    assertEquals(status, updated.getCandidateStatus());
                }
            }
        }

        @Nested
        @DisplayName("候选人查询测试")
        class CandidateQueryTests {

            @Test
            @DisplayName("应通过ID获取候选人")
            void shouldGetCandidateById() {
                String candidateId = testCandidate.getCandidateId();

                when(candidateRepository.findByCandidateId(candidateId)).thenReturn(Optional.of(testCandidate));

                Candidate result = candidateService.getCandidate(candidateId);
                assertEquals(candidateId, result.getCandidateId());
                assertEquals(testCandidate.getCandidateName(), result.getCandidateName());
            }

            @Test
            @DisplayName("应通过手机号查找候选人")
            void shouldFindCandidateByPhone() {
                String phone = testCandidate.getCandidatePhone();

                when(candidateRepository.findByCandidatePhone(phone)).thenReturn(Optional.of(testCandidate));

                Optional<Candidate> result = candidateService.findCandidateByPhone(phone);
                assertTrue(result.isPresent());
                assertEquals(phone, result.get().getCandidatePhone());
            }

            @Test
            @DisplayName("应通过姓名搜索候选人")
            void shouldSearchCandidatesByName() {
                String name = "张三";
                List<Candidate> candidates = new ArrayList<>();
                candidates.add(testCandidate);

                when(candidateRepository.findByCandidateNameContaining(name)).thenReturn(candidates);

                List<Candidate> result = candidateService.searchCandidatesByName(name);
                assertEquals(1, result.size());
            }

            @Test
            @DisplayName("获取不存在的候选人应抛出异常")
            void shouldThrowWhenCandidateNotFound() {
                when(candidateRepository.findByCandidateId("nonexistent")).thenReturn(Optional.empty());

                RuntimeException exception = assertThrows(RuntimeException.class,
                        () -> candidateService.getCandidate("nonexistent"));

                assertTrue(exception.getMessage().contains("候选人不存在"));
            }

            @Test
            @DisplayName("应按状态获取候选人列表")
            void shouldGetCandidatesByStatus() {
                List<Candidate> candidates = new ArrayList<>();
                candidates.add(testCandidate);

                when(candidateRepository.findByCandidateStatus(CandidateStatus.APPLIED)).thenReturn(candidates);

                List<Candidate> result = candidateService.getCandidatesByStatus(CandidateStatus.APPLIED);
                assertEquals(1, result.size());
            }
        }
    }

    @Nested
    @DisplayName("面试官模块测试")
    class InterviewerServiceTests {

        @Nested
        @DisplayName("面试官创建测试")
        class InterviewerCreationTests {

            @Test
            @DisplayName("应创建新面试官")
            void shouldCreateNewInterviewer() {
                String name = "李经理";
                String department = "研发部";
                InterviewType type = InterviewType.TECHNICAL;

                when(interviewerRepository.save(any(Interviewer.class))).thenAnswer(invocation -> {
                    Interviewer i = invocation.getArgument(0);
                    i.setInterviewerId("interviewer_001");
                    return i;
                });

                Interviewer result = interviewerService.createInterviewer(name, department, type);

                assertNotNull(result);
                assertEquals(name, result.getInterviewerName());
                assertEquals(department, result.getInterviewerDepartment());
                assertEquals(type, result.getInterviewerType());
                assertEquals(InterviewerStatus.AVAILABLE, result.getInterviewerStatus());
                assertEquals(0, result.getInterviewerCount());
                assertEquals(0, result.getCompletedCount());

                ArgumentCaptor<Interviewer> captor = ArgumentCaptor.forClass(Interviewer.class);
                verify(interviewerRepository).save(captor.capture());
                assertEquals(name, captor.getValue().getInterviewerName());
            }
        }

        @Nested
        @DisplayName("面试官状态测试")
        class InterviewerStatusTests {

            @Test
            @DisplayName("应正确更新面试官状态")
            void shouldUpdateInterviewerStatus() {
                String interviewerId = testInterviewer.getInterviewerId();

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));
                when(interviewerRepository.save(any(Interviewer.class))).thenAnswer(invocation -> invocation.getArgument(0));

                Interviewer busy = interviewerService.updateInterviewerStatus(interviewerId, InterviewerStatus.BUSY);
                assertEquals(InterviewerStatus.BUSY, busy.getInterviewerStatus());

                Interviewer unavailable = interviewerService.updateInterviewerStatus(interviewerId, InterviewerStatus.UNAVAILABLE);
                assertEquals(InterviewerStatus.UNAVAILABLE, unavailable.getInterviewerStatus());

                Interviewer available = interviewerService.updateInterviewerStatus(interviewerId, InterviewerStatus.AVAILABLE);
                assertEquals(InterviewerStatus.AVAILABLE, available.getInterviewerStatus());
            }

            @Test
            @DisplayName("可用的面试官应该返回true")
            void shouldReturnTrueForAvailableInterviewer() {
                String interviewerId = testInterviewer.getInterviewerId();
                testInterviewer.setInterviewerStatus(InterviewerStatus.AVAILABLE);

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));

                assertTrue(interviewerService.isInterviewerAvailable(interviewerId));
            }

            @Test
            @DisplayName("不可用的面试官应该返回false")
            void shouldReturnFalseForUnavailableInterviewer() {
                String interviewerId = testInterviewer.getInterviewerId();
                testInterviewer.setInterviewerStatus(InterviewerStatus.UNAVAILABLE);

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));

                assertFalse(interviewerService.isInterviewerAvailable(interviewerId));
            }

            @Test
            @DisplayName("忙碌的面试官应该返回false")
            void shouldReturnFalseForBusyInterviewer() {
                String interviewerId = testInterviewer.getInterviewerId();
                testInterviewer.setInterviewerStatus(InterviewerStatus.BUSY);

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));

                assertFalse(interviewerService.isInterviewerAvailable(interviewerId));
            }

            @Test
            @DisplayName("不存在的面试官应该返回false")
            void shouldReturnFalseForNonExistentInterviewer() {
                when(interviewerRepository.findByInterviewerId("nonexistent")).thenReturn(Optional.empty());

                assertFalse(interviewerService.isInterviewerAvailable("nonexistent"));
            }
        }

        @Nested
        @DisplayName("面试官分配规则测试")
        class InterviewerAssignmentTests {

            @Test
            @DisplayName("应验证可用面试官可分配")
            void shouldValidateAvailableInterviewer() {
                String interviewerId = testInterviewer.getInterviewerId();
                testInterviewer.setInterviewerStatus(InterviewerStatus.AVAILABLE);

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));

                assertDoesNotThrow(() -> interviewerService.validateInterviewerForAssignment(interviewerId));
            }

            @Test
            @DisplayName("不可用面试官应被拒绝")
            void shouldRejectUnavailableInterviewer() {
                String interviewerId = testInterviewer.getInterviewerId();
                testInterviewer.setInterviewerStatus(InterviewerStatus.UNAVAILABLE);

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));

                RuntimeException exception = assertThrows(RuntimeException.class,
                        () -> interviewerService.validateInterviewerForAssignment(interviewerId));

                assertEquals("面试官不可用", exception.getMessage());
            }

            @Test
            @DisplayName("忙碌面试官应被拒绝")
            void shouldRejectBusyInterviewer() {
                String interviewerId = testInterviewer.getInterviewerId();
                testInterviewer.setInterviewerStatus(InterviewerStatus.BUSY);

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));

                RuntimeException exception = assertThrows(RuntimeException.class,
                        () -> interviewerService.validateInterviewerForAssignment(interviewerId));

                assertEquals("面试官当前忙碌", exception.getMessage());
            }

            @Test
            @DisplayName("应优先选择指定类型的面试官")
            void shouldPreferSpecificTypeInterviewer() {
                List<Interviewer> techInterviewers = new ArrayList<>();
                techInterviewers.add(testInterviewer);

                when(interviewerRepository.findByInterviewerStatusAndInterviewerType(
                        InterviewerStatus.AVAILABLE, InterviewType.TECHNICAL)).thenReturn(techInterviewers);

                Optional<Interviewer> result = interviewerService.findAvailableInterviewer(InterviewType.TECHNICAL);

                assertTrue(result.isPresent());
                assertEquals(InterviewType.TECHNICAL, result.get().getInterviewerType());
            }

            @Test
            @DisplayName("没有指定类型时应使用任意可用面试官")
            void shouldUseAnyAvailableWhenNoSpecificType() {
                List<Interviewer> emptyList = new ArrayList<>();
                List<Interviewer> allAvailable = new ArrayList<>();
                allAvailable.add(testInterviewer);

                when(interviewerRepository.findByInterviewerStatusAndInterviewerType(
                        InterviewerStatus.AVAILABLE, InterviewType.TECHNICAL)).thenReturn(emptyList);
                when(interviewerRepository.findByInterviewerStatus(InterviewerStatus.AVAILABLE)).thenReturn(allAvailable);

                Optional<Interviewer> result = interviewerService.findAvailableInterviewer(InterviewType.TECHNICAL);

                assertTrue(result.isPresent());
            }

            @Test
            @DisplayName("没有可用面试官时应返回空")
            void shouldReturnEmptyWhenNoInterviewersAvailable() {
                List<Interviewer> emptyList = new ArrayList<>();

                when(interviewerRepository.findByInterviewerStatusAndInterviewerType(
                        any(), any())).thenReturn(emptyList);
                when(interviewerRepository.findByInterviewerStatus(any())).thenReturn(emptyList);

                Optional<Interviewer> result = interviewerService.findAvailableInterviewer(InterviewType.TECHNICAL);

                assertFalse(result.isPresent());
            }
        }

        @Nested
        @DisplayName("面试官计数测试")
        class InterviewerCountTests {

            @Test
            @DisplayName("应递增面试计数")
            void shouldIncrementInterviewCount() {
                String interviewerId = testInterviewer.getInterviewerId();
                int initialCount = testInterviewer.getInterviewerCount();

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));
                when(interviewerRepository.save(any(Interviewer.class))).thenAnswer(invocation -> invocation.getArgument(0));

                interviewerService.incrementInterviewCount(interviewerId);

                ArgumentCaptor<Interviewer> captor = ArgumentCaptor.forClass(Interviewer.class);
                verify(interviewerRepository).save(captor.capture());
                assertEquals(initialCount + 1, captor.getValue().getInterviewerCount());
            }

            @Test
            @DisplayName("应递增完成计数")
            void shouldIncrementCompletedCount() {
                String interviewerId = testInterviewer.getInterviewerId();
                int initialCount = testInterviewer.getCompletedCount();

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));
                when(interviewerRepository.save(any(Interviewer.class))).thenAnswer(invocation -> invocation.getArgument(0));

                interviewerService.incrementCompletedCount(interviewerId);

                ArgumentCaptor<Interviewer> captor = ArgumentCaptor.forClass(Interviewer.class);
                verify(interviewerRepository).save(captor.capture());
                assertEquals(initialCount + 1, captor.getValue().getCompletedCount());
            }
        }

        @Nested
        @DisplayName("面试官查询测试")
        class InterviewerQueryTests {

            @Test
            @DisplayName("应通过ID获取面试官")
            void shouldGetInterviewerById() {
                String interviewerId = testInterviewer.getInterviewerId();

                when(interviewerRepository.findByInterviewerId(interviewerId)).thenReturn(Optional.of(testInterviewer));

                Interviewer result = interviewerService.getInterviewer(interviewerId);
                assertEquals(interviewerId, result.getInterviewerId());
                assertEquals(testInterviewer.getInterviewerName(), result.getInterviewerName());
            }

            @Test
            @DisplayName("获取不存在的面试官应抛出异常")
            void shouldThrowWhenInterviewerNotFound() {
                when(interviewerRepository.findByInterviewerId("nonexistent")).thenReturn(Optional.empty());

                RuntimeException exception = assertThrows(RuntimeException.class,
                        () -> interviewerService.getInterviewer("nonexistent"));

                assertTrue(exception.getMessage().contains("面试官不存在"));
            }

            @Test
            @DisplayName("应获取所有可用面试官")
            void shouldGetAllAvailableInterviewers() {
                List<Interviewer> interviewers = new ArrayList<>();
                interviewers.add(testInterviewer);

                when(interviewerRepository.findByInterviewerStatus(InterviewerStatus.AVAILABLE)).thenReturn(interviewers);

                List<Interviewer> result = interviewerService.getAvailableInterviewers();
                assertEquals(1, result.size());
            }

            @Test
            @DisplayName("应按类型获取面试官")
            void shouldGetInterviewersByType() {
                List<Interviewer> interviewers = new ArrayList<>();
                interviewers.add(testInterviewer);

                when(interviewerRepository.findByInterviewerType(InterviewType.TECHNICAL)).thenReturn(interviewers);

                List<Interviewer> result = interviewerService.getInterviewersByType(InterviewType.TECHNICAL);
                assertEquals(1, result.size());
            }

            @Test
            @DisplayName("应按类型和状态获取面试官")
            void shouldGetAvailableInterviewersByType() {
                List<Interviewer> interviewers = new ArrayList<>();
                interviewers.add(testInterviewer);

                when(interviewerRepository.findByInterviewerStatusAndInterviewerType(
                        InterviewerStatus.AVAILABLE, InterviewType.TECHNICAL)).thenReturn(interviewers);

                List<Interviewer> result = interviewerService.getAvailableInterviewersByType(InterviewType.TECHNICAL);
                assertEquals(1, result.size());
            }
        }
    }
}
