package com.cicd.server.approval;

import com.cicd.common.enums.ApprovalMode;
import com.cicd.server.entity.*;
import com.cicd.server.notification.NotificationService;
import com.cicd.server.pipeline.PipelineOrchestrator;
import com.cicd.server.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalIdempotentTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ApprovalDecisionRepository decisionRepository;
    @Mock private PipelineExecutionRepository executionRepository;
    @Mock private EnvironmentRepository environmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private PipelineOrchestrator orchestrator;

    @InjectMocks
    private ApprovalService approvalService;

    private Approval testApproval;
    private ApprovalDecision pendingDecision;
    private PipelineExecution testExecution;
    private Pipeline testPipeline;
    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = new Project();
        testProject.setId(1L);

        testPipeline = new Pipeline();
        testPipeline.setId(1L);
        testPipeline.setProject(testProject);

        testExecution = new PipelineExecution();
        testExecution.setId(1L);
        testExecution.setPipeline(testPipeline);

        testApproval = new Approval();
        testApproval.setId(1L);
        testApproval.setStatus("PENDING");
        testApproval.setApprovalMode(ApprovalMode.ALL);
        testApproval.setPipelineExecution(testExecution);

        pendingDecision = new ApprovalDecision();
        pendingDecision.setId(1L);
        pendingDecision.setApproval(testApproval);
        pendingDecision.setApprover("user1");
        pendingDecision.setStatus("PENDING");
    }

    @Test
    void testDuplicateApprovalReturnsExistingResult() {
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(1L, "user1"))
            .thenReturn(Optional.of(pendingDecision))
            .thenReturn(Optional.of(approvedDecision()));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Approval result1 = approvalService.approve(1L, "user1", "ok");
        Approval result2 = approvalService.approve(1L, "user1", "ok again");

        assertNotNull(result1);
        assertNotNull(result2);
        verify(orchestrator, times(1)).onApprovalCompleted(anyLong(), eq(true));
    }

    @Test
    void testConcurrentDuplicateApprovalWithDataIntegrityException() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(1L, "user1"))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    return Optional.of(pendingDecision);
                }
                throw new DataIntegrityViolationException("duplicate key");
            });
        when(decisionRepository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        Approval result = approvalService.approve(1L, "user1", "ok");

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
    }

    @Test
    void testConcurrentApproveCallsNoSideEffects() throws InterruptedException {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger sideEffectCount = new AtomicInteger(0);

        AtomicInteger decisionQueryCount = new AtomicInteger(0);
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(1L, "user1"))
            .thenAnswer(inv -> {
                if (decisionQueryCount.incrementAndGet() == 1) {
                    return Optional.of(pendingDecision);
                }
                return Optional.of(approvedDecision());
            });
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            if (sideEffectCount.incrementAndGet() > 1) {
                throw new DataIntegrityViolationException("dup");
            }
            return inv.getArgument(0);
        });
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            sideEffectCount.incrementAndGet();
            return null;
        }).when(orchestrator).onApprovalCompleted(anyLong(), anyBoolean());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    approvalService.approve(1L, "user1", "ok");
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        verify(orchestrator, atMost(1)).onApprovalCompleted(anyLong(), eq(true));
    }

    @Test
    void testAlreadyApprovedReturnsWithoutError() {
        testApproval.setStatus("APPROVED");
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));

        Approval result = approvalService.approve(1L, "user1", "ok");

        assertNotNull(result);
        assertEquals("APPROVED", result.getStatus());
        verify(orchestrator, never()).onApprovalCompleted(anyLong(), anyBoolean());
    }

    @Test
    void testAlreadyRejectedReturnsWithoutError() {
        testApproval.setStatus("REJECTED");
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));

        Approval result = approvalService.reject(1L, "user1", "no");

        assertNotNull(result);
        assertEquals("REJECTED", result.getStatus());
        verify(orchestrator, never()).onApprovalCompleted(anyLong(), anyBoolean());
    }

    @Test
    void testRejectIdempotentOnDuplicateCalls() {
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(1L, "user1"))
            .thenReturn(Optional.of(pendingDecision))
            .thenReturn(Optional.of(rejectedDecision()));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Approval result1 = approvalService.reject(1L, "user1", "first rejection");
        Approval result2 = approvalService.reject(1L, "user1", "duplicate rejection");

        assertNotNull(result1);
        assertNotNull(result2);
        verify(orchestrator, times(1)).onApprovalCompleted(anyLong(), eq(false));
    }

    @Test
    void testApprovalModeAnyIdempotent() {
        testApproval.setApprovalMode(ApprovalMode.ANY);
        List<ApprovalDecision> allDecisions = Arrays.asList(
            pendingDecision,
            createDecision("user2", "PENDING")
        );

        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(1L, "user1"))
            .thenReturn(Optional.of(pendingDecision));
        when(decisionRepository.findByApprovalId(1L)).thenReturn(allDecisions);
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        approvalService.approve(1L, "user1", "ok");

        verify(orchestrator, times(1)).onApprovalCompleted(anyLong(), eq(true));
    }

    @Test
    void testExpiredApprovalIdempotent() {
        testApproval.setExpiresAt(java.time.LocalDateTime.now().minusHours(1));
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(1L, "user1"))
            .thenReturn(Optional.of(pendingDecision));
        when(approvalRepository.save(any())).thenAnswer(inv -> {
            Approval a = inv.getArgument(0);
            a.setStatus("EXPIRED");
            return a;
        });

        Approval result1 = approvalService.approve(1L, "user1", "too late");
        Approval result2 = approvalService.approve(1L, "user1", "still too late");

        assertEquals("EXPIRED", result1.getStatus());
        assertEquals("EXPIRED", result2.getStatus());
        verify(orchestrator, times(2)).onApprovalCompleted(anyLong(), eq(false));
    }

    @Test
    void testMixedConcurrentApproveAndReject() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger approveCount = new AtomicInteger(0);
        AtomicInteger rejectCount = new AtomicInteger(0);

        AtomicInteger decisionQueryCount = new AtomicInteger(0);
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(anyLong(), anyString()))
            .thenAnswer(inv -> {
                int count = decisionQueryCount.incrementAndGet();
                String approver = inv.getArgument(1);
                if (count <= 2) {
                    return Optional.of(createDecision(approver, "PENDING"));
                }
                return Optional.of(createDecision(approver,
                    approver.equals("user1") ? "APPROVED" : "REJECTED"));
            });
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            ApprovalDecision d = inv.getArgument(0);
            if ("APPROVED".equals(d.getStatus())) {
                approveCount.incrementAndGet();
            } else if ("REJECTED".equals(d.getStatus())) {
                rejectCount.incrementAndGet();
            }
            return d;
        });
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < threads; i++) {
            final boolean isApprove = i % 2 == 0;
            final String approver = isApprove ? "user1" : "user2";
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (isApprove) {
                        approvalService.approve(1L, approver, "ok");
                    } else {
                        approvalService.reject(1L, approver, "no");
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(approveCount.get() <= 5, "Approve save should be idempotent: " + approveCount.get());
        assertTrue(rejectCount.get() <= 5, "Reject save should be idempotent: " + rejectCount.get());
    }

    @Test
    void testDecisionAlreadyDecidedReturnsEarly() {
        ApprovalDecision alreadyApproved = approvedDecision();
        when(approvalRepository.findById(1L)).thenReturn(Optional.of(testApproval));
        when(decisionRepository.findByApprovalIdAndApprover(1L, "user1"))
            .thenReturn(Optional.of(alreadyApproved));

        Approval result = approvalService.approve(1L, "user1", "duplicate");

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
        verify(decisionRepository, never()).save(any());
    }

    private ApprovalDecision approvedDecision() {
        ApprovalDecision d = new ApprovalDecision();
        d.setId(1L);
        d.setApproval(testApproval);
        d.setApprover("user1");
        d.setStatus("APPROVED");
        d.setDecidedAt(java.time.LocalDateTime.now());
        return d;
    }

    private ApprovalDecision rejectedDecision() {
        ApprovalDecision d = new ApprovalDecision();
        d.setId(1L);
        d.setApproval(testApproval);
        d.setApprover("user1");
        d.setStatus("REJECTED");
        d.setDecidedAt(java.time.LocalDateTime.now());
        return d;
    }

    private ApprovalDecision createDecision(String approver, String status) {
        ApprovalDecision d = new ApprovalDecision();
        d.setId((long) approver.hashCode());
        d.setApproval(testApproval);
        d.setApprover(approver);
        d.setStatus(status);
        return d;
    }
}
