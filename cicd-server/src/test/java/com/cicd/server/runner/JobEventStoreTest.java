package com.cicd.server.runner;

import com.cicd.server.entity.JobEvent;
import com.cicd.server.repository.JobEventRepository;
import com.cicd.server.repository.JobExecutionRepository;
import com.cicd.server.repository.StepExecutionRepository;
import com.cicd.server.pipeline.PipelineOrchestrator;
import com.cicd.server.websocket.LogWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobEventStoreTest {

    @Mock
    private JobEventRepository eventRepository;

    @Mock
    private JobExecutionRepository jobExecutionRepository;

    @Mock
    private StepExecutionRepository stepExecutionRepository;

    @Mock
    private PipelineOrchestrator orchestrator;

    @Mock
    private LogWebSocketService logWebSocketService;

    private JobEventStore eventStore;

    @BeforeEach
    void setUp() {
        eventStore = new JobEventStore(
            eventRepository, jobExecutionRepository, stepExecutionRepository,
            orchestrator, logWebSocketService
        );
    }

    @Test
    void testGenerateJobToken() {
        String token = eventStore.generateJobToken(42L);

        assertNotNull(token);
        assertTrue(token.length() > 8);
        assertTrue(token.endsWith(Long.toHexString(42L)));
    }

    @Test
    void testValidateJobTokenFromCache() {
        String token = eventStore.generateJobToken(1L);

        assertTrue(eventStore.validateJobToken(1L, token));
        assertFalse(eventStore.validateJobToken(1L, "wrong-token"));
    }

    @Test
    void testValidateJobTokenFromRepository() {
        when(eventRepository.existsByJobToken("stored-token")).thenReturn(true);

        assertTrue(eventStore.validateJobToken(999L, "stored-token"));
        verify(eventRepository).existsByJobToken("stored-token");
    }

    @Test
    void testAppendEventJobAssigned() {
        when(eventRepository.save(any(JobEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        eventStore.appendEvent(
            "token-1", 1L, "JOB_ASSIGNED",
            null, null, null,
            100L, null, null, null, null
        );

        verify(eventRepository).save(argThat(event ->
            event.getJobId().equals(1L) &&
            event.getEventType().equals("JOB_ASSIGNED") &&
            event.getRunnerId().equals(100L) &&
            event.getJobToken().equals("token-1")
        ));
    }

    @Test
    void testAppendEventStepLogChunk() {
        when(eventRepository.save(any(JobEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        eventStore.appendEvent(
            "token-1", 1L, "STEP_LOG_CHUNK",
            2, "build-step", "RUNNING",
            null, "Building module A...", 0, null, null
        );

        verify(eventRepository).save(argThat(event ->
            event.getStepIndex().equals(2) &&
            event.getLogIncrement().equals("Building module A...") &&
            event.getEventType().equals("STEP_LOG_CHUNK")
        ));
    }

    @Test
    void testRecoverJobStateEmpty() {
        when(eventRepository.findByJobIdOrderByEventTimestampAsc(1L)).thenReturn(List.of());

        JobEventStore.JobStateRecovery recovery = eventStore.recoverJobState(1L);

        assertFalse(recovery.found());
        assertEquals(-1, recovery.lastCompletedStep());
    }

    @Test
    void testRecoverJobStateCompleted() {
        LocalDateTime now = LocalDateTime.now();

        JobEvent assigned = new JobEvent();
        assigned.setEventType("JOB_ASSIGNED");
        assigned.setEventTimestamp(now.minusMinutes(10));
        assigned.setRunnerId(100L);

        JobEvent stepStarted = new JobEvent();
        stepStarted.setEventType("STEP_STARTED");
        stepStarted.setStepIndex(0);
        stepStarted.setEventTimestamp(now.minusMinutes(9));

        JobEvent stepCompleted = new JobEvent();
        stepCompleted.setEventType("STEP_COMPLETED");
        stepCompleted.setStepIndex(0);
        stepCompleted.setStepStatus("SUCCESS");
        stepCompleted.setExitCode(0);
        stepCompleted.setEventTimestamp(now.minusMinutes(5));

        JobEvent jobCompleted = new JobEvent();
        jobCompleted.setEventType("JOB_COMPLETED");
        jobCompleted.setExitCode(0);
        jobCompleted.setEventTimestamp(now.minusMinutes(4));

        when(eventRepository.findByJobIdOrderByEventTimestampAsc(1L))
            .thenReturn(List.of(assigned, stepStarted, stepCompleted, jobCompleted));

        JobEventStore.JobStateRecovery recovery = eventStore.recoverJobState(1L);

        assertTrue(recovery.found());
        assertEquals("SUCCESS", recovery.jobStatus());
        assertEquals(0, recovery.lastCompletedStep());
        assertFalse(recovery.needsResend());
    }

    @Test
    void testRecoverJobStateRunningNeedsResend() {
        LocalDateTime now = LocalDateTime.now();

        JobEvent assigned = new JobEvent();
        assigned.setEventType("JOB_ASSIGNED");
        assigned.setEventTimestamp(now.minusHours(3));

        JobEvent stepStarted = new JobEvent();
        stepStarted.setEventType("STEP_STARTED");
        stepStarted.setStepIndex(0);
        stepStarted.setEventTimestamp(now.minusHours(3).plusMinutes(1));

        when(eventRepository.findByJobIdOrderByEventTimestampAsc(1L))
            .thenReturn(List.of(assigned, stepStarted));

        JobEventStore.JobStateRecovery recovery = eventStore.recoverJobState(1L);

        assertTrue(recovery.found());
        assertEquals("RUNNING", recovery.jobStatus());
        assertTrue(recovery.needsResend());
    }

    @Test
    void testRecoverJobStateFailed() {
        LocalDateTime now = LocalDateTime.now();

        JobEvent assigned = new JobEvent();
        assigned.setEventType("JOB_ASSIGNED");
        assigned.setEventTimestamp(now.minusMinutes(10));

        JobEvent stepFailed = new JobEvent();
        stepFailed.setEventType("STEP_FAILED");
        stepFailed.setStepIndex(0);
        stepFailed.setStepStatus("FAILED");
        stepFailed.setExitCode(1);
        stepFailed.setEventTimestamp(now.minusMinutes(8));

        when(eventRepository.findByJobIdOrderByEventTimestampAsc(1L))
            .thenReturn(List.of(assigned, stepFailed));

        JobEventStore.JobStateRecovery recovery = eventStore.recoverJobState(1L);

        assertTrue(recovery.found());
        assertEquals("FAILED", recovery.jobStatus());
        assertEquals(0, recovery.lastCompletedStep());
        assertEquals("FAILED", recovery.lastStepStatus());
    }

    @Test
    void testTokenCleanupOnJobCompletion() {
        when(eventRepository.save(any(JobEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        String token = eventStore.generateJobToken(1L);
        assertTrue(eventStore.validateJobToken(1L, token));

        eventStore.appendEvent(token, 1L, "JOB_COMPLETED",
            null, null, "SUCCESS", null, null, null, 0, null);

        assertFalse(eventStore.validateJobToken(1L, token));
    }
}
